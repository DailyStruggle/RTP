/**
 * LeafRTP Relational Cross-Linker, Multi-Plane Search Engine, Helper Bot & Log/Config Doctor.
 *
 * Transforms the relational AST & FTS5 corpus (`doc-index.sqlite` -> `support-corpus.json`)
 * into three client-side experiences across the generated LeafCraft MkDocs website:
 *   1. Automatic Inline `<code>` Relational Cross-Linker & Hover Popover Cards (`Gov(c)` neighbors,
 *      schema directives, anti-pattern conflict warnings, and Web Editor spotlight links).
 *   2. Per-Page "Relational Cross-References & Governance Graph" Footer (`page_crosslinks`).
 *   3. Unified Multi-Plane Search Engine, Deterministic Helper Bot & Secret-Redacted Log/Config Doctor
 *      (with optional progressive enhancement against `/api/v1/triage`).
 */
(function () {
  'use strict';

  const BIT_CODE_RE = /\b(ACTADDON|ACTION|BIT|CLAIM|GOVERNOR|HEATMAP|PROBE|RTP|TEST)[-_](\d{1,4})\b/gi;
  const BIT_SYMBOL_RE = /\b((?:RTP|ACTADDON|CLAIM|PROBE|BIT|HEATMAP|ACTION|GOVERNOR)_[A-Z0-9_]{3,})\b/g;
  const PRIMARY_DOMAIN_KEYS = new Set([
    'step', 'direction', 'miny', 'maxy', 'requireskylight',
    'maxslope', 'sloperadius', 'maxhillangle',
    'strictactions', 'strictconfig', 'overrides',
    'syncallottedtime', 'asyncallottedtime', 'autotune', 'period',
    'triggers', 'gates', 'command', 'alias', 'rejectinclaim',
    'openonbarertp', 'syncloading', 'worldborderoverride', 'centerradius', 'radius'
  ]);
  const AMBIGUOUS_CODE_TOKENS = new Set([
    'true', 'false', 'null', 'none', 'world', 'default', 'name', 'type', 'id',
    'get', 'set', 'run', 'list', 'map', 'int', 'string', 'double', 'float', 'boolean'
  ]);
  const STOP_WORDS = new Set([
    'the', 'and', 'for', 'with', 'when', 'how', 'why', 'does', 'what', 'where', 'which', 'who',
    'from', 'into', 'that', 'this', 'these', 'those', 'there', 'their', 'them', 'they',
    'rtp', 'leafrtp', 'leaf', 'leafcraft', 'plugin', 'plugins', 'server', 'servers', 'minecraft',
    'make', 'making', 'made', 'get', 'getting', 'got', 'set', 'setting', 'use', 'using',
    'want', 'need', 'can', 'could', 'would', 'should', 'will', 'have', 'has', 'had',
    'change', 'changing', 'turn', 'work', 'working', 'way', 'more', 'less', 'very',
    'some', 'any', 'all', 'every', 'time', 'players', 'player', 'user', 'users',
    'certain', 'other', 'about', 'after', 'before', 'between', 'under', 'over'
  ]);

  const TYPE_BADGE_COLORS = {
    CONFIG_KEY: { bg: '#1e3a5f', fg: '#89b4fa', label: 'CONFIG' },
    BIT_CODE: { bg: '#45273d', fg: '#f38ba8', label: 'BIT' },
    CLI_COMMAND: { bg: '#1e3f35', fg: '#a6e3a1', label: 'CMD' },
    PERMISSION_NODE: { bg: '#3d331e', fg: '#f9e2af', label: 'PERM' },
    JAVA_CLASS: { bg: '#2c2547', fg: '#cba6f7', label: 'CLASS' },
    SPI_CONTRACT: { bg: '#1e3c47', fg: '#94e2d5', label: 'SPI' },
    ADR_RECORD: { bg: '#3b2d22', fg: '#fab387', label: 'ADR' },
    SAFETY_PROHIBITION: { bg: '#4a1c24', fg: '#f38ba8', label: 'SAFETY' },
    REQ_ID: { bg: '#2a374b', fg: '#74c7ec', label: 'REQ' },
    BEHAVIORAL_SYMPTOM: { bg: '#303446', fg: '#b4befe', label: 'SYMPTOM' },
    TEST_CLASS: { bg: '#253528', fg: '#a6e3a1', label: 'TEST' }
  };

  let corpusPromise = null;
  let cachedCorpus = null;
  let cachedIndexes = null;

  function resolveSitePrefix() {
    const p = (typeof window !== 'undefined' && window.location && window.location.pathname) ? window.location.pathname : '';
    if (/^\/mc(\/|$)/i.test(p)) return '/mc';
    if (/^\/RTP(\/|$)/i.test(p)) return '/RTP';
    return '/mc';
  }

  function normalizeSiteUrl(url) {
    if (!url || typeof url !== 'string') return '';
    const prefix = resolveSitePrefix();
    return url
      .replace(/^https?:\/\/dailystruggle\.github\.io\/RTP/i, 'https://docs.leafcraft.net/mc')
      .replace(/^https?:\/\/docs\.leafcraft\.net\/mc\/leafrtp\/(admin|editor|site|dev|adr|FOR_)/i, 'https://docs.leafcraft.net/mc/$1')
      .replace(/^\/mc\/leafrtp\/(admin|editor|site|dev|adr|FOR_)/i, '/mc/$1')
      .replace(/^\/RTP(\/|$)/i, prefix + '$1');
  }

  function configDocUrlForFile(filePath) {
    const prefix = resolveSitePrefix();
    const fp = String(filePath || '').trim().toLowerCase();
    if (!fp) return prefix + '/admin/configuration/CONFIGURATION/';
    if (fp.includes('performance.yml')) return prefix + '/admin/configuration/PERFORMANCE/';
    if (fp.includes('regions/') || fp === 'regions.yml') return prefix + '/admin/configuration/REGIONS/';
    if (fp.includes('worlds/') || fp === 'worlds.yml') return prefix + '/admin/configuration/WORLDS/';
    if (fp.includes('safety.yml')) return prefix + '/admin/configuration/SAFETY/';
    if (fp === 'config.yml') return prefix + '/admin/configuration/CORE_CONFIG/';
    if (fp.includes('economy.yml')) return prefix + '/admin/configuration/ECONOMY/';
    if (fp.includes('effects.yml') || fp.includes('events.yml')) return prefix + '/admin/configuration/EVENTS_AND_EFFECTS/';
    if (fp.includes('integrations.yml') || fp.includes('claims.yml')) return prefix + '/admin/configuration/INTEGRATIONS/';
    if (fp.includes('lang.yml')) return prefix + '/admin/configuration/LANGUAGE/';
    if (fp.includes('logging.yml')) return prefix + '/admin/configuration/LOGGING/';
    if (fp.includes('messages.yml') || fp.includes('placeholders.yml')) return prefix + '/admin/configuration/MESSAGES/';
    if (fp.includes('metrics.yml')) return prefix + '/admin/configuration/METRICS/';
    if (fp.includes('schematics.yml')) return prefix + '/admin/configuration/SCHEMATICS/';
    if (fp.includes('ttl.yml')) return prefix + '/admin/configuration/TTL/';
    if (fp.startsWith('addons/') || fp.includes('menu')) return prefix + '/admin/ADDONS/';
    if (fp.includes('actions/')) return prefix + '/admin/ACTIONS/';
    if (fp.includes('custom_items')) return prefix + '/admin/CUSTOM_ITEMS/';
    if (fp.includes('network.yml')) return prefix + '/admin/proxies/CONFIGURATION/';
    return prefix + '/admin/configuration/CONFIGURATION/';
  }

  function cleanConfigDescription(desc, maxChars) {
    if (!desc) return '';
    const limit = maxChars || 220;
    let base = String(desc).split('[Doc context:')[0].trim();
    base = base.replace(/\(Inherited from.*?\)\s*$/i, '').trim();
    if (base.length > limit) {
      const cut = base.slice(0, limit);
      const lastSpace = cut.lastIndexOf(' ');
      base = (lastSpace > 40 ? cut.slice(0, lastSpace) : cut) + '...';
    }
    return base;
  }

  function resolveCorpusUrl() {
    const scripts = document.querySelectorAll('script[src*="rtp-support-doctor.js"]');
    if (scripts.length > 0) {
      const src = scripts[0].src || scripts[0].getAttribute('src') || '';
      const qMatch = src.match(/\?v=([A-Za-z0-9._-]+)/);
      const qSuffix = qMatch ? ('?v=' + qMatch[1]) : '';
      const base = src.replace(/javascripts\/rtp-support-doctor\.js.*$/, '');
      return base + 'support-corpus.json' + qSuffix;
    }
    return resolveSitePrefix() + '/assets/support-corpus.json';
  }

  function resolveTriageApiBase() {
    if (typeof window !== 'undefined' && window.RTP_BOT_API_URL) {
      return String(window.RTP_BOT_API_URL).replace(/\/+$/, '');
    }
    const p = (typeof window !== 'undefined' && window.location && window.location.pathname) ? window.location.pathname : '';
    if (/^\/mc(\/|$)/i.test(p)) return '/mc';
    return '';
  }

  function buildRelationalIndexes(corpus) {
    if (!corpus || typeof corpus !== 'object') return null;
    const byId = new Map();
    const byLookupToken = new Map();
    const edgesBySrc = new Map();
    const edgesByDst = new Map();
    const claimsBySymbol = new Map();
    const conflictsBySymbol = new Map();
    const sitePrefix = resolveSitePrefix();

    // Index config files so `advanced/performance.yml`, `config.yml`, etc. are crosslinkable
    const cfgList = Array.isArray(corpus.config_keys) ? corpus.config_keys : [];
    const seenCfgFiles = new Set();
    for (const c of cfgList) {
      const fp = String(c.file_path || '').trim();
      if (!fp || seenCfgFiles.has(fp)) continue;
      seenCfgFiles.add(fp);
      const docUrl = configDocUrlForFile(fp);
      const fSym = {
        id: 'cfgfile:' + fp,
        name: fp,
        type: 'CONFIG_KEY',
        url: docUrl,
        summary: 'View ' + fp + ' configuration reference'
      };
      byId.set(fSym.id, fSym);
      byLookupToken.set(fp.toLowerCase(), fSym);
      byLookupToken.set('plugins/rtp/' + fp.toLowerCase(), fSym);
      const shortName = fp.split('/').pop().toLowerCase();
      if (shortName.endsWith('.yml') && !byLookupToken.has(shortName)) {
        byLookupToken.set(shortName, fSym);
      }
    }

    const syms = Array.isArray(corpus.ast_symbols) ? corpus.ast_symbols : [];
    for (const s of syms) {
      if (!s || !s.id) continue;
      if (s.url) s.url = normalizeSiteUrl(s.url);
      if (s.editor_url) s.editor_url = normalizeSiteUrl(s.editor_url);
      if (s.type === 'CONFIG_KEY' && (!s.url || s.url.includes('/editor/'))) {
        const fpPart = s.id.split(':').length >= 3 ? s.id.split(':')[1] : '';
        s.url = configDocUrlForFile(fpPart);
      }
      if (s.summary) s.summary = cleanConfigDescription(s.summary, 200);
      byId.set(s.id, s);
      if (s.type === 'BEHAVIORAL_SYMPTOM') continue;
      const nameLow = String(s.name || '').trim().toLowerCase();
      if (nameLow && !AMBIGUOUS_CODE_TOKENS.has(nameLow) && !STOP_WORDS.has(nameLow)) {
        if (!byLookupToken.has(nameLow)) byLookupToken.set(nameLow, s);
      }
      if (s.type === 'CONFIG_KEY') {
        const kp = s.id.split(':').slice(2).join(':');
        const kpLow = kp.toLowerCase();
        const leafLow = kpLow.split('.').pop();
        if (kpLow && !AMBIGUOUS_CODE_TOKENS.has(kpLow) && !STOP_WORDS.has(kpLow) && !byLookupToken.has(kpLow)) {
          byLookupToken.set(kpLow, s);
        }
        if (leafLow && leafLow.length >= 4 && !AMBIGUOUS_CODE_TOKENS.has(leafLow) && !STOP_WORDS.has(leafLow) && !byLookupToken.has(leafLow)) {
          byLookupToken.set(leafLow, s);
        }
      }
      if (Array.isArray(s.aliases)) {
        for (const al of s.aliases) {
          const alLow = String(al || '').trim().toLowerCase();
          if (alLow && alLow.length >= 4 && !alLow.includes(' ') && !AMBIGUOUS_CODE_TOKENS.has(alLow) && !STOP_WORDS.has(alLow) && !byLookupToken.has(alLow)) {
            byLookupToken.set(alLow, s);
          }
        }
      }
    }

    // Also index bit_codes by code (`CLAIM-0001`) and key (`CLAIM_PLUGIN_NOT_INTEGRATED`)
    const bitList = Array.isArray(corpus.bit_codes) ? corpus.bit_codes : [];
    for (const b of bitList) {
      const codeStr = (b.code || (b.namespace + '-' + String(b.id).padStart(4, '0'))).toLowerCase();
      const keyStr = String(b.key || '').toLowerCase();
      const synthSym = byId.get('bit:' + (b.code || '')) || byId.get('bit:' + b.key) || {
        id: 'bit:' + (b.code || b.key),
        name: (b.code || (b.namespace + '-' + b.id)) + ' (' + b.key + ')',
        type: 'BIT_CODE',
        url: sitePrefix + '/admin/RUNBOOK/',
        editor_url: '',
        summary: '[' + b.severity + '] ' + b.remediation
      };
      byId.set(synthSym.id, synthSym);
      if (codeStr) byLookupToken.set(codeStr, synthSym);
      if (keyStr) byLookupToken.set(keyStr, synthSym);
    }

    const edges = Array.isArray(corpus.ast_edges) ? corpus.ast_edges : [];
    for (const e of edges) {
      if (!e || !e.src || !e.dst) continue;
      if (!edgesBySrc.has(e.src)) edgesBySrc.set(e.src, []);
      edgesBySrc.get(e.src).push(e);
      if (!edgesByDst.has(e.dst)) edgesByDst.set(e.dst, []);
      edgesByDst.get(e.dst).push(e);
    }

    const claims = Array.isArray(corpus.ast_claims) ? corpus.ast_claims : [];
    for (const cl of claims) {
      if (!cl) continue;
      for (const sid of [cl.src, cl.dst]) {
        if (!sid) continue;
        if (!claimsBySymbol.has(sid)) claimsBySymbol.set(sid, []);
        claimsBySymbol.get(sid).push(cl);
      }
    }

    const conflicts = Array.isArray(corpus.ast_conflicts) ? corpus.ast_conflicts : [];
    for (const cf of conflicts) {
      if (!cf || !cf.focal_symbol_id) continue;
      if (cf.explanation) cf.explanation = cleanConfigDescription(cf.explanation, 260);
      const fid = cf.focal_symbol_id;
      if (!conflictsBySymbol.has(fid)) conflictsBySymbol.set(fid, []);
      conflictsBySymbol.get(fid).push(cf);
    }

    return {
      byId,
      byLookupToken,
      edgesBySrc,
      edgesByDst,
      claimsBySymbol,
      conflictsBySymbol
    };
  }

  function loadCorpus() {
    if (cachedCorpus) return Promise.resolve(cachedCorpus);
    if (corpusPromise) return corpusPromise;
    corpusPromise = fetch(resolveCorpusUrl(), { headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (data && typeof data === 'object') {
          cachedCorpus = data;
          cachedIndexes = buildRelationalIndexes(data);
        }
        return cachedCorpus;
      })
      .catch(() => null);
    return corpusPromise;
  }

  function redactSecrets(input) {
    if (!input || typeof input !== 'string') return { text: '', count: 0 };
    let text = input;
    let count = 0;
    text = text.replace(
      /\b(password|passwd|pass|secret|api[_-]?key|token|auth[_-]?token|private[_-]?key|access[_-]?key|client[_-]?secret|webhook[_-]?url)\b(\s*[:=]\s*)(["']?)([^\s"'#,\r\n]{4,})\3/gi,
      function (m, k, sep, q, val) {
        if (val === '<redacted>' || val === '***REDACTED***') return m;
        count++;
        return k + sep + q + '***REDACTED***' + q;
      }
    );
    text = text.replace(
      /\b((?:jdbc:(?:mysql|mariadb|postgresql|sqlserver):\/\/|redis(?:s)?:\/\/|mongodb(?:\+srv)?:\/\/)[^:\s\/]+:)([^@\s]+)(@)/gi,
      function (_m, pre, _pw, post) {
        count++;
        return pre + '***REDACTED***' + post;
      }
    );
    return { text, count };
  }

  function triageLocal(rawInput, corpus) {
    const red = redactSecrets(rawInput || '');
    const text = red.text;
    if (!corpus) {
      return {
        ok: false,
        secretRedactions: red.count,
        redactedText: text,
        bitFindings: [],
        configHits: [],
        codeHits: [],
        docHits: [],
        schemaIssues: [],
        resolvedSymbols: [],
        astNeighbors: [],
        causalClaims: [],
        conflictWarnings: [],
        helperBotCards: [],
        reqHits: [],
        helperBotSummary: ''
      };
    }

    const idx = cachedIndexes || buildRelationalIndexes(corpus);
    const bitFindings = [];
    const seenBits = new Set();
    const bitList = Array.isArray(corpus.bit_codes) ? corpus.bit_codes : [];

    let m;
    BIT_CODE_RE.lastIndex = 0;
    while ((m = BIT_CODE_RE.exec(text)) !== null) {
      const ns = m[1].toUpperCase();
      const id = parseInt(m[2], 10);
      const found = bitList.find((b) => b.namespace === ns && b.id === id);
      if (found && !seenBits.has(found.key)) {
        seenBits.add(found.key);
        bitFindings.push(found);
      }
    }
    BIT_SYMBOL_RE.lastIndex = 0;
    while ((m = BIT_SYMBOL_RE.exec(text)) !== null) {
      const sym = m[1];
      const found = bitList.find((b) => b.key === sym);
      if (found && !seenBits.has(found.key)) {
        seenBits.add(found.key);
        bitFindings.push(found);
      }
    }

    const codeHits = [];
    const seenClasses = new Set();
    const codeList = Array.isArray(corpus.code_sections) ? corpus.code_sections : [];
    const textLow = text.toLowerCase();
    const identWords = new Set(textLow.match(/[a-z][a-z0-9_]{3,64}/g) || []);
    for (const c of codeList) {
      const cn = c.class_name || '';
      if (cn.length >= 4 && identWords.has(cn.toLowerCase()) && !seenClasses.has(cn)) {
        seenClasses.add(cn);
        codeHits.push(c);
      }
    }

    // Bridge BIT codes from matched Java AST classes (e.g. ClaimSetupBit, ActionSetupBit)
    if (bitFindings.length === 0 && codeHits.length > 0) {
      for (const cs of codeHits.slice(0, 2)) {
        const diag = cs.diagnostics || '';
        const bitMatches = diag.match(/bit:\s*([A-Z][A-Z0-9_]{3,})/g) || [];
        for (const bm of bitMatches) {
          const sym = bm.replace(/^bit:\s*/, '').trim();
          const found = bitList.find((b) => b.key === sym);
          if (found && !seenBits.has(found.key)) {
            seenBits.add(found.key);
            bitFindings.push(found);
          }
        }
      }
    }

    const tokens = (textLow.match(/[a-z0-9_./-]{3,64}/g) || []).slice(0, 56);
    const directTokens = new Set(tokens.filter((t) => !STOP_WORDS.has(t)));
    identWords.forEach((w) => {
      if (!STOP_WORDS.has(w)) directTokens.add(w);
    });
    const expanded = new Set(directTokens);
    const aliases = corpus.locale_aliases || {};
    const syns = corpus.synonyms || {};
    for (const tok of tokens) {
      if (aliases[tok] && aliases[tok].key_path) {
        const aliasedKey = aliases[tok].key_path.toLowerCase();
        expanded.add(aliasedKey);
        directTokens.add(aliasedKey);
        directTokens.add(aliasedKey.split('.').pop());
      }
      if (Array.isArray(syns[tok])) {
        syns[tok].forEach((s) => expanded.add(String(s).toLowerCase()));
      }
    }

    // Relational AST Symbol & Symptom Resolution (direct query tokens only so synonyms don't trigger unrelated conflict alerts)
    const resolvedSymbols = [];
    const seenSymIds = new Set();
    const symList = Array.isArray(corpus.ast_symbols) ? corpus.ast_symbols : [];
    if (idx) {
      for (const tok of directTokens) {
        const directSym = idx.byLookupToken.get(tok);
        if (directSym && !seenSymIds.has(directSym.id)) {
          seenSymIds.add(directSym.id);
          resolvedSymbols.push(directSym);
        }
      }
    }
    // Match BEHAVIORAL_SYMPTOM nodes and CLI_COMMAND / ADR / REQ nodes by phrase/keyword overlap
    const scoredSymptoms = [];
    const stopWords = STOP_WORDS;
    const queryWords = Array.from(identWords).filter((w) => !stopWords.has(w) && w.length >= 3);
    const hasWholeWord = (textStr, word) => {
      const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      return new RegExp('\\b' + escaped + '\\b', 'i').test(textStr);
    };
    for (const s of symList) {
      if (seenSymIds.has(s.id)) continue;
      if (s.type === 'BEHAVIORAL_SYMPTOM') {
        const hay = (s.name + ' ' + (s.aliases || []).join(' ')).toLowerCase();
        let overlap = 0;
        let matchedWords = 0;
        for (const qw of queryWords) {
          if (hasWholeWord(hay, qw)) {
            matchedWords++;
            overlap += (hasWholeWord(s.name.toLowerCase(), qw) ? 3 : 1);
          }
        }
        if (overlap >= 3 && (matchedWords >= 2 || queryWords.length === 1)) {
          scoredSymptoms.push({ score: overlap, sym: s });
        }
      } else if (s.type === 'CLI_COMMAND' && textLow.includes(s.name.toLowerCase())) {
        seenSymIds.add(s.id);
        resolvedSymbols.push(s);
      }
    }
    scoredSymptoms.sort((a, b) => b.score - a.score);
    for (const item of scoredSymptoms.slice(0, 3)) {
      if (!seenSymIds.has(item.sym.id)) {
        seenSymIds.add(item.sym.id);
        resolvedSymbols.push(item.sym);
      }
    }

    // Traverse 1-hop relational `ast_edges` from resolved symbols
    const astNeighbors = [];
    const seenEdgeKeys = new Set();
    const neighborConfigBoost = new Map();
    if (idx) {
      for (const sym of resolvedSymbols.slice(0, 8)) {
        const outEdges = idx.edgesBySrc.get(sym.id) || [];
        const inEdges = idx.edgesByDst.get(sym.id) || [];
        for (const e of outEdges.concat(inEdges)) {
          const eKey = e.src + '|' + e.rel + '|' + e.dst;
          if (seenEdgeKeys.has(eKey)) continue;
          seenEdgeKeys.add(eKey);
          astNeighbors.push(e);
          const otherId = e.src === sym.id ? e.dst : e.src;
          if (otherId.startsWith('cfg:')) {
            const kp = otherId.split(':').slice(2).join(':').toLowerCase();
            const lf = kp.split('.').pop();
            neighborConfigBoost.set(kp, Math.max(neighborConfigBoost.get(kp) || 0, 145));
            neighborConfigBoost.set(lf, Math.max(neighborConfigBoost.get(lf) || 0, 130));
            expanded.add(lf);
          } else if (otherId.startsWith('cls:')) {
            const clsName = otherId.slice(4);
            const foundCls = codeList.find((c) => c.class_name === clsName);
            if (foundCls && !seenClasses.has(clsName)) {
              seenClasses.add(clsName);
              codeHits.push(foundCls);
            }
          }
        }
      }
    }

    // Gather matched `ast_claims` and `ast_conflicts`
    const causalClaims = [];
    const seenClaimIds = new Set();
    const conflictWarnings = [];
    const seenConflictIds = new Set();
    if (idx) {
      for (const sym of resolvedSymbols) {
        for (const cl of (idx.claimsBySymbol.get(sym.id) || [])) {
          if (!seenClaimIds.has(cl.id)) {
            seenClaimIds.add(cl.id);
            causalClaims.push(cl);
          }
        }
        for (const cf of (idx.conflictsBySymbol.get(sym.id) || [])) {
          if (!seenConflictIds.has(cf.id)) {
            seenConflictIds.add(cf.id);
            conflictWarnings.push(cf);
          }
        }
      }
    }
    const allConflicts = Array.isArray(corpus.ast_conflicts) ? corpus.ast_conflicts : [];
    for (const cf of allConflicts) {
      if (seenConflictIds.has(cf.id)) continue;
      const focalLeaf = String(cf.focal_symbol_id || '').split(':').pop().split('.').pop().toLowerCase();
      if (focalLeaf && focalLeaf.length >= 4 && !AMBIGUOUS_CODE_TOKENS.has(focalLeaf) && textLow.includes(focalLeaf)) {
        seenConflictIds.add(cf.id);
        conflictWarnings.push(cf);
      }
    }

    // Match `helper_bot_cards` and `traceability_rows`
    const helperCards = Array.isArray(corpus.helper_bot_cards) ? corpus.helper_bot_cards : [];
    const scoredHelperCards = [];
    for (const hc of helperCards) {
      let score = seenSymIds.has(hc.id) ? 100 : 0;
      let matchedWords = 0;
      const hay = (hc.title + ' ' + (hc.triggers || []).join(' ') + ' ' + (hc.quote || '')).toLowerCase();
      for (const qw of queryWords) {
        if (hasWholeWord(hay, qw)) {
          matchedWords++;
          score += (hasWholeWord(hc.title.toLowerCase(), qw) ? 18 : 6);
        }
      }
      if (score >= 100 || (score >= 24 && (matchedWords >= 2 || queryWords.length === 1))) {
        scoredHelperCards.push({ score, card: hc });
      }
    }
    scoredHelperCards.sort((a, b) => b.score - a.score);
    const helperBotCards = scoredHelperCards.slice(0, 3).map((x) => x.card);

    const traceRows = Array.isArray(corpus.traceability_rows) ? corpus.traceability_rows : [];
    const reqHits = [];
    for (const tr of traceRows) {
      const reqLow = String(tr.req_id || '').toLowerCase();
      const sumLow = String(tr.summary || '').toLowerCase();
      if (textLow.includes(reqLow) || seenSymIds.has('req:' + tr.req_id) ||
          (tr.implementing_classes || []).some((cn) => seenClasses.has(cn))) {
        reqHits.push(tr);
      } else {
        let rwScore = 0;
        for (const qw of queryWords) {
          if (sumLow.includes(qw)) rwScore++;
        }
        if (rwScore >= 3 && reqHits.length < 4) reqHits.push(tr);
      }
    }

    // Bridge config_refs from matched Java AST classes with query-overlap & domain priority
    const astRefBoost = new Map(neighborConfigBoost);
    for (const cs of codeHits.slice(0, 3)) {
      const refs = String(cs.config_refs || '').split(/\s+/).filter((r) => r.length >= 3);
      for (const rk of refs) {
        const rkLow = rk.toLowerCase();
        const stripped = rkLow.startsWith('reroll') && rkLow.length > 6 ? rkLow.slice(6) : rkLow;
        let boost = 70;
        if (identWords.has(rkLow) || (stripped.length >= 4 && textLow.includes(stripped))) {
          boost = 160;
        } else if (PRIMARY_DOMAIN_KEYS.has(rkLow)) {
          boost = 115;
        }
        if ((astRefBoost.get(rkLow) || 0) < boost) {
          astRefBoost.set(rkLow, boost);
        }
        expanded.add(rkLow);
      }
    }

    const cfgList = Array.isArray(corpus.config_keys) ? corpus.config_keys : [];
    const scoredCfg = [];
    for (const c of cfgList) {
      const kpLow = (c.key_path || '').toLowerCase();
      const leaf = kpLow.split('.').pop();
      const descClean = cleanConfigDescription(c.description || '', 240);
      const descLow = descClean.toLowerCase();
      let score = 0;
      if (astRefBoost.has(leaf) || astRefBoost.has(kpLow)) {
        score += Math.max(astRefBoost.get(leaf) || 0, astRefBoost.get(kpLow) || 0);
        if ((c.file_path || '') === 'definitions/regions/default.yml' || (c.file_path || '').startsWith('addons/') || (c.file_path || '').startsWith('advanced/')) {
          score += 15;
        }
      }
      for (const tok of expanded) {
        if (stopWords.has(tok)) continue;
        if (tok === kpLow || tok === leaf) score += 100;
        else if (kpLow.includes(tok) && tok.length >= 4) score += 45;
        else if (descLow.includes(tok) && tok.length >= 4) score += 12;
      }
      if (score > 0) {
        scoredCfg.push({
          score,
          item: Object.assign({}, c, { description: descClean })
        });
      }
    }
    scoredCfg.sort((a, b) => b.score - a.score);
    const seenCfgKeys = new Set();
    const configHits = [];
    for (const x of scoredCfg) {
      const canon = String(x.item.key_path || '').replace(/^defaults\./, '').toLowerCase();
      if (!seenCfgKeys.has(canon)) {
        seenCfgKeys.add(canon);
        configHits.push(x.item);
        if (configHits.length >= 6) break;
      }
    }

    // Client-side YAML @range / @type directive validation
    const schemaIssues = [];
    const byLeaf = new Map();
    for (const c of cfgList) {
      if (!c.directives) continue;
      const lf = String(c.key_path || '').split('.').pop().toLowerCase();
      if (!byLeaf.has(lf)) byLeaf.set(lf, c);
    }
    const lines = text.split(/\r?\n/).slice(0, 200);
    for (let i = 0; i < lines.length; i++) {
      const rawLine = lines[i].split('#')[0].trim();
      if (!rawLine || rawLine.startsWith('-') || !rawLine.includes(':')) continue;
      const idxColon = rawLine.indexOf(':');
      const kName = rawLine.slice(0, idxColon).trim().replace(/^['"]|['"]$/g, '').toLowerCase();
      const vStr = rawLine.slice(idxColon + 1).trim().replace(/^['"]|['"]$/g, '');
      if (!vStr || vStr.startsWith('{') || vStr.startsWith('[')) continue;
      const specItem = byLeaf.get(kName);
      if (!specItem) continue;
      const dir = specItem.directives || '';
      const rangeMatch = dir.match(/@range:\s*`?\[\s*([^,\]]+?)\s*,\s*([^\]]+?)\s*\]`?/i);
      if (rangeMatch) {
        const num = parseFloat(vStr);
        if (!Number.isNaN(num)) {
          const lo = parseFloat(rangeMatch[1]);
          const hi = parseFloat(rangeMatch[2]);
          if (!Number.isNaN(lo) && num < lo) {
            schemaIssues.push({
              file_path: specItem.file_path,
              key_path: specItem.key_path,
              reason: 'Value ' + vStr + ' is below minimum ' + lo + ' (' + dir + ')'
            });
          } else if (!Number.isNaN(hi) && num > hi) {
            schemaIssues.push({
              file_path: specItem.file_path,
              key_path: specItem.key_path,
              reason: 'Value ' + vStr + ' exceeds maximum ' + hi + ' (' + dir + ')'
            });
          }
        }
      }
    }

    const docList = Array.isArray(corpus.doc_sections) ? corpus.doc_sections : [];
    const scoredDocs = [];
    for (const d of docList) {
      const hLow = (d.heading || '').toLowerCase();
      const sLow = (d.snippet || '').toLowerCase();
      let score = 0;
      for (const tok of expanded) {
        if (tok.length < 3 || stopWords.has(tok)) continue;
        if (hLow.includes(tok)) score += 35;
        if (sLow.includes(tok)) score += 10;
      }
      if (score > 0) scoredDocs.push({ score, item: d });
    }
    scoredDocs.sort((a, b) => b.score - a.score);
    const docHits = scoredDocs.slice(0, 5).map((x) => Object.assign({}, x.item, { url: normalizeSiteUrl(x.item.url) }));

    // Synthesize actionable `recommendedChanges` for one-click Web Editor staging
    const recommendedChanges = [];
    const seenRecTargets = new Set();
    const sitePrefix = resolveSitePrefix();
    function buildCliCmd(fp, kp, val) {
      const leaf = String(kp || '').split('.').pop();
      if (String(fp || '').startsWith('definitions/regions/')) {
        const reg = fp.split('/').pop().replace(/\.ya?ml$/i, '');
        return '/rtp config regions ' + reg + ' ' + kp + '=' + val;
      }
      if (String(fp || '').startsWith('definitions/worlds/')) {
        const w = fp.split('/').pop().replace(/\.ya?ml$/i, '');
        return '/rtp config worlds ' + w + ' ' + leaf + '=' + val;
      }
      const alias = String(fp || 'config').split('/').pop().replace(/\.ya?ml$/i, '');
      return '/rtp config ' + alias + ' ' + kp + '=' + val;
    }
    function addRecChange(fp, kp, val, reason, defVal) {
      if (!fp || !kp) return;
      const targetId = fp + ':' + kp;
      if (seenRecTargets.has(targetId)) return;
      seenRecTargets.add(targetId);
      const leaf = String(kp).split('.').pop();
      const valStr = String(val);
      const reasonStr = cleanConfigDescription(String(reason || ''), 190);
      recommendedChanges.push({
        file_path: fp,
        key_path: kp,
        leaf: leaf,
        recommended_value: valStr,
        default_value: String(defVal || ''),
        reason: reasonStr,
        cli_command: buildCliCmd(fp, kp, valStr),
        editor_url: sitePrefix + '/editor/?file=' + encodeURIComponent(fp) + '&key=' + encodeURIComponent(leaf) + '&search=' + encodeURIComponent(leaf),
        editor_stage_url: sitePrefix + '/editor/?file=' + encodeURIComponent(fp) + '&key=' + encodeURIComponent(leaf) + '&value=' + encodeURIComponent(valStr) + '&intent=' + encodeURIComponent(reasonStr.slice(0, 90))
      });
    }

    for (const hc of helperBotCards) {
      for (const rc of (hc.recommended_changes || [])) {
        addRecChange(rc.file_path, rc.key_path, rc.recommended_value, rc.reason, rc.default_value);
      }
    }
    if (/(?:\bgui\b|\bchest\b|\bmenu\b|\binventory\b|openonbarertp|guimenu)/i.test(textLow)) {
      addRecChange('addons/guimenu.yml', 'openOnBareRtp', 'false', 'Disable intercepting bare /rtp with the chest/book destination picker GUI so /rtp teleports immediately.', 'true');
    }
    if (/(?:\bfaster\b|\bfast\b|\bspeed\b|\binstant\b|\bquick\b|\bslow\b|\bdelay\b|\bwait\b|\bpregen\b|cachecap)/i.test(textLow)) {
      addRecChange('definitions/regions/default.yml', 'cacheCap', '20', 'Pre-warm 20 safe teleport locations in the region queue so /rtp is zero-wait.', '10');
      addRecChange('advanced/performance.yml', 'postTeleportQueueing', 'true', 'Immediately schedule a background refill task whenever a player consumes a cached location.', 'true');
      addRecChange('advanced/performance.yml', 'period', '20', 'Run background queue refill checks every 20 ticks (1 second).', '20');
      addRecChange('advanced/performance.yml', 'syncLoading', 'false', 'Keep chunk loading asynchronous (S-005) so background pre-generation never stalls the main thread.', 'false');
    }
    if (/(?:syncloading|\blag\b|\btps\b|\bmspt\b|\bspike|\bstall)/i.test(textLow)) {
      addRecChange('advanced/performance.yml', 'syncLoading', 'false', 'Keep chunk loading asynchronous (S-005); synchronous chunk loads stall the main server thread.', 'false');
      addRecChange('advanced/performance.yml', 'maxMSPT', '35.0', 'Pause background RTP queue pre-generation earlier when server MSPT exceeds 35.0 ms.', '45.0');
      addRecChange('advanced/performance.yml', 'autoTune', 'true', 'Enable adaptive tick-budget pacing for background candidate generation.', 'true');
    }
    if (/(?:nether.*(?:roof|bedrock|128|ceiling)|(?:roof|bedrock|128).*nether)/i.test(textLow)) {
      addRecChange('definitions/regions/default.yml', 'vert.maxY', '120', 'Cap vertical search below the Y=128 Nether bedrock ceiling.', '127');
      addRecChange('definitions/regions/default.yml', 'vert.requireSkyLight', 'false', 'Disable sky-light requirement in ceiling dimensions like the Nether.', 'false');
    } else if (/nether/i.test(textLow) && /(?:overworld|sends|world_nether|wrong world)/i.test(textLow)) {
      addRecChange('definitions/worlds/world_nether.yml', 'region', 'nether', 'Route /rtp in world_nether to a dedicated nether region instead of falling back to default.', 'default');
      addRecChange('definitions/worlds/world_nether.yml', 'override', 'true', 'Allow world_nether to use its own region routing instead of redirecting to the default world.', 'false');
    }
    if (/(?:\bcaves?\b|underground|requireskylight|surface)/i.test(textLow)) {
      addRecChange('definitions/regions/default.yml', 'vert.requireSkyLight', 'true', 'Require sky light (>= 15) at the landing column so players only spawn on the surface.', 'true');
    }
    if (/(?:\bslope\b|\bcliff\b|\bsteep\b|maxslope|sloperadius|linearadjustor|jumpadjustor)/i.test(textLow)) {
      addRecChange('definitions/regions/default.yml', 'vert.maxSlope', '2.0', 'Reject steep cliff/ravine landing columns exceeding 2.0 blocks of elevation delta.', '2.0');
      addRecChange('definitions/regions/default.yml', 'vert.slopeRadius', '2', 'Sample a 2-block horizontal neighborhood around candidate landings (@range: [1, 4]).', '2');
    }
    if (/(?:\brefill\b|\bqueued\b|postteleportqueueing)/i.test(textLow) && !/unqueued/i.test(textLow)) {
      addRecChange('advanced/performance.yml', 'postTeleportQueueing', 'true', 'Immediately schedule a background refill task whenever a player consumes a cached location.', 'true');
      addRecChange('advanced/performance.yml', 'period', '20', 'Run background queue refill checks every 20 ticks (1 second).', '20');
    }
    if (/(?:\bclaim\b|griefprevention|worldguard|lands|towny|huskclaims|rejectinclaim|claim-000)/i.test(textLow)) {
      addRecChange('safety.yml', 'rejectInClaim', 'true', 'Reject candidate teleport locations inside protected land claims (S-003).', 'true');
    }
    for (const iss of schemaIssues) {
      const specItem = byLeaf.get(String(iss.key_path || '').split('.').pop().toLowerCase());
      const defVal = specItem && specItem.default_value ? specItem.default_value : '2';
      addRecChange(iss.file_path, iss.key_path, defVal, 'Restore valid schema value (' + iss.reason + ').', defVal);
    }

    // Synthesize a deterministic Helper Bot summary from the relational graph
    const summaryParts = [];
    if (helperBotCards.length > 0) {
      const hc = helperBotCards[0];
      const keysStr = (hc.governed_keys || []).map((k) => k.name).join(', ');
      const cmdsStr = (hc.mitigating_commands || []).map((c) => c.name).join(', ');
      summaryParts.push(
        '🤖 ' + hc.title + ': ' + (hc.quote || '') +
        (keysStr ? ' | Governing Keys: ' + keysStr : '') +
        (cmdsStr ? ' | Commands: ' + cmdsStr : '')
      );
    } else if (bitFindings.length > 0) {
      const b0 = bitFindings[0];
      summaryParts.push('🩺 ' + (b0.code || (b0.namespace + '-' + b0.id)) + ' (' + b0.key + '): ' + b0.remediation);
    } else if (recommendedChanges.length > 0) {
      const rLines = recommendedChanges.slice(0, 3).map((rc) =>
        '• `' + rc.file_path + '` → `' + rc.key_path + '`: `' + rc.recommended_value + '` — ' + rc.reason + (rc.cli_command ? ' (`' + rc.cli_command + '`)' : '')
      );
      summaryParts.push('🤖 Recommended Operator Settings:\n' + rLines.join('\n'));
    } else if (configHits.length > 0) {
      const c0 = configHits[0];
      summaryParts.push('🔧 `' + c0.file_path + '` → `' + c0.key_path + '` (default: `' + c0.default_value + '`): ' + c0.description);
    }

    return {
      ok: true,
      secretRedactions: red.count,
      redactedText: text,
      bitFindings,
      configHits,
      codeHits,
      docHits,
      schemaIssues,
      recommendedChanges: recommendedChanges.slice(0, 6),
      resolvedSymbols: resolvedSymbols.slice(0, 10),
      astNeighbors: astNeighbors.slice(0, 12),
      causalClaims: causalClaims.slice(0, 6),
      conflictWarnings: conflictWarnings.slice(0, 4),
      helperBotCards,
      reqHits: reqHits.slice(0, 5),
      helperBotSummary: summaryParts.join('\n\n')
    };
  }

  async function triage(rawInput, options) {
    const opts = options || {};
    const apiUrl = opts.apiUrl !== undefined ? opts.apiUrl : (window.RTP_BOT_API_URL || resolveTriageApiBase());
    if (apiUrl || opts.useLlm) {
      const endpoint = (apiUrl ? String(apiUrl).replace(/\/+$/, '') : '') + '/api/v1/triage';
      try {
        const res = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query: rawInput || '', use_llm: !!opts.useLlm })
        });
        if (res.ok) return await res.json();
      } catch (_e) {
        // Fall back to static client-side corpus
      }
    }
    const corpus = await loadCorpus();
    return triageLocal(rawInput, corpus);
  }

  function renderBotMarkdown(container, text) {
    container.innerHTML = '';
    const raw = String(text || '').trim();
    if (!raw) return;
    const blocks = raw.split(/\n{2,}/);
    blocks.forEach((blk) => {
      const lines = blk.split(/\r?\n/);
      const isBulletBlock = lines.every((ln) => !ln.trim() || /^[-*•]\s+/.test(ln.trim()) || /^\s{2,}/.test(ln));
      if (isBulletBlock && lines.some((ln) => /^[-*•]\s+/.test(ln.trim()))) {
        const ul = document.createElement('ul');
        ul.style.cssText = 'margin:6px 0 8px 18px;padding:0;display:flex;flex-direction:column;gap:6px;';
        lines.forEach((ln) => {
          const cleanLn = ln.trim().replace(/^[-*•]\s+/, '');
          if (!cleanLn) return;
          const li = document.createElement('li');
          li.innerHTML = formatInlineMarkdown(cleanLn);
          ul.appendChild(li);
        });
        container.appendChild(ul);
      } else {
        const p = document.createElement('div');
        p.style.cssText = 'margin:0 0 8px 0;white-space:pre-wrap;';
        p.innerHTML = formatInlineMarkdown(blk);
        container.appendChild(p);
      }
    });
  }

  function renderSymbolCrosslinkHtml(rawToken, displayLabel) {
    const label = displayLabel !== undefined ? displayLabel : rawToken;
    const idx = cachedIndexes || (cachedCorpus ? buildRelationalIndexes(cachedCorpus) : null);
    if (!idx) {
      return '<code style="background:#313244;color:#a6e3a1;padding:1px 5px;border-radius:4px;font-size:12px;">' + label + '</code>';
    }
    const rawLow = String(rawToken || '').trim().toLowerCase();
    const clean = String(rawToken || '')
      .replace(/^`|`$/g, '')
      .split('=')[0]
      .split(':')[0]
      .trim()
      .toLowerCase()
      .replace(/^(?:\.\/)?(?:plugins|config)\/(?:leaf)?rtp\//i, '');
    const sym = idx.byLookupToken.get(rawLow) || idx.byLookupToken.get(clean);
    if (!sym) {
      return '<code style="background:#313244;color:#a6e3a1;padding:1px 5px;border-radius:4px;font-size:12px;">' + label + '</code>';
    }
    const href = normalizeSiteUrl(sym.url || (resolveSitePrefix() + '/'));
    const tip = ((sym.type ? '[' + sym.type + '] ' : '') + (sym.name || '') + (sym.summary ? ' — ' + sym.summary : '')).replace(/"/g, '&quot;');
    const sid = String(sym.id || '').replace(/"/g, '&quot;');
    return (
      '<a class="rtp-baked-xref" href="' + href + '" target="_blank" rel="noopener" data-rtp-symbol-id="' + sid + '" title="' + tip + '" style="text-decoration:none;">' +
      '<code class="rtp-xref-chip" data-rtp-baked="1" data-rtp-symbol-id="' + sid + '" style="background:#313244;color:#89b4fa;padding:1px 5px;border-radius:4px;font-size:12px;border-bottom:1.5px dotted #89b4fa;">' + label + '</code>' +
      '</a>'
    );
  }

  function formatInlineMarkdown(str) {
    let esc = String(str || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
    esc = esc.replace(/\[([^\]]+)\]\((?:&lt;)?(https?:\/\/[^\s)&]+|\/[^\s)&]+)(?:&gt;)?\)/g, (_m, label, u) => {
      const norm = normalizeSiteUrl(u);
      return '<a href="' + norm + '" target="_blank" rel="noopener" style="color:#89b4fa;text-decoration:underline;">' + label + '</a>';
    });
    esc = esc.replace(/&lt;(https?:\/\/[^\s&]+)&gt;/g, (_m, u) => {
      const norm = normalizeSiteUrl(u);
      return '<a href="' + norm + '" target="_blank" rel="noopener" style="color:#89b4fa;text-decoration:underline;">' + norm + '</a>';
    });
    esc = esc.replace(/\*\*([^*]+)\*\*/g, '<strong style="color:#f5e0dc;">$1</strong>');
    esc = esc.replace(/`([^`]+)`/g, (_m, codeTxt) => renderSymbolCrosslinkHtml(codeTxt, codeTxt));
    // Also crosslink bare ADR-*, REQ-*, S-00*, and BIT citations outside HTML tags
    const parts = esc.split(/(<a\b[\s\S]*?<\/a>|<code\b[\s\S]*?<\/code>|<[^>]+>)/g);
    for (let i = 0; i < parts.length; i++) {
      if (!parts[i] || parts[i].startsWith('<')) continue;
      parts[i] = parts[i].replace(/\b((?:(?:leafanvil|leafrtp|treegen|sleuth|fuzz|sleep|anvil-api|commands-api)-)?ADR-\d{3}|REQ-[A-Z0-9-]+|S-00[1-7]|(?:RTP|CLAIM|ACTADDON|ANVIL|SLEUTH|TREEGEN|FUZZ)-\d{4})\b/g, (mTok) => {
        return renderSymbolCrosslinkHtml(mTok, mTok);
      });
    }
    return parts.join('');
  }

  function injectRelationalStyles() {
    if (document.getElementById('rtp-relational-styles')) return;
    const style = document.createElement('style');
    style.id = 'rtp-relational-styles';
    style.textContent = [
      'a.rtp-baked-xref {',
      '  text-decoration: none !important;',
      '  color: inherit;',
      '}',
      'a.rtp-prose-xref {',
      '  color: #89b4fa !important;',
      '  font-weight: 600;',
      '  border-bottom: 1.5px dotted #89b4fa;',
      '}',
      'code.rtp-xref-chip {',
      '  cursor: pointer;',
      '  border-bottom: 1.5px dotted #89b4fa;',
      '  transition: background-color 0.15s ease, color 0.15s ease;',
      '}',
      'code.rtp-xref-chip:hover, a.rtp-prose-xref:hover {',
      '  background-color: rgba(137, 180, 250, 0.18);',
      '}',
      '#rtp-xref-popover {',
      '  display: none;',
      '  position: fixed;',
      '  z-index: 1200;',
      '  max-width: 440px;',
      '  width: calc(100vw - 32px);',
      '  background: #1e1e2e;',
      '  color: #cdd6f4;',
      '  border: 1px solid #585b70;',
      '  border-radius: 10px;',
      '  padding: 14px;',
      '  box-shadow: 0 10px 28px rgba(0,0,0,0.55);',
      '  font-family: system-ui, -apple-system, sans-serif;',
      '  font-size: 12.5px;',
      '  line-height: 1.45;',
      '}',
      '.rtp-page-xref-footer {',
      '  margin-top: 32px;',
      '  padding: 16px 18px;',
      '  border-radius: 10px;',
      '  border: 1px solid rgba(137, 180, 250, 0.3);',
      '  background: rgba(30, 30, 46, 0.06);',
      '  font-size: 13px;',
      '}',
      '[data-md-color-scheme="slate"] .rtp-page-xref-footer {',
      '  background: rgba(30, 30, 46, 0.65);',
      '}',
      '.rtp-xref-pill {',
      '  display: inline-flex;',
      '  align-items: center;',
      '  gap: 5px;',
      '  margin: 3px 4px 3px 0;',
      '  padding: 3px 9px;',
      '  border-radius: 999px;',
      '  font-size: 11.5px;',
      '  font-family: monospace;',
      '  text-decoration: none !important;',
      '  border: 1px solid rgba(137, 180, 250, 0.35);',
      '  cursor: pointer;',
      '}'
    ].join('\n');
    document.head.appendChild(style);
  }

  function openDoctorWithQuery(queryStr) {
    const modal = document.getElementById('rtp-doctor-modal');
    const inp = document.getElementById('rtp-doctor-input');
    const runBtn = document.getElementById('rtp-doctor-run');
    if (!modal || !inp || !runBtn) return;
    modal.style.display = 'flex';
    inp.value = queryStr || '';
    runBtn.click();
  }

  function ensurePopoverElement() {
    let pop = document.getElementById('rtp-xref-popover');
    if (pop) return pop;
    pop = document.createElement('div');
    pop.id = 'rtp-xref-popover';
    document.body.appendChild(pop);
    document.addEventListener('click', (ev) => {
      if (pop.style.display === 'block' && !pop.contains(ev.target) && !ev.target.closest('.rtp-xref-chip')) {
        pop.style.display = 'none';
      }
    });
    return pop;
  }

  function showSymbolPopover(sym, anchorEl) {
    if (!sym || !cachedIndexes) return;
    const pop = ensurePopoverElement();
    const badge = TYPE_BADGE_COLORS[sym.type] || { bg: '#313244', fg: '#cdd6f4', label: sym.type || 'SYMBOL' };
    const outEdges = (cachedIndexes.edgesBySrc.get(sym.id) || []).slice(0, 5);
    const inEdges = (cachedIndexes.edgesByDst.get(sym.id) || []).slice(0, 4);
    const allEdges = outEdges.concat(inEdges).slice(0, 6);
    const conflicts = cachedIndexes.conflictsBySymbol.get(sym.id) || [];

    pop.innerHTML = '';
    const hdr = document.createElement('div');
    hdr.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:6px;';
    const badgeEl = document.createElement('span');
    badgeEl.style.cssText = 'background:' + badge.bg + ';color:' + badge.fg + ';padding:2px 7px;border-radius:4px;font-size:10.5px;font-weight:700;letter-spacing:0.04em;';
    badgeEl.textContent = badge.label;
    const titleEl = document.createElement('strong');
    titleEl.style.cssText = 'font-family:monospace;font-size:13px;color:#f5e0dc;flex:1;word-break:break-all;';
    titleEl.textContent = sym.name || sym.id;
    const closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.style.cssText = 'background:none;border:none;color:#a6adc8;cursor:pointer;font-size:14px;';
    closeBtn.textContent = '✕';
    closeBtn.addEventListener('click', () => { pop.style.display = 'none'; });
    hdr.appendChild(badgeEl);
    hdr.appendChild(titleEl);
    hdr.appendChild(closeBtn);
    pop.appendChild(hdr);

    if (sym.summary) {
      const sumEl = document.createElement('div');
      sumEl.style.cssText = 'color:#cdd6f4;margin-bottom:8px;font-size:12px;';
      sumEl.textContent = sym.summary;
      pop.appendChild(sumEl);
    }

    if (allEdges.length > 0) {
      const relHdr = document.createElement('div');
      relHdr.style.cssText = 'font-weight:700;color:#a6e3a1;font-size:11px;margin-bottom:4px;';
      relHdr.textContent = '🕸️ Relational AST Connections:';
      pop.appendChild(relHdr);
      const ul = document.createElement('div');
      ul.style.cssText = 'display:flex;flex-wrap:wrap;gap:4px;margin-bottom:8px;';
      allEdges.forEach((e) => {
        const isOut = e.src === sym.id;
        const otherName = isOut ? e.dst_name : e.src_name;
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.style.cssText = 'background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:4px;padding:2px 6px;font-size:11px;font-family:monospace;cursor:pointer;';
        chip.textContent = e.rel + ' → ' + otherName;
        chip.addEventListener('click', () => {
          pop.style.display = 'none';
          openDoctorWithQuery(otherName);
        });
        ul.appendChild(chip);
      });
      pop.appendChild(ul);
    }

    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin-top:6px;border-top:1px solid #313244;padding-top:8px;';
    if (sym.url) {
      const docLink = document.createElement('a');
      docLink.href = sym.url;
      docLink.target = '_blank';
      docLink.rel = 'noopener';
      docLink.style.cssText = 'color:#89b4fa;font-weight:600;text-decoration:none;font-size:11.5px;';
      docLink.textContent = '📖 Open Reference';
      actions.appendChild(docLink);
    }
    const botBtn = document.createElement('button');
    botBtn.type = 'button';
    botBtn.style.cssText = 'background:#89b4fa;color:#11111b;border:none;border-radius:4px;padding:2px 8px;font-size:11.5px;font-weight:600;cursor:pointer;margin-left:auto;';
    botBtn.textContent = '🤖 Inspect in Helper Bot';
    botBtn.addEventListener('click', () => {
      pop.style.display = 'none';
      openDoctorWithQuery(sym.name || sym.id);
    });
    actions.appendChild(botBtn);
    pop.appendChild(actions);

    const rect = anchorEl.getBoundingClientRect();
    pop.style.display = 'block';
    const topPos = Math.min(window.innerHeight - pop.offsetHeight - 12, Math.max(12, rect.bottom + 6));
    const leftPos = Math.min(window.innerWidth - pop.offsetWidth - 12, Math.max(12, rect.left));
    pop.style.top = topPos + 'px';
    pop.style.left = leftPos + 'px';
  }

  function enhanceArticleWithRelationalCrosslinks() {
    if (!cachedCorpus || !cachedIndexes) return;
    injectRelationalStyles();
    const article = document.querySelector('article.md-content__inner');
    if (!article) return;

    const matchedOnPage = new Map();
    const codeNodes = article.querySelectorAll('code');
    codeNodes.forEach((codeEl) => {
      if (codeEl.closest('pre') || codeEl.closest('a:not(.rtp-baked-xref)') || codeEl.dataset.rtpXrefBound === '1') return;
      const rawTxt = (codeEl.textContent || '').trim();
      if (rawTxt.length < 3 || rawTxt.length > 72) return;
      const cleanKey = rawTxt.replace(/^`|`$/g, '').split('=')[0].split(':')[0].trim().toLowerCase();
      if (AMBIGUOUS_CODE_TOKENS.has(cleanKey)) return;

      const symIdAttr = codeEl.dataset.rtpSymbolId;
      const sym = (symIdAttr && cachedIndexes.byId.get(symIdAttr)) ||
        cachedIndexes.byLookupToken.get(rawTxt.toLowerCase()) ||
        cachedIndexes.byLookupToken.get(cleanKey);
      if (!sym) return;

      codeEl.dataset.rtpXrefBound = '1';
      codeEl.dataset.rtpSymbolId = sym.id;
      codeEl.classList.add('rtp-xref-chip');
      const tipText = (sym.type ? '[' + sym.type + '] ' : '') + (sym.summary || sym.name) + ' — Click to inspect relational AST graph (Ctrl/Cmd+Click to open page)';
      codeEl.title = tipText;

      let parentAnchor = codeEl.closest('a.rtp-baked-xref');
      if (!parentAnchor && codeEl.parentNode) {
        const href = normalizeSiteUrl(sym.url || '');
        if (href) {
          parentAnchor = document.createElement('a');
          parentAnchor.className = 'rtp-baked-xref';
          parentAnchor.href = href;
          parentAnchor.dataset.rtpSymbolId = sym.id;
          parentAnchor.title = tipText;
          codeEl.parentNode.insertBefore(parentAnchor, codeEl);
          parentAnchor.appendChild(codeEl);
        }
      }

      const handleActivation = (ev) => {
        if (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.button === 1) return;
        ev.preventDefault();
        ev.stopPropagation();
        showSymbolPopover(sym, codeEl);
      };
      if (parentAnchor) {
        parentAnchor.addEventListener('click', handleActivation);
      } else {
        codeEl.addEventListener('click', handleActivation);
      }
      if (!matchedOnPage.has(sym.id)) {
        matchedOnPage.set(sym.id, sym);
      }
    });

    const proseLinks = article.querySelectorAll('a.rtp-prose-xref[data-rtp-symbol-id]');
    proseLinks.forEach((aEl) => {
      if (aEl.dataset.rtpXrefBound === '1') return;
      const sid = aEl.dataset.rtpSymbolId;
      const sym = sid ? cachedIndexes.byId.get(sid) : null;
      if (!sym) return;
      aEl.dataset.rtpXrefBound = '1';
      aEl.addEventListener('click', (ev) => {
        if (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.button === 1) return;
        ev.preventDefault();
        ev.stopPropagation();
        showSymbolPopover(sym, aEl);
      });
      if (!matchedOnPage.has(sym.id)) {
        matchedOnPage.set(sym.id, sym);
      }
    });

    // Also pull pre-indexed `page_crosslinks` for the current URL path
    const pageCrosslinks = cachedCorpus.page_crosslinks || {};
    const pathSlug = window.location.pathname
      .replace(/^\/mc\/leafrtp\//i, '')
      .replace(/^\/mc\//i, '')
      .replace(/^\/RTP\//i, '')
      .replace(/^\/+|\/+$/g, '')
      .replace(/\/index\.html$/i, '')
      .replace(/\.html$/i, '') || 'index';
    const preIndexed = pageCrosslinks[pathSlug] || pageCrosslinks[pathSlug.toUpperCase()];
    if (preIndexed && Array.isArray(preIndexed.symbols)) {
      for (const sid of preIndexed.symbols) {
        const sObj = cachedIndexes.byId.get(sid);
        if (sObj && !matchedOnPage.has(sObj.id)) {
          matchedOnPage.set(sObj.id, sObj);
        }
      }
    }

    // Mount or refresh the Per-Page Relational Cross-Reference & Governance Footer
    const existingFooter = article.querySelector('.rtp-page-xref-footer');
    if (existingFooter) existingFooter.remove();
    if (matchedOnPage.size === 0) return;

    const footer = document.createElement('section');
    footer.className = 'rtp-page-xref-footer';
    const titleRow = document.createElement('div');
    titleRow.style.cssText = 'display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:8px;';
    const heading = document.createElement('strong');
    heading.textContent = '🕸️ Relational Cross-References & Connected AST Symbols (' + matchedOnPage.size + ')';
    const askPageBtn = document.createElement('button');
    askPageBtn.type = 'button';
    askPageBtn.style.cssText = 'background:#2e7d32;color:#fff;border:none;border-radius:6px;padding:4px 10px;font-size:11.5px;font-weight:600;cursor:pointer;';
    askPageBtn.textContent = '🤖 Open Helper Bot & Search Engine';
    askPageBtn.addEventListener('click', () => {
      const firstSym = matchedOnPage.values().next().value;
      openDoctorWithQuery(firstSym ? firstSym.name : '');
    });
    titleRow.appendChild(heading);
    titleRow.appendChild(askPageBtn);
    footer.appendChild(titleRow);

    const pillsWrap = document.createElement('div');
    Array.from(matchedOnPage.values()).slice(0, 22).forEach((sym) => {
      const badge = TYPE_BADGE_COLORS[sym.type] || { bg: '#313244', fg: '#cdd6f4', label: 'SYM' };
      const pill = document.createElement('button');
      pill.type = 'button';
      pill.className = 'rtp-xref-pill rtp-xref-chip';
      pill.style.background = badge.bg;
      pill.style.color = badge.fg;
      pill.textContent = '[' + badge.label + '] ' + sym.name;
      pill.addEventListener('click', (ev) => {
        ev.preventDefault();
        ev.stopPropagation();
        showSymbolPopover(sym, pill);
      });
      pillsWrap.appendChild(pill);
    });
    footer.appendChild(pillsWrap);
    article.appendChild(footer);
  }

  function mountWidget() {
    if (document.getElementById('rtp-doctor-fab')) return;
    const btn = document.createElement('button');
    btn.id = 'rtp-doctor-fab';
    btn.type = 'button';
    btn.textContent = '🤖 Helper Bot & Log/Config Doctor';
    btn.title = 'Search the LeafRTP Relational AST Graph, BIT codes, 12-locale config keys, Java stack traces, and redact logs';
    btn.style.cssText = [
      'position:fixed', 'bottom:18px', 'right:18px', 'z-index:999',
      'background:#2e7d32', 'color:#fff', 'border:none', 'border-radius:999px',
      'padding:10px 16px', 'font-size:13px', 'font-weight:600', 'cursor:pointer',
      'box-shadow:0 4px 14px rgba(0,0,0,0.35)'
    ].join(';');

    const modal = document.createElement('div');
    modal.id = 'rtp-doctor-modal';
    modal.style.cssText = [
      'display:none', 'position:fixed', 'inset:0', 'z-index:1000',
      'background:rgba(0,0,0,0.6)', 'align-items:center', 'justify-content:center', 'padding:16px'
    ].join(';');

    modal.innerHTML = [
      '<div style="background:#1e1e2e;color:#cdd6f4;max-width:820px;width:100%;max-height:88vh;overflow:auto;border-radius:12px;padding:20px;border:1px solid #45475a;font-family:system-ui,sans-serif;">',
      '  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">',
      '    <strong style="font-size:16px;">🤖 LeafRTP Relational Search Engine, Helper Bot &amp; Log/Config Doctor</strong>',
      '    <button type="button" id="rtp-doctor-close" style="background:none;border:none;color:#cdd6f4;font-size:18px;cursor:pointer;">✕</button>',
      '  </div>',
      '  <p style="font-size:12px;color:#a6adc8;margin:0 0 8px;">Ask a natural-language question, search any config key (across 12 locales, e.g. <code>maxSlope</code>, <code>openOnBareRtp</code>, <code>dinero</code>), BIT code (<code>RTP-0012</code>, <code>CLAIM-0001</code>), Java class (<code>JumpAdjustor</code>, <code>TickBudget</code>), ADR/REQ ID, or paste a console log / YAML snippet. Secrets are automatically redacted.</p>',
      '  <div id="rtp-doctor-quick-prompts" style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:8px;">',
      '    <button type="button" data-q="Every time players type /rtp it opens a chest inventory GUI instead of teleporting" style="background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:999px;padding:3px 9px;font-size:11px;cursor:pointer;">💬 Why does /rtp open a GUI?</button>',
      '    <button type="button" data-q="Why does /rtp in world_nether send players to the overworld?" style="background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:999px;padding:3px 9px;font-size:11px;cursor:pointer;">💬 Nether /rtp sends to overworld?</button>',
      '    <button type="button" data-q="Server lag spikes during RTP — should I set syncLoading: true?" style="background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:999px;padding:3px 9px;font-size:11px;cursor:pointer;">💬 Lag spikes / syncLoading?</button>',
      '    <button type="button" data-q="CLAIM-0001 maxSlope JumpAdjustor" style="background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:999px;padding:3px 9px;font-size:11px;cursor:pointer;">🩺 Sample BIT + AST Triage</button>',
      '  </div>',
      '  <textarea id="rtp-doctor-input" rows="4" placeholder="Ask a question, search symbols/configs/ADRs, or paste console log / YAML config..." style="width:100%;box-sizing:border-box;background:#11111b;color:#cdd6f4;border:1px solid #45475a;border-radius:8px;padding:10px;font-family:monospace;font-size:12px;"></textarea>',
      '  <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">',
      '    <button type="button" id="rtp-doctor-run" style="background:#89b4fa;color:#11111b;border:none;border-radius:6px;padding:7px 14px;font-weight:600;cursor:pointer;">Search &amp; Ask Helper Bot</button>',
      '    <button type="button" id="rtp-doctor-copy" style="background:#313244;color:#cdd6f4;border:1px solid #585b70;border-radius:6px;padding:7px 14px;cursor:pointer;">Copy Secret-Redacted Summary for Discord #support</button>',
      '  </div>',
      '  <div id="rtp-doctor-output" style="margin-top:14px;font-size:13px;line-height:1.5;"></div>',
      '</div>'
    ].join('\n');

    document.body.appendChild(btn);
    document.body.appendChild(modal);

    btn.addEventListener('click', () => {
      modal.style.display = 'flex';
      loadCorpus();
    });
    modal.querySelector('#rtp-doctor-close').addEventListener('click', () => {
      modal.style.display = 'none';
    });
    modal.addEventListener('click', (ev) => {
      if (ev.target === modal) modal.style.display = 'none';
    });

    const inputEl = modal.querySelector('#rtp-doctor-input');
    const outEl = modal.querySelector('#rtp-doctor-output');

    outEl.addEventListener('click', (ev) => {
      const chip = ev.target.closest('.rtp-xref-chip[data-rtp-symbol-id], a.rtp-baked-xref[data-rtp-symbol-id]');
      if (!chip) return;
      if (ev.ctrlKey || ev.metaKey || ev.shiftKey || ev.button === 1) return;
      const sid = chip.getAttribute('data-rtp-symbol-id');
      const idx = cachedIndexes || (cachedCorpus ? buildRelationalIndexes(cachedCorpus) : null);
      const sym = (sid && idx && idx.byId.get(sid)) || null;
      if (sym) {
        ev.preventDefault();
        ev.stopPropagation();
        showSymbolPopover(sym, chip);
      }
    });

    modal.querySelectorAll('#rtp-doctor-quick-prompts button[data-q]').forEach((qBtn) => {
      qBtn.addEventListener('click', () => {
        inputEl.value = qBtn.getAttribute('data-q') || '';
        modal.querySelector('#rtp-doctor-run').click();
      });
    });

    let activeRunId = 0;

    modal.querySelector('#rtp-doctor-run').addEventListener('click', async () => {
      const myRunId = ++activeRunId;
      const rawQuery = inputEl.value || '';
      outEl.textContent = 'Scanning relational AST graph & multi-plane corpus...';
      const corpus = await loadCorpus();
      const res = triageLocal(rawQuery, corpus);
      if (myRunId !== activeRunId) return;
      outEl.innerHTML = '';

      if (res.secretRedactions > 0 || res.secret_redactions > 0) {
        const redCnt = res.secretRedactions || res.secret_redactions;
        const warn = document.createElement('div');
        warn.style.cssText = 'color:#f38ba8;margin-bottom:8px;font-weight:600;';
        warn.textContent = '🔒 Automatically redacted ' + redCnt + ' secret(s)/credential(s).';
        outEl.appendChild(warn);
      }

      const botCard = document.createElement('div');
      botCard.style.cssText = 'background:#181825;border:1.5px solid #89b4fa;border-radius:10px;padding:12px 14px;margin-bottom:12px;font-size:13px;line-height:1.55;';
      const botHeader = document.createElement('div');
      botHeader.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:6px;border-bottom:1px solid #313244;padding-bottom:6px;';
      const botTitle = document.createElement('strong');
      botTitle.style.cssText = 'color:#89b4fa;font-size:13px;';
      botTitle.textContent = '🤖 Operator Guidance';
      const botStatus = document.createElement('span');
      botStatus.style.cssText = 'color:#f9e2af;font-size:11.5px;font-weight:600;';
      botStatus.textContent = '⚡ Synthesizing tailored answer (Gemini TPU)...';
      botHeader.appendChild(botTitle);
      botHeader.appendChild(botStatus);
      const botBody = document.createElement('div');
      renderBotMarkdown(botBody, res.helperBotSummary || res.reply_markdown || 'Analyzing configuration schema and operator docs...');
      botCard.appendChild(botHeader);
      botCard.appendChild(botBody);
      outEl.appendChild(botCard);

      const addSectionTo = (parentEl, title, items, renderItem) => {
        if (!items || !items.length) return;
        const h = document.createElement('div');
        h.style.cssText = 'font-weight:700;color:#a6e3a1;margin-top:10px;';
        h.textContent = title;
        parentEl.appendChild(h);
        const ul = document.createElement('ul');
        ul.style.cssText = 'margin:4px 0 8px 18px;padding:0;';
        items.forEach((it) => {
          const li = document.createElement('li');
          renderItem(li, it);
          ul.appendChild(li);
        });
        parentEl.appendChild(ul);
      };

      addSectionTo(outEl, '⚠️ YAML Schema & Range Issues', res.schemaIssues || res.schema_issues, (li, iss) => {
        li.style.color = '#f9e2af';
        li.textContent = (iss.file_path ? iss.file_path + ' → ' : '') + (iss.key || iss.key_path || '') + ': ' + (iss.reason || iss.issue || iss.message || '');
      });

      addSectionTo(outEl, '🩺 BIT Diagnostic Findings', res.bitFindings || res.bit_findings, (li, b) => {
        li.textContent = (b.code || (b.namespace + '-' + b.id)) + ' (' + b.key + ', ' + b.severity + '): ' + b.remediation;
      });

      addSectionTo(outEl, '⚡ Recommended Configuration Changes', res.recommendedChanges || res.recommended_changes, (li, rc) => {
        const span = document.createElement('span');
        const cleanReason = cleanConfigDescription(rc.reason || '', 190);
        const fileHtml = renderSymbolCrosslinkHtml(rc.file_path || '', rc.file_path || '');
        const keyLabel = (rc.key_path || rc.leaf || '') + ': ' + (rc.recommended_value || '');
        const keyHtml = renderSymbolCrosslinkHtml(rc.key_path || rc.leaf || '', keyLabel);
        span.innerHTML = fileHtml + ' → <b>' + keyHtml + '</b> — ' + formatInlineMarkdown(cleanReason) + (rc.cli_command ? ' (<code>' + rc.cli_command + '</code>) ' : ' ');
        li.appendChild(span);
        const leaf = String(rc.leaf || rc.key_path || '').split('.').pop();
        if (window.RTPIntentCapture && typeof window.RTPIntentCapture.applyDeepLink === 'function') {
          const stageBtn = document.createElement('button');
          stageBtn.type = 'button';
          stageBtn.className = 'rtp-stage-change-btn';
          stageBtn.style.cssText = 'background:#a6e3a1;color:#11111b;border:none;border-radius:4px;padding:2px 8px;font-size:11.5px;font-weight:700;cursor:pointer;margin-left:4px;';
          stageBtn.textContent = '⚡ Stage Change Now (' + leaf + '=' + rc.recommended_value + ')';
          stageBtn.addEventListener('click', () => {
            window.RTPIntentCapture.applyDeepLink({
              file: rc.file_path || '',
              key: leaf || '',
              value: String(rc.recommended_value || ''),
              intent: cleanReason || ('Set ' + leaf + ' = ' + rc.recommended_value)
            });
            modal.style.display = 'none';
          });
          li.appendChild(stageBtn);
        }
      });

      addSectionTo(outEl, '📖 Documentation Sections', (res.docHits || res.doc_hits || []).slice(0, 3), (li, d) => {
        const a = document.createElement('a');
        a.href = normalizeSiteUrl(d.url);
        a.target = '_blank';
        a.rel = 'noopener';
        a.style.cssText = 'color:#89b4fa;text-decoration:underline;';
        const cleanHeading = String(d.heading || d.title || '')
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/`([^`]+)`/g, '<code style="background:#313244;color:#a6e3a1;padding:1px 4px;border-radius:4px;font-size:11.5px;">$1</code>');
        a.innerHTML = cleanHeading;
        li.appendChild(a);
        const cleanPath = String(d.path || '')
          .replace(/^(?:resource|plugins)\/[^/]+\/docs\//i, '')
          .replace(/^docs\//i, '');
        if (cleanPath) {
          const pathSpan = document.createElement('span');
          pathSpan.style.cssText = 'color:#a6adc8;font-size:11.5px;margin-left:5px;';
          pathSpan.textContent = '(' + cleanPath + ')';
          li.appendChild(pathSpan);
        }
      });

      const cfgItems = res.configHits || res.config_hits || [];
      const astItems = res.astNeighbors || res.ast_subgraph_edges || [];
      const claimItems = res.causalClaims || res.typed_causal_claims || [];
      const codeItems = res.codeHits || res.code_hits || [];
      const reqItems = res.reqHits || [];
      const totalTechCount = cfgItems.length + astItems.length + claimItems.length + codeItems.length + reqItems.length;

      if (totalTechCount > 0) {
        const techDetails = document.createElement('details');
        techDetails.style.cssText = 'margin-top:12px;background:#11111b;border:1px solid #313244;border-radius:8px;padding:8px 12px;font-size:12px;color:#a6adc8;';
        const techSummary = document.createElement('summary');
        techSummary.style.cssText = 'cursor:pointer;font-weight:600;color:#89b4fa;user-select:none;';
        techSummary.textContent = '🔍 Show matching config keys & technical AST diagnostics (' + cfgItems.length + ' keys, ' + codeItems.length + ' classes, ' + astItems.length + ' graph edges)';
        techDetails.appendChild(techSummary);

        addSectionTo(techDetails, '🔧 Matching Configuration Keys', cfgItems, (li, c) => {
          const span = document.createElement('span');
          const cleanDesc = cleanConfigDescription(c.description || '', 220);
          span.textContent = c.file_path + ' → ' + c.key_path + ' (default: ' + c.default_value + (c.directives ? ', ' + c.directives : '') + ') — ' + cleanDesc + ' ';
          li.appendChild(span);
          const leaf = String(c.key_path || '').split('.').pop();
          if (window.RTPIntentCapture && typeof window.RTPIntentCapture.applyDeepLink === 'function') {
            const spotBtn = document.createElement('button');
            spotBtn.type = 'button';
            spotBtn.style.cssText = 'background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:4px;padding:1px 7px;font-size:11.5px;font-weight:600;cursor:pointer;margin-left:4px;';
            spotBtn.textContent = '🧭 Spotlight Key';
            spotBtn.addEventListener('click', () => {
              window.RTPIntentCapture.applyDeepLink({ file: c.file_path || '', key: leaf || '', intent: cleanDesc || c.key_path });
              modal.style.display = 'none';
            });
            li.appendChild(spotBtn);
          }
        });

        addSectionTo(techDetails, '🕸️ Relational AST Graph Connections (Gov(c) & Crosslinks)', astItems, (li, e) => {
          const span = document.createElement('span');
          span.textContent = (e.src_name || e.src) + ' —[' + e.rel + ']→ ';
          li.appendChild(span);
          const btnPivot = document.createElement('button');
          btnPivot.type = 'button';
          btnPivot.style.cssText = 'background:#313244;color:#89b4fa;border:1px solid #45475a;border-radius:4px;padding:1px 6px;font-family:monospace;font-size:11.5px;cursor:pointer;';
          btnPivot.textContent = e.dst_name || e.dst;
          btnPivot.addEventListener('click', () => {
            inputEl.value = e.dst_name || e.dst;
            modal.querySelector('#rtp-doctor-run').click();
          });
          li.appendChild(btnPivot);
        });

        addSectionTo(techDetails, '📌 Grounded Causal & Governance Claims', claimItems, (li, cl) => {
          const desc = cl.claim || ((cl.src_name || cl.src) + ' —[' + cl.predicate + ']→ ' + (cl.dst_name || cl.dst));
          li.textContent = desc + (cl.quote ? ' — "' + cl.quote + '"' : '');
        });

        addSectionTo(techDetails, '🧩 Matching Engine Classes & Method Shorthand (AST)', codeItems, (li, c) => {
          let txt = c.class_name + ' [' + (c.subsystem || c.dag_tier || '') + ']: ' + c.summary;
          if (c.jminor_method) {
            txt += ' | Scope: ' + c.jminor_method;
          } else if (Array.isArray(c.top_methods) && c.top_methods.length > 0) {
            const m0 = c.top_methods[0];
            const muts = (m0.effect_frame && Array.isArray(m0.effect_frame.mutates) && m0.effect_frame.mutates.length) ? (' mutates=' + m0.effect_frame.mutates.join(',')) : '';
            txt += ' | Top Scope: ' + c.class_name + '#' + m0.method + ' (' + m0.signature + muts + ')';
          }
          if (c.effect_frame_summary) {
            txt += ' [' + c.effect_frame_summary + ']';
          }
          li.textContent = txt;
        });

        addSectionTo(techDetails, '📐 Requirement Traceability (REQ-* → Class → Test)', reqItems, (li, tr) => {
          li.textContent = tr.req_id + ': ' + tr.summary + ' (Classes: ' + (tr.implementing_classes || []).join(', ') + ' | Tests: ' + (tr.test_classes || []).join(', ') + ')';
        });

        outEl.appendChild(techDetails);
      }

      // Progressive enhancement: ask the live Gemini TPU / fallback triage backend for an operator-focused synthesis
      try {
        const apiBase = window.RTP_BOT_API_URL || resolveTriageApiBase();
        const endpoint = (apiBase ? String(apiBase).replace(/\/+$/, '') : '') + '/api/v1/triage';
        const resp = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ query: rawQuery, use_llm: true })
        });
        if (myRunId !== activeRunId) return;
        if (resp.ok) {
          const llmRes = await resp.json();
          if (myRunId !== activeRunId) return;
          if (llmRes && llmRes.reply_markdown) {
            renderBotMarkdown(botBody, llmRes.reply_markdown);
            if (llmRes.llm_used) {
              const mName = llmRes.llm_model || 'gemini-3.5-flash-lite';
              const isGemini = String(mName).toLowerCase().includes('gemini');
              botStatus.style.color = '#a6e3a1';
              botStatus.textContent = '✓ Verified ' + (isGemini ? 'Gemini TPU' : 'LLM') + ' (' + mName + ')';
            } else {
              botStatus.style.color = '#a6adc8';
              botStatus.textContent = '✓ Schema-verified answer';
            }
          } else {
            botStatus.textContent = '';
          }
        } else {
          botStatus.textContent = '';
        }
      } catch (_err) {
        if (myRunId === activeRunId) {
          botStatus.textContent = '';
        }
      }
    });

    modal.querySelector('#rtp-doctor-copy').addEventListener('click', async () => {
      const res = await triage(inputEl.value);
      const bits = (res.bitFindings || res.bit_findings || []).map((b) => (b.code || (b.namespace + '-' + b.id)) + ' (' + b.key + ')');
      const issues = (res.schemaIssues || res.schema_issues || []).map((i) => (i.key || i.key_path) + ': ' + (i.reason || i.issue || i.message));
      const summaryLines = ['**LeafRTP Diagnostic Summary (Secrets Redacted)**'];
      if (bits.length) summaryLines.push('- **BIT Codes:** ' + bits.join(', '));
      if (issues.length) summaryLines.push('- **Schema Issues:** ' + issues.join('; '));
      summaryLines.push('```', (res.redactedText || res.redacted_input || '').slice(0, 1200), '```');
      const summary = summaryLines.join('\n');
      window.__lastCopiedDoctorSummary = summary;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        try { await navigator.clipboard.writeText(summary); } catch (_e) {}
      }
    });

    // Load relational corpus in the background and crosslink the current documentation page
    loadCorpus().then(() => {
      enhanceArticleWithRelationalCrosslinks();
    });
  }

  window.RTPSupportDoctor = {
    loadCorpus,
    redactSecrets,
    triageLocal,
    triage,
    mountWidget,
    enhanceArticleWithRelationalCrosslinks,
    openDoctorWithQuery
  };

  if (typeof document !== 'undefined') {
    const initAll = () => {
      mountWidget();
      if (cachedCorpus) enhanceArticleWithRelationalCrosslinks();
    };
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', initAll);
    } else {
      initAll();
    }
    // Re-apply inline crosslinks when MkDocs Material instant navigation swaps page content
    if (typeof window.document$ !== 'undefined' && typeof window.document$.subscribe === 'function') {
      window.document$.subscribe(() => {
        if (cachedCorpus) enhanceArticleWithRelationalCrosslinks();
      });
    }
  }
})();
