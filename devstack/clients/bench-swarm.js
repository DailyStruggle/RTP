/**
 * Idle offline-mode roster for the StressTestRTP ramp (/rtpstress ramp).
 *
 * Joins N lightweight Mineflayer bots in one Node process and keeps them
 * online, idle and alive so the harness can dispatch teleports against them.
 * Bots never chat, move, dig or interact; the server drives every teleport.
 *
 * Why: the harness allows one in-flight attempt per player, so offered rate
 * <= roster / latency (Little's law). 4 accounts at ~300 ms cap a plugin at
 * ~13 TP/s; 64 bots lift that to ~200 TP/s.
 *
 * Server prerequisites (see helpers/StressTestRTP/README.md, "Ramp mode"):
 *   - server.properties online-mode=false (offline UUIDs).
 *   - bukkit.yml settings.connection-throttle: every bot shares one IP, so
 *     either set it to -1 (then --join-delay-ms 400 brings 192 bots online in ~77 s) or keep
 *     --join-delay-ms above it (default 4500 ms, ~14 min for 192 bots).
 *
 * Usage:
 *   node devstack/clients/bench-swarm.js --host 10.0.0.5 --count 64
 * Options (env var in brackets):
 *   --host <h>               [BENCH_HOST]          default 127.0.0.1
 *   --port <p>               [BENCH_PORT]          default 25565
 *   --count <n>              [BENCH_COUNT]         default 64
 *   --prefix <s>             [BENCH_PREFIX]        default bench_  (names bench_000..)
 *   --start-index <i>        [BENCH_START_INDEX]   default 0
 *   --version <v>            [BENCH_VERSION]       default auto-detect
 *   --join-delay-ms <ms>     [BENCH_JOIN_DELAY_MS] default 4500  (global spacing of ALL connects)
 *   --view-distance <d>      [BENCH_VIEW_DISTANCE] default 2     (number, or tiny|short|normal|far)
 *   --physics                [BENCH_PHYSICS=1]     default off
 *   --full-plugins           [BENCH_FULL_PLUGINS=1] default off (lean plugin set)
 *   --reconnect-base-ms <ms> default 5000, doubled per consecutive failure
 *   --reconnect-max-ms <ms>  default 60000
 *   --stats-interval-s <s>   default 30 (0 disables)
 *   --stats-file <path>      [BENCH_STATS_FILE]    append one JSON line per worker per interval:
 *                            {t,w,rx,tx,online}; rx/tx are cumulative wire bytes (socket.bytesRead /
 *                            bytesWritten, after compression) incl. closed sockets, so reconnects
 *                            never reset them. The file is truncated at start. Off by default.
 *   --stats-file-interval-s <s>  default 1
 *   --swap-stuck             [BENCH_SWAP_STUCK=1]  replace a bot the server stopped teleporting: when the
 *                            other bots of this worker are being teleported but this one has seen no
 *                            teleport for max(--swap-idle-s, 10 x its expected gap), it quits and
 *                            rejoins under a new name (new offline UUID), clearing per-player plugin
 *                            state such as a stuck "already teleporting" lock. Adds `swaps` to the
 *                            stats line and --stats-file. Off by default.
 *   --swap-idle-s <s>        default 120
 *   --swap-min-activity <n>  default 5 (teleports seen by this worker in the last 60 s before any swap)
 *   --log-chat               print every chat/system line received (noisy)
 *   --workers <n>            [BENCH_WORKERS]       default ceil(count/16), max cpus-1 (1 = in-process)
 *   --keep-world             [BENCH_KEEP_WORLD=1]  decode chunks into a client-side world (off)
 *   --churn-rename           [BENCH_CHURN_RENAME=1] after a `/rtpstress churn` kick, rejoin as
 *                            <name>g<generation> (new offline UUID) instead of the same name
 *   --fuzz-control <port>    [BENCH_FUZZ_CONTROL]  fuzz mode (below); 0 picks a free port
 *   --fuzz-spawn-timeout-ms <ms>  default 30000 (connect-to-spawn limit per join)
 *   --fuzz-episode-max-ms <ms>    default 180000 (episode deadline unless max_ms is sent)
 *   --fuzz-max-bots <n>           default 64
 *
 * Fuzz mode (--fuzz-control): no roster, no workers, no reconnects. Listens on
 * 127.0.0.1:<port> for newline-delimited JSON, one episode at a time:
 *   {"op":"episode","id":7,"cmd":"rtp","max_ms":90000,
 *    "bots":[{"name":"fz7_0","program":["rtp",{"a":"wait","ms":200},"quit"]}]}
 *   {"op":"stop"}   abort any episode, reply {"ev":"stopped"}, exit
 *   {"op":"ping"}   reply {"ev":"pong"}
 * cmd is sent as /<cmd>[ <args>] with {self} replaced by the bot's current name.
 * Actions (string or {"a":...}): rtp{args}, rtp2{args} (second /rtp, same send),
 * wait{ms<=120000}, move{dx,dz<=64,ms<=60000} (position packets, physics off),
 * quit, rejoin{rename} (rename -> churnName, a new offline UUID), die (/kill).
 * Each bot joins, runs its program, then quits. A kick skips the rest of its
 * program; actions needing a connection while offline are skipped.
 * Replies: {"ev":"accepted"|"error",id}; then events {ev,id,bot,t,...} with ev in
 * act, skip, spawn, chat{text,kind}, kick{text}, tp{pos}, death, bot_error, end
 * (t = ms since accept, id = episode id); finally
 * {"ev":"done",id,ms,reason,bots:[...]} once every bot has disconnected.
 * done always arrives: joins time out, quits are forced after 5 s, and the
 * episode deadline aborts the programs. tp is every server position packet:
 * it also fires on the login position and after each respawn (just before
 * spawn), not only on plugin teleports. kick/end text is plain text decoded
 * from JSON or NBT components (translate keys as key(args)).
 * Lifetime: the swarm stops when stdin reaches EOF, so spawn it with a pipe
 * on stdin (stdin=PIPE) and keep it open; /dev/null stops it at once.
 * Connects are spaced by --join-delay-ms from the last actual connect, so an
 * aborted episode leaves no reserved slots behind.
 *
 * Trade-offs:
 *   - view distance: the server sends min(client, server) chunks, so 2 keeps
 *     per-bot memory and bandwidth small while chunk sending stays real.
 *     Server-side loading follows the server's own view/simulation distance,
 *     not this value. Raise it to model real clients' send cost.
 *   - physics off: no per-tick collision simulation (the dominant per-bot
 *     CPU). Position and teleport-confirm packets are still sent, so the
 *     server sees a normal idle player. Bots do not fall after landing; a
 *     landing in mid-air can draw a fly kick (allow-flight=true avoids it;
 *     the bot reconnects either way). Use --physics to model gravity.
 *   - lean plugins: inventory-UI, digging, placing, crafting, sound and
 *     particle handlers are not loaded; chat, entities, health/respawn,
 *     physics, resource packs and settings stay.
 *   - world decode off: every teleport makes the server send a fresh chunk
 *     square; decoding those into prismarine-chunk columns costs ~1 ms+ per
 *     chunk on one Node thread. At ramp rates that saturates the event loop,
 *     keepalives queue behind chunk data, and both sides time out
 *     ("client timed out after 60000 ms", server "keepalive timeout").
 *     Chunks are still received and every chunk batch is still acknowledged,
 *     so server-side send cost and pacing stay real; only the client-side
 *     decode is skipped. Idle bots never read blocks.
 *   - workers: bots are split across child processes so packet decoding
 *     uses several cores; join spacing stays global (worker k starts at
 *     k * join-delay and spaces its own joins by workers * join-delay).
 */
const mineflayer = require('mineflayer');
const minecraftData = require('minecraft-data');
const minecraftProtocol = require('minecraft-protocol');
const childProcess = require('child_process');
const os = require('os');
const fs = require('fs');

const args = process.argv.slice(2);
function getArg(flag, envName, defaultValue) {
  const idx = args.indexOf(flag);
  if (idx !== -1 && idx + 1 < args.length && !args[idx + 1].startsWith('--')) return args[idx + 1];
  if (envName && process.env[envName] !== undefined && process.env[envName] !== '') return process.env[envName];
  return defaultValue;
}
function hasFlag(flag, envName) {
  if (args.includes(flag)) return true;
  return envName ? /^(1|true|yes)$/i.test(process.env[envName] || '') : false;
}
function intArg(flag, envName, def) {
  const v = parseInt(getArg(flag, envName, String(def)), 10);
  return Number.isFinite(v) ? v : def;
}

const host = getArg('--host', 'BENCH_HOST', '127.0.0.1');
const port = intArg('--port', 'BENCH_PORT', 25565);
const count = Math.max(1, intArg('--count', 'BENCH_COUNT', 64));
const prefix = getArg('--prefix', 'BENCH_PREFIX', 'bench_');
const startIndex = Math.max(0, intArg('--start-index', 'BENCH_START_INDEX', 0));
const requestedVersion = getArg('--version', 'BENCH_VERSION', '') || false;
let activeVersion = requestedVersion;
let resolvedAutoVersion = null;
const joinDelayMs = Math.max(0, intArg('--join-delay-ms', 'BENCH_JOIN_DELAY_MS', 4500));
const viewDistanceRaw = getArg('--view-distance', 'BENCH_VIEW_DISTANCE', '2');
const viewDistance = /^\d+$/.test(viewDistanceRaw) ? Math.max(2, parseInt(viewDistanceRaw, 10)) : viewDistanceRaw;
const physicsEnabled = hasFlag('--physics', 'BENCH_PHYSICS');
const fullPlugins = hasFlag('--full-plugins', 'BENCH_FULL_PLUGINS');
const reconnectBaseMs = Math.max(500, intArg('--reconnect-base-ms', null, 5000));
const reconnectMaxMs = Math.max(reconnectBaseMs, intArg('--reconnect-max-ms', null, 60000));
const statsIntervalS = Math.max(0, intArg('--stats-interval-s', null, 30));
const statsFile = getArg('--stats-file', 'BENCH_STATS_FILE', '');
const statsFileIntervalS = Math.max(0.1, parseFloat(getArg('--stats-file-interval-s', null, '1')) || 1);
const swapStuck = hasFlag('--swap-stuck', 'BENCH_SWAP_STUCK');
const swapIdleS = Math.max(1, parseFloat(getArg('--swap-idle-s', null, '120')) || 120);
const swapMinActivity = Math.max(1, intArg('--swap-min-activity', null, 5));
const logChat = hasFlag('--log-chat', null);
const keepWorld = hasFlag('--keep-world', 'BENCH_KEEP_WORLD');
const churnRename = hasFlag('--churn-rename', 'BENCH_CHURN_RENAME');
const fuzzControlRaw = getArg('--fuzz-control', 'BENCH_FUZZ_CONTROL', '');
const fuzzMode = fuzzControlRaw !== '';
const fuzzSpawnTimeoutMs = Math.max(1000, intArg('--fuzz-spawn-timeout-ms', null, 30000));
const fuzzEpisodeMaxMs = Math.max(1000, intArg('--fuzz-episode-max-ms', null, 180000));
const fuzzMaxBots = Math.max(1, intArg('--fuzz-max-bots', null, 64));
// Kick reason sent by StressTestRTP LeakProbe#churn.
const CHURN_MARKER = 'StressTestRTP churn';
const isWorker = process.env.BENCH_WORKER === '1';
const workerLabel = isWorker ? `[w${process.env.BENCH_WORKER_ID}] ` : '';
const initialDelayMs = Math.max(0, parseInt(process.env.BENCH_INITIAL_DELAY_MS || '0', 10) || 0);
const maxWorkers = Math.max(1, (os.cpus() || []).length - 1);
const workers = (isWorker || fuzzMode) ? 1
  : Math.max(1, Math.min(count, maxWorkers, intArg('--workers', 'BENCH_WORKERS', Math.ceil(count / 16))));

// Client-side world packets dropped unless --keep-world. chunk_batch_* stay
// (the batch ack drives server send pacing); unload_chunk stays (no-op).
const WORLD_PACKETS = ['map_chunk', 'map_chunk_bulk', 'update_light', 'multi_block_change',
  'block_change', 'tile_entity_data', 'update_sign'];

const width = Math.max(3, parseInt(process.env.BENCH_NAME_WIDTH || '0', 10) || 0,
  String(startIndex + count - 1).length);
if ((prefix + '0'.repeat(width)).length > 16) {
  console.error(`[bench] prefix "${prefix}" + ${width} digits exceeds the 16-char username limit`);
  process.exit(2);
}

// Peripheral internal plugins dropped in lean mode. Kept: blocks,
// block_actions, breath, chat, entities, experience, game, health, inventory,
// simple_inventory, kick, physics, resource_pack, scoreboard, team, settings,
// spawn_point, tablist.
const LEAN_DISABLED = [
  'anvil', 'bed', 'book', 'boss_bar', 'chest', 'command_block', 'craft', 'creative',
  'digging', 'enchantment_table', 'explosion', 'fishing', 'furnace', 'generic_place',
  'particle', 'place_block', 'place_entity', 'rain', 'ray_trace', 'sound', 'time',
  'title', 'villager'
];
const pluginOverrides = {};
if (!fullPlugins) for (const p of LEAN_DISABLED) pluginOverrides[p] = false;

async function resolveVersion() {
  if (requestedVersion) return requestedVersion;
  if (resolvedAutoVersion) return resolvedAutoVersion;
  try {
    const status = await minecraftProtocol.ping({ host, port, timeout: 5000 });
    const serverVersion = status && status.version && status.version.name;
    if (!serverVersion) throw new Error('server ping did not report a version');
    if (minecraftData(serverVersion)) {
      resolvedAutoVersion = serverVersion;
      activeVersion = serverVersion;
      return activeVersion;
    }
    const latestSupported = minecraftData.versions.pc.find((v) => v.releaseType === 'release'
      && minecraftData(v.minecraftVersion));
    if (!latestSupported) throw new Error(`no supported PC client version for server ${serverVersion}`);
    resolvedAutoVersion = latestSupported.minecraftVersion;
    activeVersion = resolvedAutoVersion;
    console.warn(`[bench] server reports ${serverVersion}, which minecraft-data does not support; `
      + `using ${activeVersion}. The bench server needs ViaVersion/ViaBackwards.`);
    return activeVersion;
  } catch (err) {
    console.warn(`[bench] could not resolve server version (${err && err.message ? err.message : err}); `
      + 'keeping Mineflayer auto-detect for this connection');
    return false;
  }
}

// Plugin-side rejection phrases seen in chat. Out-of-band evidence only: the
// harness counts busy rejections from console lines, which player-directed
// replies usually never reach.
const BUSY_RE = /already teleporting|already rtp'?ing|have some patience|cool ?down|cooling down|please wait|you must wait|wait \d+|for another \d+|can'?t rtp|busy|queue|spot in line|no locations ready|try again/i;

const stats = {
  connects: 0, spawns: 0, kicks: 0, ends: 0, errors: 0, deaths: 0, busyChat: 0, swaps: 0,
  kickReasons: new Map()
};
// Teleport (server position packet) times seen by this worker's bots in the last 60 s.
const recentTps = [];
// Wire bytes of sockets that already closed; live sockets are added on read.
const wire = { rx: 0, tx: 0 };
const states = [];
const joinQueue = [];
let joinTimer = null;
let shuttingDown = false;

function nameFor(i) {
  return prefix + String(startIndex + i).padStart(width, '0');
}

/** Churn identity: base name + 'g' + base-36 generation, wrapped to stay
 *  within the 16-char username limit. A new name is a new offline UUID, so
 *  UUID-keyed retention on the server grows per session, not per bot. */
function churnName(base, generation) {
  const room = 16 - base.length - 1;
  if (room < 1) return base;
  const mod = Math.pow(36, Math.min(room, 6));
  return base + 'g' + (generation % mod).toString(36);
}

function reasonText(reason) {
  if (reason == null) return 'unknown';
  if (typeof reason === 'string') {
    try { return reasonText(JSON.parse(reason)); } catch (e) { return reason; }
  }
  if (typeof reason === 'object') {
    const plain = nbtPlain(reason);
    if (typeof plain === 'string') return plain;
    try {
      const parts = [];
      const walk = (n, depth) => {
        if (n == null || depth > 32) return;
        if (typeof n !== 'object') { parts.push(String(n)); return; }
        if (Array.isArray(n)) { n.forEach((c) => walk(c, depth + 1)); return; }
        if (typeof n.text === 'string') parts.push(n.text);
        else if (typeof n[''] === 'string') parts.push(n['']);
        else if (typeof n.translate === 'string') {
          const args = Array.isArray(n.with) ? n.with.map((w) => reasonText(w)) : [];
          parts.push(args.length ? `${n.translate}(${args.join(', ')})` : n.translate);
        }
        if (Array.isArray(n.extra)) n.extra.forEach((c) => walk(c, depth + 1));
      };
      walk(plain, 0);
      if (parts.length) return parts.join('');
    } catch (e) { /* fall through */ }
    return JSON.stringify(reason);
  }
  return String(reason);
}

/** Strip prismarine-nbt {type,value} wrappers (1.20.3+ text components arrive
 *  as NBT) into plain JSON-like values; plain objects pass through. */
function nbtPlain(n, depth = 0) {
  if (n == null || typeof n !== 'object' || depth > 64) return n;
  if (Array.isArray(n)) return n.map((c) => nbtPlain(c, depth + 1));
  const keys = Object.keys(n);
  if (typeof n.type === 'string' && keys.length === 2 && 'value' in n) {
    const v = n.value;
    if (n.type === 'compound') return nbtCompound(v, depth + 1);
    if (n.type === 'list') {
      const inner = v && v.type;
      const items = (v && Array.isArray(v.value)) ? v.value : [];
      return items.map((c) => (inner === 'compound' ? nbtCompound(c, depth + 1) : nbtPlain(c, depth + 1)));
    }
    return nbtPlain(v, depth + 1);
  }
  return nbtCompound(n, depth);
}

function nbtCompound(obj, depth) {
  if (obj == null || typeof obj !== 'object') return obj;
  const out = {};
  for (const k of Object.keys(obj)) out[k] = nbtPlain(obj[k], depth + 1);
  return out;
}

/** One global connect queue: every (re)connect is spaced by joinDelayMs so
 *  the shared source IP never trips connection-throttle. */
function enqueueJoin(state) {
  if (shuttingDown || state.queued) return;
  state.queued = true;
  joinQueue.push(state);
  pumpJoins();
}

function pumpJoins() {
  if (joinTimer || shuttingDown) return;
  const next = joinQueue.shift();
  if (!next) return;
  next.queued = false;
  connect(next);
  joinTimer = setTimeout(() => { joinTimer = null; pumpJoins(); }, joinDelayMs);
}

let throttleWarned = false;
function warnIfThrottled(text) {
  if (throttleWarned || !/throttl/i.test(text || '')) return;
  throttleWarned = true;
  console.warn('[bench] server connection-throttle is rejecting joins; raise --join-delay-ms above '
    + 'bukkit.yml settings.connection-throttle (4000), or set it to -1 for fast joins');
}

function scheduleReconnect(state) {
  if (shuttingDown || state.reconnectTimer) return;
  const exp = Math.min(reconnectMaxMs, reconnectBaseMs * Math.pow(2, Math.min(state.failures, 10)));
  const delay = Math.round(exp * (0.75 + Math.random() * 0.5));
  state.failures++;
  state.reconnectTimer = setTimeout(() => {
    state.reconnectTimer = null;
    enqueueJoin(state);
  }, delay);
}

function connect(state) {
  if (shuttingDown) return;
  stats.connects++;
  state.online = false;
  let bot;
  try {
    bot = mineflayer.createBot({
      host,
      port,
      username: state.username,
      auth: 'offline',
      version: activeVersion,
      viewDistance,
      physicsEnabled,
      respawn: true,
      hideErrors: true,
      checkTimeoutInterval: 60 * 1000,
      plugins: pluginOverrides
    });
  } catch (err) {
    stats.errors++;
    console.error(`[bench] [${state.username}] createBot failed: ${err.message}`);
    scheduleReconnect(state);
    return;
  }
  state.bot = bot;
  bot.__wireFolded = false;

  if (!keepWorld) {
    // Plugins register their packet listeners on inject; 'login' fires after
    // that and before the first chunk, so removal here catches every chunk.
    bot.once('login', () => {
      for (const p of WORLD_PACKETS) bot._client.removeAllListeners(p);
    });
  }

  bot.on('forcedMove', () => {
    const now = Date.now();
    // Login and respawn positions are not teleports.
    if (!state.online || now - state.spawnAt < 3000) return;
    state.lastTp = now;
    recentTps.push(now);
  });

  bot.once('spawn', () => {
    stats.spawns++;
    state.online = true;
    state.spawnAt = state.lastTp = Date.now();
    state.failures = 0; // a clean spawn resets backoff
    console.log(`[bench] [${state.username}] spawned (${onlineCount()}/${count} online)`);
  });

  bot.on('death', () => {
    stats.deaths++; // health plugin auto-respawns (respawn: true)
    console.log(`[bench] [${state.username}] died; respawning`);
  });

  bot.on('messagestr', (text) => {
    if (BUSY_RE.test(text)) stats.busyChat++;
    if (logChat) console.log(`[bench] [${state.username}] chat: ${text}`);
  });

  bot.on('kicked', (reason) => {
    stats.kicks++;
    const r = reasonText(reason).slice(0, 120);
    stats.kickReasons.set(r, (stats.kickReasons.get(r) || 0) + 1);
    if (churnRename && r.includes(CHURN_MARKER)) state.renamePending = true;
    console.warn(`[bench] [${state.username}] kicked: ${r}`);
    warnIfThrottled(r);
  });

  bot.on('error', (err) => {
    stats.errors++;
    console.warn(`[bench] [${state.username}] error: ${err && err.message ? err.message : err}`);
  });

  bot.on('end', (reason) => {
    stats.ends++;
    foldWire(bot);
    const wasOnline = state.online;
    state.online = false;
    state.bot = null;
    if (shuttingDown) return;
    const before = state.username;
    if (state.renamePending) {
      state.renamePending = false;
      state.generation++;
      state.username = churnName(state.baseName, state.generation);
    }
    console.warn(`[bench] [${before}] disconnected (${reasonText(reason)})${wasOnline ? '' : ' before spawn'}; reconnecting`
      + (before !== state.username ? ` as ${state.username}` : ''));
    scheduleReconnect(state);
  });
}

function onlineCount() {
  return states.reduce((n, s) => n + (s.online ? 1 : 0), 0);
}

/** Replace bots the server stopped teleporting while it keeps teleporting the others. */
function checkStuck() {
  if (shuttingDown) return;
  const now = Date.now();
  while (recentTps.length && now - recentTps[0] > 60000) recentTps.shift();
  if (recentTps.length < swapMinActivity) return;
  const online = states.filter((s) => s.online && s.bot);
  if (online.length < 2) return;
  const expectedGapMs = online.length / (recentTps.length / 60) * 1000;
  const limitMs = Math.max(swapIdleS * 1000, 10 * expectedGapMs);
  for (const s of online) {
    const idleMs = now - s.lastTp;
    if (idleMs <= limitMs) continue;
    stats.swaps++;
    s.renamePending = true;
    s.lastTp = now;
    console.warn(`[bench] ${workerLabel}[${s.username}] no teleport for ${Math.round(idleMs / 1000)}s while ${recentTps.length}`
      + ` seen in 60s; swapping to a new name`);
    try { s.bot.quit('bench-swarm swap'); } catch (e) { /* already closed */ }
  }
}

function botSocket(bot) {
  return bot && bot._client ? bot._client.socket : null;
}

/** Move a finished bot's socket counters into the closed total, once. */
function foldWire(bot) {
  if (!bot || bot.__wireFolded) return;
  bot.__wireFolded = true;
  const sock = botSocket(bot);
  if (!sock) return;
  wire.rx += sock.bytesRead || 0;
  wire.tx += sock.bytesWritten || 0;
}

function wireTotals() {
  let rx = wire.rx;
  let tx = wire.tx;
  for (const s of states) {
    const bot = s.bot;
    if (!bot || bot.__wireFolded) continue;
    const sock = botSocket(bot);
    if (!sock) continue;
    rx += sock.bytesRead || 0;
    tx += sock.bytesWritten || 0;
  }
  return { rx, tx };
}

function writeStatsFile() {
  if (!statsFile) return;
  const { rx, tx } = wireTotals();
  const rec = { t: Date.now(), w: isWorker ? Number(process.env.BENCH_WORKER_ID) : 0,
    rx, tx, online: onlineCount() };
  if (swapStuck) rec.swaps = stats.swaps;
  const line = JSON.stringify(rec) + '\n';
  try { fs.appendFileSync(statsFile, line); } catch (e) {
    console.warn(`[bench] ${workerLabel}stats-file write failed: ${e.message}`);
  }
}

function truncateStatsFile() {
  if (!statsFile) return;
  try { fs.writeFileSync(statsFile, ''); } catch (e) {
    console.error(`[bench] cannot write --stats-file ${statsFile}: ${e.message}`);
    process.exit(2);
  }
}

function printStats(prefixLabel) {
  const mem = process.memoryUsage();
  const top = [...stats.kickReasons.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3)
    .map(([r, n]) => `${n}x "${r}"`).join('; ');
  const w = wireTotals();
  console.log(`[bench] ${workerLabel}${prefixLabel} online=${onlineCount()}/${count} connects=${stats.connects} spawns=${stats.spawns}`
    + ` kicks=${stats.kicks} ends=${stats.ends} errors=${stats.errors} deaths=${stats.deaths}`
    + ` busy_chat=${stats.busyChat} queued=${joinQueue.length} rss=${Math.round(mem.rss / 1048576)}MB`
    + ` rx_mb=${(w.rx / 1048576).toFixed(1)} tx_mb=${(w.tx / 1048576).toFixed(1)}`
    + (swapStuck ? ` swaps=${stats.swaps}` : '')
    + ` heap=${Math.round(mem.heapUsed / 1048576)}MB${top ? ' top_kicks: ' + top : ''}`);
}

function shutdown(signal) {
  if (shuttingDown) {
    console.log('[bench] second signal; exiting now');
    process.exit(1);
  }
  shuttingDown = true;
  console.log(`[bench] ${signal}: disconnecting ${onlineCount()} bot(s)`);
  if (joinTimer) clearTimeout(joinTimer);
  joinQueue.length = 0;
  for (const s of states) {
    if (s.reconnectTimer) clearTimeout(s.reconnectTimer);
    if (s.bot) {
      try { s.bot.quit('bench-swarm shutdown'); } catch (e) { /* already closed */ }
    }
  }
  printStats('final');
  writeStatsFile();
  // Give quit packets a moment to flush, then exit regardless.
  setTimeout(() => process.exit(0), 1500).unref();
}

/** Strip flags the parent rewrites per worker. */
function workerArgs() {
  const drop = new Set(['--count', '--start-index', '--join-delay-ms', '--workers']);
  const out = [];
  for (let i = 0; i < args.length; i++) {
    if (drop.has(args[i])) {
      if (i + 1 < args.length && !args[i + 1].startsWith('--')) i++;
      continue;
    }
    out.push(args[i]);
  }
  return out;
}

function runParent() {
  truncateStatsFile();
  console.log(`[bench] ${count} bot(s) ${nameFor(0)}..${nameFor(count - 1)} -> ${host}:${port}`
    + ` across ${workers} worker process(es); join-delay=${joinDelayMs}ms (global)`
    + ` world-decode=${keepWorld ? 'on' : 'off'}`);
  console.log(`[bench] full roster online after ~${Math.round((count - 1) * joinDelayMs / 1000)}s; Ctrl+C to stop`);
  const children = [];
  const base = Math.floor(count / workers);
  let rem = count % workers;
  let next = startIndex;
  for (let w = 0; w < workers; w++) {
    const n = base + (rem-- > 0 ? 1 : 0);
    const child = childProcess.fork(__filename, [...workerArgs(),
      '--count', String(n), '--start-index', String(next), '--join-delay-ms', String(joinDelayMs * workers)], {
      env: { ...process.env, BENCH_WORKER: '1', BENCH_WORKER_ID: String(w),
        BENCH_INITIAL_DELAY_MS: String(w * joinDelayMs),
        // Keep the zero-padded width of the whole roster.
        BENCH_NAME_WIDTH: String(width) }
    });
    child.on('exit', (code) => console.log(`[bench] worker ${w} exited (${code})`));
    children.push(child);
    next += n;
  }
  let stopping = false;
  const stopAll = (signal) => {
    if (stopping) process.exit(1);
    stopping = true;
    console.log(`[bench] ${signal}: stopping ${children.length} worker(s)`);
    for (const c of children) { try { c.send({ cmd: 'shutdown' }); } catch (e) { /* gone */ } }
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGINT', () => stopAll('SIGINT'));
  process.on('SIGTERM', () => stopAll('SIGTERM'));
}

async function runBots() {
  await resolveVersion();
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('message', (m) => { if (m && m.cmd === 'shutdown') shutdown('parent'); });
  process.on('disconnect', () => shutdown('parent gone'));

  console.log(`[bench] ${workerLabel}${count} bot(s) ${nameFor(0)}..${nameFor(count - 1)} -> ${host}:${port}`
    + ` version=${activeVersion || 'auto'} join-delay=${joinDelayMs}ms view-distance=${viewDistance}`
    + ` physics=${physicsEnabled ? 'on' : 'off'} plugins=${fullPlugins ? 'full' : 'lean'}`
    + ` world-decode=${keepWorld ? 'on' : 'off'}`);
  if (!isWorker) {
    console.log(`[bench] full roster online after ~${Math.round((count - 1) * joinDelayMs / 1000)}s; Ctrl+C to stop`);
  }

  for (let i = 0; i < count; i++) {
    states.push({ username: nameFor(i), baseName: nameFor(i), generation: 0, renamePending: false,
      bot: null, online: false, failures: 0, queued: false, reconnectTimer: null, spawnAt: 0, lastTp: 0 });
  }
  setTimeout(() => { for (const s of states) enqueueJoin(s); }, initialDelayMs);
  if (swapStuck) setInterval(checkStuck, 5000).unref();

  if (statsIntervalS > 0) {
    setInterval(() => printStats('stats'), statsIntervalS * 1000).unref();
  }
  if (statsFile) {
    if (!isWorker) truncateStatsFile();
    setInterval(writeStatsFile, Math.round(statsFileIntervalS * 1000)).unref();
  }
}

// ---------------------------------------------------------------------------
// Fuzz-control mode
// ---------------------------------------------------------------------------

const FUZZ_ACTIONS = new Set(['rtp', 'rtp2', 'wait', 'move', 'quit', 'rejoin', 'die', 'cmd']);
const DENIED_ACTIONS = new Set([
  'op', 'deop', 'stop', 'restart', 'reload', 'lp', 'luckperms',
  'bukkit:reload', 'minecraft:op', 'rtp reload', 'rtp admin',
  'betterrtp reload', 'betterrtp admin', 'huskhomes reload', 'jakesrtp reload'
]);
const DENIAL_PATTERNS = /(no permission|unknown command|do not have permission|unknown or incomplete|cannot execute|denied|i'm sorry|not permitted)/i;
const FUZZ_NAME_RE = /^[A-Za-z0-9_]{1,16}$/;
const FUZZ_MAX_LINE = 1 << 20;
const FUZZ_MAX_PROGRAM = 500;
const FUZZ_QUIT_GRACE_MS = 5000;
let fuzzEpisode = null;
let fuzzLastConnectAt = -Infinity;
let fuzzJoinChain = Promise.resolve();
let fuzzServer = null;

function fail(msg) { throw new Error(msg); }

function cleanText(v, max, what) {
  if (v === undefined || v === null) return '';
  if (typeof v !== 'string') fail(`${what} must be a string`);
  if (/[\r\n\u0000]/.test(v)) fail(`${what} must be one line`);
  if (v.length > max) fail(`${what} longer than ${max}`);
  return v.trim();
}

function num(v, lo, hi, def, what) {
  if (v === undefined || v === null) return def;
  if (typeof v !== 'number' || !Number.isFinite(v)) fail(`${what} must be a finite number`);
  if (v < lo || v > hi) fail(`${what} out of range [${lo}, ${hi}]`);
  return v;
}

/** Normalise one program action; throws with a message naming the step. */
function parseAction(raw, where) {
  const o = typeof raw === 'string' ? { a: raw } : raw;
  if (!o || typeof o !== 'object' || Array.isArray(o)) fail(`${where}: action must be a string or object`);
  if (!FUZZ_ACTIONS.has(o.a)) fail(`${where}: unknown action ${JSON.stringify(o.a)}`);
  switch (o.a) {
    case 'rtp': case 'rtp2': return { a: o.a, args: cleanText(o.args, 128, `${where}.args`) };
    case 'wait': return { a: 'wait', ms: Math.round(num(o.ms, 0, 120000, 0, `${where}.ms`)) };
    case 'move': return { a: 'move', dx: num(o.dx, -64, 64, 0, `${where}.dx`),
      dz: num(o.dz, -64, 64, 0, `${where}.dz`), ms: Math.round(num(o.ms, 0, 60000, 1000, `${where}.ms`)) };
    case 'rejoin':
      if (o.rename !== undefined && typeof o.rename !== 'boolean') fail(`${where}.rename must be a boolean`);
      return { a: 'rejoin', rename: o.rename === true };
    case 'cmd': return { a: 'cmd', cmd: cleanText(o.cmd, 256, `${where}.cmd`) };
    default: return { a: o.a };
  }
}

/** Validate an episode request into a normalised copy; throws on bad input. */
function parseEpisode(req) {
  const id = req.id;
  if (!((typeof id === 'string' && id.length > 0 && id.length <= 64) || Number.isSafeInteger(id))) {
    fail('id must be a non-empty string (<=64) or an integer');
  }
  let cmd = cleanText(req.cmd, 200, 'cmd');
  if (cmd.startsWith('/')) cmd = cmd.slice(1);
  if (!cmd) fail('cmd is required');
  const maxMs = Math.round(num(req.max_ms, 1000, 3600000, fuzzEpisodeMaxMs, 'max_ms'));
  if (!Array.isArray(req.bots) || req.bots.length === 0) fail('bots must be a non-empty array');
  if (req.bots.length > fuzzMaxBots) fail(`at most ${fuzzMaxBots} bots per episode`);
  const names = new Set();
  const bots = req.bots.map((b, i) => {
    if (!b || typeof b !== 'object') fail(`bots[${i}] must be an object`);
    if (typeof b.name !== 'string' || !FUZZ_NAME_RE.test(b.name)) fail(`bots[${i}].name must match ${FUZZ_NAME_RE}`);
    if (names.has(b.name.toLowerCase())) fail(`duplicate bot name ${b.name}`);
    names.add(b.name.toLowerCase());
    if (!Array.isArray(b.program)) fail(`bots[${i}].program must be an array`);
    if (b.program.length > FUZZ_MAX_PROGRAM) fail(`bots[${i}].program longer than ${FUZZ_MAX_PROGRAM}`);
    return { name: b.name, program: b.program.map((a, k) => parseAction(a, `bots[${i}].program[${k}]`)) };
  });
  return { id, cmd, maxMs, bots };
}

function fuzzSend(sock, obj) {
  if (sock && !sock.destroyed && sock.writable) {
    try { sock.write(JSON.stringify(obj) + '\n'); } catch (e) { /* peer gone */ }
  }
}

function posOf(bot) {
  const p = bot && bot.entity && bot.entity.position;
  if (!p || !Number.isFinite(p.x)) return null;
  return { x: +p.x.toFixed(2), y: +p.y.toFixed(2), z: +p.z.toFixed(2) };
}

/** Sleep that an episode abort cuts short. */
function epSleep(ep, ms) {
  return new Promise((resolve) => {
    if (ep.aborted) { resolve(); return; }
    const wake = () => { clearTimeout(timer); ep.wakers.delete(wake); resolve(); };
    const timer = setTimeout(wake, Math.max(0, ms));
    ep.wakers.add(wake);
  });
}

function abortEpisode(ep, reason) {
  if (ep.aborted) return;
  ep.aborted = reason;
  for (const w of [...ep.wakers]) w();
}

/** Global connect spacing (--join-delay-ms, connection-throttle). Joins queue
 *  in order and wait from the last actual connect; an aborted episode's
 *  queued joins fall through without consuming a slot. */
function joinSlot(ep) {
  const slot = fuzzJoinChain.then(async () => {
    if (ep.aborted) return;
    const wait = fuzzLastConnectAt + joinDelayMs - Date.now();
    if (wait > 0) await epSleep(ep, wait);
    if (!ep.aborted) fuzzLastConnectAt = Date.now();
  });
  fuzzJoinChain = slot;
  return slot;
}

/** Close one connection record exactly once and wake its waiters. */
function finishConn(ep, bs, conn, text, forced) {
  if (conn.ended) return;
  conn.ended = true;
  if (conn.spawnTimer) clearTimeout(conn.spawnTimer);
  if (bs.conn === conn) bs.conn = null;
  ep.emit({ ev: 'end', bot: conn.username, text: String(text).slice(0, 256), ...(forced ? { forced: true } : {}) });
  for (const w of conn.waiters.splice(0)) w('end');
}

/** Join as bs.username; resolves 'spawn', 'end' or 'aborted'. */
async function fuzzConnect(ep, bs) {
  await resolveVersion();
  await joinSlot(ep);
  if (ep.aborted) return 'aborted';
  const conn = { username: bs.username, bot: null, spawned: false, ended: false, waiters: [], spawnTimer: null };
  bs.conn = conn;
  bs.joins++;
  return new Promise((resolve) => {
    let settled = false;
    const settle = (r) => {
      if (settled) return;
      settled = true;
      ep.wakers.delete(onAbort);
      resolve(r);
    };
    const onAbort = () => settle('aborted');
    ep.wakers.add(onAbort);
    conn.waiters.push(settle);
    let bot;
    try {
      bot = mineflayer.createBot({ host, port, username: bs.username, auth: 'offline', version: activeVersion, viewDistance,
        physicsEnabled, respawn: true, hideErrors: true, checkTimeoutInterval: 60 * 1000, plugins: pluginOverrides });
    } catch (err) {
      ep.emit({ ev: 'bot_error', bot: bs.username, text: String(err && err.message ? err.message : err) });
      finishConn(ep, bs, conn, 'createBot failed', true);
      return;
    }
    conn.bot = bot;
    if (!keepWorld) bot.once('login', () => { for (const p of WORLD_PACKETS) bot._client.removeAllListeners(p); });
    bot.on('spawn', () => {
      ep.emit({ ev: 'spawn', bot: conn.username, pos: posOf(bot) });
      if (!conn.spawned) {
        conn.spawned = true;
        bs.spawns++;
        clearTimeout(conn.spawnTimer);
        settle('spawn');
      }
    });
    bot.on('death', () => ep.emit({ ev: 'death', bot: conn.username }));
    bot.on('forcedMove', () => ep.emit({ ev: 'tp', bot: conn.username, pos: posOf(bot) }));
    bot.on('messagestr', (text, position) => {
      ep.emit({ ev: 'chat', bot: conn.username, text: String(text).slice(0, 512), ...(position ? { kind: position } : {}) });
      if (bs.lastCmd) {
        const cmdClean = bs.lastCmd.startsWith('/') ? bs.lastCmd.slice(1) : bs.lastCmd;
        const root = cmdClean.split(' ')[0];
        if (DENIED_ACTIONS.has(cmdClean) || DENIED_ACTIONS.has(root)) {
          const str = String(text);
          if (!DENIAL_PATTERNS.test(str)) {
            ep.emit({ ev: 'denial_failure', bot: conn.username, cmd: bs.lastCmd, text: str.slice(0, 256) });
          }
        }
      }
    });
    bot.on('kicked', (reason) => {
      bs.kicked = true;
      ep.emit({ ev: 'kick', bot: conn.username, text: reasonText(reason).slice(0, 512) });
    });
    bot.on('error', (err) => {
      ep.emit({ ev: 'bot_error', bot: conn.username, text: String(err && err.message ? err.message : err).slice(0, 256) });
    });
    bot.on('end', (reason) => finishConn(ep, bs, conn, reasonText(reason), false));
    conn.spawnTimer = setTimeout(() => {
      if (conn.spawned || conn.ended) return;
      ep.emit({ ev: 'bot_error', bot: conn.username, text: `no spawn within ${fuzzSpawnTimeoutMs} ms` });
      settle('end');
      fuzzDisconnect(ep, bs, 'spawn timeout');
    }, fuzzSpawnTimeoutMs);
  });
}

/** Quit the current connection; force-close after a grace period so the
 *  episode never waits on a silent socket. */
function fuzzDisconnect(ep, bs, why) {
  const conn = bs.conn;
  if (!conn || conn.ended) return Promise.resolve();
  return new Promise((resolve) => {
    conn.waiters.push(() => resolve());
    const bot = conn.bot;
    try { if (bot) bot.quit(why); } catch (e) { /* not connected */ }
    setTimeout(() => {
      if (conn.ended) return;
      try { if (bot && bot._client && bot._client.socket) bot._client.socket.destroy(); } catch (e) { /* gone */ }
      setTimeout(() => finishConn(ep, bs, conn, `${why} (no end event)`, true), 1000).unref();
    }, FUZZ_QUIT_GRACE_MS).unref();
  });
}

function onlineBot(bs) {
  const c = bs.conn;
  return c && c.spawned && !c.ended && c.bot ? c.bot : null;
}

/** Walk with raw position packets: with world decode off mineflayer's
 *  physics tick never sends positions (blockAt is null). Each step is
 *  relative to the current position, so a teleport mid-move is kept. */
async function fuzzMove(ep, bs, act) {
  const bot = onlineBot(bs);
  const steps = Math.max(1, Math.round(act.ms / 50));
  for (let k = 0; k < steps; k++) {
    if (ep.aborted || onlineBot(bs) !== bot || !posOf(bot)) return;
    const p = bot.entity.position.offset(act.dx / steps, 0, act.dz / steps);
    bot.entity.position = p;
    try {
      bot._client.write('position', { x: p.x, y: p.y, z: p.z, onGround: true,
        flags: { onGround: true, hasHorizontalCollision: false } });
    } catch (e) { return; }
    await epSleep(ep, act.ms / steps);
  }
}

async function runFuzzProgram(ep, bs) {
  const first = await fuzzConnect(ep, bs);
  const program = bs.program;
  let i = 0;
  if (first === 'spawn') {
    for (; i < program.length; i++) {
      if (ep.aborted || bs.kicked) break;
      const act = program[i];
      const bot = onlineBot(bs);
      const needsBot = act.a !== 'wait' && act.a !== 'rejoin';
      if (needsBot && !bot) {
        ep.emit({ ev: 'skip', bot: bs.username, i, a: act.a, why: 'offline' });
        bs.skipped++;
        continue;
      }
      ep.emit({ ev: 'act', bot: bs.username, i, ...act });
      bs.ran++;
      switch (act.a) {
        case 'rtp': case 'rtp2': {
          const line = '/' + ep.cmd.split('{self}').join(bs.username) + (act.args ? ' ' + act.args : '');
          try { bot.chat(line); } catch (e) { ep.emit({ ev: 'bot_error', bot: bs.username, text: String(e.message) }); }
          break;
        }
        case 'die':
          try { bot.chat('/kill'); } catch (e) { ep.emit({ ev: 'bot_error', bot: bs.username, text: String(e.message) }); }
          break;
        case 'cmd': {
          const rawCmd = act.cmd.split('{self}').join(bs.username);
          const line = rawCmd.startsWith('/') ? rawCmd : '/' + rawCmd;
          bs.lastCmd = rawCmd.toLowerCase().trim();
          try { bot.chat(line); } catch (e) { ep.emit({ ev: 'bot_error', bot: bs.username, text: String(e.message) }); }
          break;
        }
        case 'wait': await epSleep(ep, act.ms); break;
        case 'move': await fuzzMove(ep, bs, act); break;
        case 'quit': await fuzzDisconnect(ep, bs, 'fuzz quit'); break;
        case 'rejoin':
          await fuzzDisconnect(ep, bs, 'fuzz rejoin');
          if (act.rename) { bs.generation++; bs.username = churnName(bs.baseName, bs.generation); }
          if (!ep.aborted) await fuzzConnect(ep, bs);
          break;
      }
    }
  }
  if (i < program.length) {
    const why = ep.aborted ? String(ep.aborted) : bs.kicked ? 'kicked' : 'no spawn';
    ep.emit({ ev: 'skip', bot: bs.username, i, n: program.length - i, why });
    bs.skipped += program.length - i;
  }
  await fuzzDisconnect(ep, bs, 'fuzz program end');
}

async function runFuzzEpisode(sock, spec) {
  await resolveVersion();
  const ep = { id: spec.id, cmd: spec.cmd, start: Date.now(), aborted: null, wakers: new Set(), sock };
  ep.emit = (obj) => fuzzSend(ep.sock, { ...obj, id: ep.id, t: Date.now() - ep.start });
  fuzzEpisode = ep;
  fuzzSend(sock, { ev: 'accepted', id: spec.id, bots: spec.bots.length });
  console.log(`[fuzz] episode ${spec.id}: ${spec.bots.length} bot(s), cmd=/${spec.cmd}, max ${spec.maxMs} ms`);
  const deadline = setTimeout(() => abortEpisode(ep, 'timeout'), spec.maxMs);
  const states = spec.bots.map((b) => ({ baseName: b.name, username: b.name, program: b.program, generation: 0,
    conn: null, kicked: false, joins: 0, spawns: 0, ran: 0, skipped: 0, lastCmd: null }));
  ep.states = states;
  try {
    await Promise.all(states.map((bs) => runFuzzProgram(ep, bs).catch((e) => {
      ep.emit({ ev: 'bot_error', bot: bs.username, text: `program failed: ${e && e.message ? e.message : e}` });
      return fuzzDisconnect(ep, bs, 'program failed');
    })));
  } finally {
    clearTimeout(deadline);
  }
  const ms = Date.now() - ep.start;
  fuzzSend(ep.sock, { ev: 'done', id: spec.id, ms, reason: ep.aborted || 'complete',
    bots: states.map((bs) => ({ name: bs.baseName, last: bs.username, joins: bs.joins, spawns: bs.spawns,
      ran: bs.ran, skipped: bs.skipped, kicked: bs.kicked })) });
  console.log(`[fuzz] episode ${spec.id} done in ${ms} ms (${ep.aborted || 'complete'})`);
  fuzzEpisode = null;
  return ep;
}

function stopFuzz(sock, why) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[fuzz] ${why}: stopping`);
  const ep = fuzzEpisode;
  if (ep) abortEpisode(ep, 'stopped');
  if (fuzzServer) fuzzServer.close();
  const finish = () => {
    fuzzSend(sock, { ev: 'stopped' });
    const exit = () => process.exit(0);
    if (sock && !sock.destroyed) sock.end(exit); else exit();
    setTimeout(exit, 1000).unref();
  };
  if (!ep) { finish(); return; }
  // runFuzzEpisode sends done first; the force-close path bounds this.
  const poll = setInterval(() => { if (!fuzzEpisode) { clearInterval(poll); finish(); } }, 50);
  setTimeout(() => { clearInterval(poll); finish(); }, FUZZ_QUIT_GRACE_MS + 3000).unref();
}

function handleFuzzLine(sock, line) {
  let req;
  try { req = JSON.parse(line); } catch (e) { fuzzSend(sock, { ev: 'error', error: 'invalid JSON' }); return; }
  if (!req || typeof req !== 'object' || Array.isArray(req)) { fuzzSend(sock, { ev: 'error', error: 'request must be an object' }); return; }
  const id = req.id;
  switch (req.op) {
    case 'ping': fuzzSend(sock, { ev: 'pong', ...(id !== undefined ? { id } : {}), busy: !!fuzzEpisode }); return;
    case 'stop': stopFuzz(sock, 'stop request'); return;
    case 'episode': {
      if (shuttingDown) { fuzzSend(sock, { ev: 'error', id, error: 'stopping' }); return; }
      if (fuzzEpisode) { fuzzSend(sock, { ev: 'error', id, error: `busy with episode ${fuzzEpisode.id}` }); return; }
      let spec;
      try { spec = parseEpisode(req); } catch (e) { fuzzSend(sock, { ev: 'error', id, error: e.message }); return; }
      runFuzzEpisode(sock, spec).catch((e) => {
        fuzzEpisode = null;
        fuzzSend(sock, { ev: 'error', id, error: `episode failed: ${e && e.message ? e.message : e}` });
      });
      return;
    }
    default: fuzzSend(sock, { ev: 'error', id, error: `unknown op ${JSON.stringify(req.op)}` });
  }
}

function runFuzz() {
  const net = require('net');
  const listenPort = parseInt(fuzzControlRaw, 10);
  if (!Number.isInteger(listenPort) || listenPort < 0 || listenPort > 65535) {
    console.error(`[fuzz] --fuzz-control needs a port 0..65535, got "${fuzzControlRaw}"`);
    process.exit(2);
  }
  fuzzServer = net.createServer((sock) => {
    sock.setEncoding('utf8');
    sock.setNoDelay(true);
    let buf = '';
    sock.on('data', (chunk) => {
      buf += chunk;
      let nl;
      while ((nl = buf.indexOf('\n')) !== -1) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (line) handleFuzzLine(sock, line);
      }
      if (buf.length > FUZZ_MAX_LINE) {
        fuzzSend(sock, { ev: 'error', error: `request line over ${FUZZ_MAX_LINE} bytes` });
        sock.destroy();
      }
    });
    sock.on('error', () => { /* handled by close */ });
    sock.on('close', () => {
      // The controller owns the episode; without it nobody reads the trace.
      if (fuzzEpisode && fuzzEpisode.sock === sock) abortEpisode(fuzzEpisode, 'controller disconnected');
    });
  });
  fuzzServer.on('error', (err) => {
    console.error(`[fuzz] control socket: ${err.message}`);
    process.exit(2);
  });
  fuzzServer.listen(listenPort, '127.0.0.1', () => {
    console.log(`[fuzz] listening on 127.0.0.1:${fuzzServer.address().port} -> ${host}:${port}`
      + ` version=${activeVersion || 'auto'} join-delay=${joinDelayMs}ms spawn-timeout=${fuzzSpawnTimeoutMs}ms`);
  });
  process.on('SIGINT', () => (shuttingDown ? process.exit(1) : stopFuzz(fuzzEpisode && fuzzEpisode.sock, 'SIGINT')));
  process.on('SIGTERM', () => stopFuzz(fuzzEpisode && fuzzEpisode.sock, 'SIGTERM'));
  // Lifetime tied to the controller process: its death closes our stdin pipe.
  const stdinGone = () => stopFuzz(fuzzEpisode && fuzzEpisode.sock, 'stdin closed');
  process.stdin.on('end', stdinGone);
  process.stdin.on('close', stdinGone);
  process.stdin.on('error', stdinGone);
  process.stdin.on('data', () => { /* drain; content ignored */ });
  process.stdin.resume();
}

if (fuzzMode) runFuzz(); else if (workers > 1) runParent(); else runBots().catch((err) => {
  console.error(`[bench] startup failed: ${err && err.message ? err.message : err}`);
  process.exit(2);
});
