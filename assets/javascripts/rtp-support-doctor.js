/**
 * LeafRTP Multi-Plane Search, AST Stack-Trace Matcher & Log/Config Doctor.
 *
 * Runs the Discord Support Bot's deterministic 5-plane triage engine directly
 * in the browser against `support-corpus.json` (Layer 1, 100% static on GitHub
 * Pages), with optional progressive enhancement against the live bot HTTP bridge
 * (`/api/v1/triage` and `/api/v1/ask`, Layer 2).
 */
(function () {
  'use strict';

  const SECRET_PATTERNS = [
    /\b(password|passwd|pass|secret|api[_-]?key|token|auth[_-]?token|private[_-]?key|access[_-]?key|client[_-]?secret|webhook[_-]?url|Bot)\b(\s*[:=]\s*)(["']?)([^\s"'#,\r\n]{4,})\3/gi,
    /\b((?:jdbc:(?:mysql|mariadb|postgresql|sqlserver):\/\/|redis(?:s)?:\/\/|mongodb(?:\+srv)?:\/\/)[^:\s\/]+:)([^@\s]+)(@)/gi
  ];

  const BIT_CODE_RE = /\b(ACTADDON|ACTION|BIT|CLAIM|GOVERNOR|HEATMAP|PROBE|RTP|TEST)[-_](\d{1,4})\b/gi;
  const BIT_SYMBOL_RE = /\b((?:RTP|ACTADDON|CLAIM|PROBE|BIT|HEATMAP|ACTION|GOVERNOR)_[A-Z0-9_]{3,})\b/g;
  const PRIMARY_DOMAIN_KEYS = new Set([
    'step', 'direction', 'miny', 'maxy', 'requireskylight',
    'maxslope', 'sloperadius', 'maxhillangle',
    'strictactions', 'strictconfig', 'overrides',
    'syncallottedtime', 'asyncallottedtime', 'autotune', 'period',
    'triggers', 'gates', 'command', 'alias', 'rejectinclaim'
  ]);

  let corpusPromise = null;
  let cachedCorpus = null;

  function resolveCorpusUrl() {
    const scripts = document.querySelectorAll('script[src*="rtp-support-doctor.js"]');
    if (scripts.length > 0) {
      const src = scripts[0].src || scripts[0].getAttribute('src') || '';
      const base = src.replace(/javascripts\/rtp-support-doctor\.js.*$/, '');
      return base + 'support-corpus.json';
    }
    return '/RTP/assets/support-corpus.json';
  }

  function loadCorpus() {
    if (cachedCorpus) return Promise.resolve(cachedCorpus);
    if (corpusPromise) return corpusPromise;
    corpusPromise = fetch(resolveCorpusUrl(), { headers: { Accept: 'application/json' } })
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (data && typeof data === 'object') cachedCorpus = data;
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
        docHits: []
      };
    }

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

    const tokens = (textLow.match(/[a-z0-9_.-]{3,64}/g) || []).slice(0, 48);
    const expanded = new Set(tokens);
    const aliases = corpus.locale_aliases || {};
    const syns = corpus.synonyms || {};
    for (const tok of tokens) {
      if (aliases[tok] && aliases[tok].key_path) {
        expanded.add(aliases[tok].key_path.toLowerCase());
      }
      if (Array.isArray(syns[tok])) {
        syns[tok].forEach((s) => expanded.add(String(s).toLowerCase()));
      }
    }

    // Bridge config_refs from matched Java AST classes with query-overlap & domain priority
    const astRefBoost = new Map();
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
      const descLow = (c.description || '').toLowerCase();
      let score = 0;
      if (astRefBoost.has(leaf) || astRefBoost.has(kpLow)) {
        score += Math.max(astRefBoost.get(leaf) || 0, astRefBoost.get(kpLow) || 0);
        if ((c.file_path || '') === 'definitions/regions/default.yml' || (c.file_path || '').startsWith('addons/') || (c.file_path || '').startsWith('advanced/')) {
          score += 15;
        }
      }
      for (const tok of expanded) {
        if (tok === kpLow || tok === leaf) score += 100;
        else if (kpLow.includes(tok) && tok.length >= 4) score += 45;
        else if (descLow.includes(tok) && tok.length >= 4) score += 12;
      }
      if (score > 0) scoredCfg.push({ score, item: c });
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

    // Client-side YAML @range / @typedirective validation
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
      const idx = rawLine.indexOf(':');
      const kName = rawLine.slice(0, idx).trim().replace(/^['"]|['"]$/g, '').toLowerCase();
      const vStr = rawLine.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '');
      if (!vStr || vStr.startsWith('{') || vStr.startsWith('[')) continue;
      const spec = byLeaf.get(kName);
      if (!spec) continue;
      const dir = spec.directives || '';
      const rangeM = /@range:\s*`?(?:\[\s*([^,\]]+?)\s*,\s*([^\]]+?)\s*\]|(-?\d+(?:\.\d+)?)\s*[-–—]\s*(-?\d+(?:\.\d+)?))`?/i.exec(dir);
      if (rangeM) {
        const num = Number(vStr);
        const loStr = (rangeM[1] || rangeM[3] || '').trim();
        const hiStr = (rangeM[2] || rangeM[4] || '').trim();
        const lo = (!loStr || loStr.toLowerCase() === 'null') ? NaN : Number(loStr);
        const hi = (!hiStr || hiStr.toLowerCase() === 'null') ? NaN : Number(hiStr);
        if (!Number.isNaN(num)) {
          if (!Number.isNaN(lo) && num < lo) {
            schemaIssues.push({ line: String(i + 1), key_path: spec.key_path, file_path: spec.file_path, value: vStr, reason: 'Value ' + vStr + ' is below minimum ' + lo + ' (' + dir + ')' });
          } else if (!Number.isNaN(hi) && num > hi) {
            schemaIssues.push({ line: String(i + 1), key_path: spec.key_path, file_path: spec.file_path, value: vStr, reason: 'Value ' + vStr + ' exceeds maximum ' + hi + ' (' + dir + ')' });
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
        if (tok.length < 3) continue;
        if (hLow.includes(tok)) score += 35;
        if (sLow.includes(tok)) score += 10;
      }
      if (score > 0) scoredDocs.push({ score, item: d });
    }
    scoredDocs.sort((a, b) => b.score - a.score);
    const docHits = scoredDocs.slice(0, 5).map((x) => x.item);

    return {
      ok: true,
      secretRedactions: red.count,
      redactedText: text,
      bitFindings,
      configHits,
      codeHits,
      docHits,
      schemaIssues
    };
  }

  async function triage(rawInput, options) {
    const opts = options || {};
    const apiUrl = opts.apiUrl || window.RTP_BOT_API_URL;
    if (apiUrl) {
      try {
        const res = await fetch(apiUrl.replace(/\/+$/, '') + '/api/v1/triage', {
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

  function mountWidget() {
    if (document.getElementById('rtp-doctor-fab')) return;
    const btn = document.createElement('button');
    btn.id = 'rtp-doctor-fab';
    btn.type = 'button';
    btn.textContent = '🩺 Log & Config Doctor';
    btn.title = 'Search BIT codes, 12-locale config keys, Java stack traces, and redact logs before posting to Discord #support';
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
      '<div style="background:#1e1e2e;color:#cdd6f4;max-width:760px;width:100%;max-height:85vh;overflow:auto;border-radius:12px;padding:20px;border:1px solid #45475a;font-family:system-ui,sans-serif;">',
      '  <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;">',
      '    <strong style="font-size:16px;">🩺 LeafRTP Multi-Plane Search &amp; Log/Config Doctor</strong>',
      '    <button type="button" id="rtp-doctor-close" style="background:none;border:none;color:#cdd6f4;font-size:18px;cursor:pointer;">✕</button>',
      '  </div>',
      '  <p style="font-size:12px;color:#a6adc8;margin:0 0 10px;">Paste a BIT code (e.g. <code>RTP-0012</code>, <code>CLAIM-0001</code>), config key (any of 12 locales, e.g. <code>maxSlope</code>, <code>dinero</code>), console stack trace, or YAML snippet. Passwords and tokens are automatically redacted.</p>',
      '  <textarea id="rtp-doctor-input" rows="5" placeholder="Paste console log, /rtp test output, YAML config, or question..." style="width:100%;box-sizing:border-box;background:#11111b;color:#cdd6f4;border:1px solid #45475a;border-radius:8px;padding:10px;font-family:monospace;font-size:12px;"></textarea>',
      '  <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">',
      '    <button type="button" id="rtp-doctor-run" style="background:#89b4fa;color:#11111b;border:none;border-radius:6px;padding:7px 14px;font-weight:600;cursor:pointer;">Analyze &amp; Search</button>',
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

    modal.querySelector('#rtp-doctor-run').addEventListener('click', async () => {
      outEl.textContent = 'Scanning multi-plane corpus...';
      const res = await triage(inputEl.value);
      outEl.innerHTML = '';
      if (res.secretRedactions > 0) {
        const warn = document.createElement('div');
        warn.style.cssText = 'color:#f38ba8;margin-bottom:8px;font-weight:600;';
        warn.textContent = '🔒 Automatically redacted ' + res.secretRedactions + ' secret(s)/credential(s).';
        outEl.appendChild(warn);
      }
      const addSection = (title, items, renderItem) => {
        if (!items || !items.length) return;
        const h = document.createElement('div');
        h.style.cssText = 'font-weight:700;color:#a6e3a1;margin-top:10px;';
        h.textContent = title;
        outEl.appendChild(h);
        const ul = document.createElement('ul');
        ul.style.cssText = 'margin:4px 0 8px 18px;padding:0;';
        items.forEach((it) => {
          const li = document.createElement('li');
          renderItem(li, it);
          ul.appendChild(li);
        });
        outEl.appendChild(ul);
      };
      addSection('⚠️ YAML Schema & Range Issues', res.schemaIssues || res.schema_issues, (li, iss) => {
        li.style.color = '#f9e2af';
        li.textContent = (iss.file_path ? iss.file_path + ' → ' : '') + (iss.key || iss.key_path || '') + ': ' + (iss.reason || iss.issue || iss.message || '');
      });
      addSection('🩺 BIT Diagnostic Findings', res.bitFindings || res.bit_findings, (li, b) => {
        li.textContent = (b.code || (b.namespace + '-' + b.id)) + ' (' + b.key + ', ' + b.severity + '): ' + b.remediation;
      });
      addSection('🔧 Matching Configuration Keys', res.configHits || res.config_hits, (li, c) => {
        const span = document.createElement('span');
        span.textContent = c.file_path + ' → ' + c.key_path + ' (default: ' + c.default_value + (c.directives ? ', ' + c.directives : '') + ') — ' + c.description + ' ';
        li.appendChild(span);
        const leaf = String(c.key_path || '').split('.').pop();
        const link = document.createElement('a');
        link.href = '/RTP/editor/?file=' + encodeURIComponent(c.file_path || '') + '&key=' + encodeURIComponent(leaf || '') + '&search=' + encodeURIComponent(leaf || '');
        link.target = '_blank';
        link.rel = 'noopener';
        link.style.cssText = 'color:#89b4fa;font-weight:600;text-decoration:none;margin-left:4px;';
        link.textContent = '[🧭 Spotlight in Web Editor]';
        li.appendChild(link);
      });
      addSection('🧩 Matching Engine Classes (AST)', res.codeHits || res.code_hits, (li, c) => {
        li.textContent = c.class_name + ' [' + c.subsystem + ']: ' + c.summary;
      });
      addSection('📖 Documentation Sections', res.docHits || res.doc_hits, (li, d) => {
        const a = document.createElement('a');
        a.href = d.url;
        a.target = '_blank';
        a.rel = 'noopener';
        a.style.color = '#89b4fa';
        a.textContent = d.heading + ' (' + d.path + ')';
        li.appendChild(a);
      });
      if (!outEl.childNodes.length) {
        outEl.textContent = 'No matching BIT codes, config keys, or doc sections found.';
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
  }

  window.RTPSupportDoctor = {
    loadCorpus,
    redactSecrets,
    triageLocal,
    triage,
    mountWidget
  };

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', mountWidget);
    } else {
      mountWidget();
    }
  }
})();
