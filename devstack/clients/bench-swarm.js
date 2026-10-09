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
 *     either set it to -1 or keep --join-delay-ms above it (default 4000 ms).
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
 *   --log-chat               print every chat/system line received (noisy)
 *   --workers <n>            [BENCH_WORKERS]       default ceil(count/16), max cpus-1 (1 = in-process)
 *   --keep-world             [BENCH_KEEP_WORLD=1]  decode chunks into a client-side world (off)
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
const childProcess = require('child_process');
const os = require('os');

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
const version = getArg('--version', 'BENCH_VERSION', '') || false;
const joinDelayMs = Math.max(0, intArg('--join-delay-ms', 'BENCH_JOIN_DELAY_MS', 4500));
const viewDistanceRaw = getArg('--view-distance', 'BENCH_VIEW_DISTANCE', '2');
const viewDistance = /^\d+$/.test(viewDistanceRaw) ? Math.max(2, parseInt(viewDistanceRaw, 10)) : viewDistanceRaw;
const physicsEnabled = hasFlag('--physics', 'BENCH_PHYSICS');
const fullPlugins = hasFlag('--full-plugins', 'BENCH_FULL_PLUGINS');
const reconnectBaseMs = Math.max(500, intArg('--reconnect-base-ms', null, 5000));
const reconnectMaxMs = Math.max(reconnectBaseMs, intArg('--reconnect-max-ms', null, 60000));
const statsIntervalS = Math.max(0, intArg('--stats-interval-s', null, 30));
const logChat = hasFlag('--log-chat', null);
const keepWorld = hasFlag('--keep-world', 'BENCH_KEEP_WORLD');
const isWorker = process.env.BENCH_WORKER === '1';
const workerLabel = isWorker ? `[w${process.env.BENCH_WORKER_ID}] ` : '';
const initialDelayMs = Math.max(0, parseInt(process.env.BENCH_INITIAL_DELAY_MS || '0', 10) || 0);
const maxWorkers = Math.max(1, (os.cpus() || []).length - 1);
const workers = isWorker ? 1
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

// Plugin-side rejection phrases seen in chat. Out-of-band evidence only: the
// harness counts busy rejections from console lines, which player-directed
// replies usually never reach.
const BUSY_RE = /already teleporting|already rtp'?ing|have some patience|cool ?down|cooling down|please wait|you must wait|wait \d+|for another \d+|can'?t rtp|busy|queue|spot in line|no locations ready|try again/i;

const stats = {
  connects: 0, spawns: 0, kicks: 0, ends: 0, errors: 0, deaths: 0, busyChat: 0,
  kickReasons: new Map()
};
const states = [];
const joinQueue = [];
let joinTimer = null;
let shuttingDown = false;

function nameFor(i) {
  return prefix + String(startIndex + i).padStart(width, '0');
}

function reasonText(reason) {
  if (reason == null) return 'unknown';
  if (typeof reason === 'string') {
    try { return reasonText(JSON.parse(reason)); } catch (e) { return reason; }
  }
  if (typeof reason === 'object') {
    if (typeof reason.text === 'string' && !reason.extra) return reason.text;
    if (typeof reason.translate === 'string') return reason.translate;
    try {
      const parts = [];
      const walk = (n) => {
        if (n == null) return;
        if (typeof n === 'string') { parts.push(n); return; }
        if (typeof n.text === 'string') parts.push(n.text);
        if (Array.isArray(n.extra)) n.extra.forEach(walk);
        if (n.value) walk(n.value);
      };
      walk(reason);
      if (parts.length) return parts.join('');
    } catch (e) { /* fall through */ }
    return JSON.stringify(reason);
  }
  return String(reason);
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
      version,
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

  if (!keepWorld) {
    // Plugins register their packet listeners on inject; 'login' fires after
    // that and before the first chunk, so removal here catches every chunk.
    bot.once('login', () => {
      for (const p of WORLD_PACKETS) bot._client.removeAllListeners(p);
    });
  }

  bot.once('spawn', () => {
    stats.spawns++;
    state.online = true;
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
    console.warn(`[bench] [${state.username}] kicked: ${r}`);
  });

  bot.on('error', (err) => {
    stats.errors++;
    console.warn(`[bench] [${state.username}] error: ${err && err.message ? err.message : err}`);
  });

  bot.on('end', (reason) => {
    stats.ends++;
    const wasOnline = state.online;
    state.online = false;
    state.bot = null;
    if (shuttingDown) return;
    console.warn(`[bench] [${state.username}] disconnected (${reasonText(reason)})${wasOnline ? '' : ' before spawn'}; reconnecting`);
    scheduleReconnect(state);
  });
}

function onlineCount() {
  return states.reduce((n, s) => n + (s.online ? 1 : 0), 0);
}

function printStats(prefixLabel) {
  const mem = process.memoryUsage();
  const top = [...stats.kickReasons.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3)
    .map(([r, n]) => `${n}x "${r}"`).join('; ');
  console.log(`[bench] ${workerLabel}${prefixLabel} online=${onlineCount()}/${count} connects=${stats.connects} spawns=${stats.spawns}`
    + ` kicks=${stats.kicks} ends=${stats.ends} errors=${stats.errors} deaths=${stats.deaths}`
    + ` busy_chat=${stats.busyChat} queued=${joinQueue.length} rss=${Math.round(mem.rss / 1048576)}MB`
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

function runBots() {
  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));
  process.on('message', (m) => { if (m && m.cmd === 'shutdown') shutdown('parent'); });
  process.on('disconnect', () => shutdown('parent gone'));

  console.log(`[bench] ${workerLabel}${count} bot(s) ${nameFor(0)}..${nameFor(count - 1)} -> ${host}:${port}`
    + ` version=${version || 'auto'} join-delay=${joinDelayMs}ms view-distance=${viewDistance}`
    + ` physics=${physicsEnabled ? 'on' : 'off'} plugins=${fullPlugins ? 'full' : 'lean'}`
    + ` world-decode=${keepWorld ? 'on' : 'off'}`);
  if (!isWorker) {
    console.log(`[bench] full roster online after ~${Math.round((count - 1) * joinDelayMs / 1000)}s; Ctrl+C to stop`);
  }

  for (let i = 0; i < count; i++) {
    states.push({ username: nameFor(i), bot: null, online: false, failures: 0, queued: false, reconnectTimer: null });
  }
  setTimeout(() => { for (const s of states) enqueueJoin(s); }, initialDelayMs);

  if (statsIntervalS > 0) {
    setInterval(() => printStats('stats'), statsIntervalS * 1000).unref();
  }
}

if (workers > 1) runParent(); else runBots();
