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
 *   --fuzz-max-bots <n>           default 128
 *
 * Fuzz mode (--fuzz-control): no roster, no workers, no reconnects. Listens on
 * 127.0.0.1:<port> for newline-delimited JSON, one episode at a time:
 *   {"op":"episode","id":7,"cmd":"rtp","max_ms":90000,"telemetry":false,
 *    "bots":[{"name":"fz7_0","program":["rtp",{"a":"wait","ms":200},"quit"]}]}
 *   {"op":"discover","id":1,"tier":"default","bot":"fzd_default"}
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
 * (plus window and tick_gap{ms,at,keepalive_ms} when episode "telemetry":true,
 * and commands on discover; t = ms since accept, id = episode id); finally
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
const fuzzTaskMode = hasFlag('--fuzz-task-mode', 'BENCH_FUZZ_TASK_MODE');
const churnRename = hasFlag('--churn-rename', 'BENCH_CHURN_RENAME');
const fuzzControlRaw = getArg('--fuzz-control', 'BENCH_FUZZ_CONTROL', '');
const fuzzMode = fuzzControlRaw !== '';
const fuzzSpawnTimeoutMs = Math.max(1000, intArg('--fuzz-spawn-timeout-ms', null, 30000));
const fuzzEpisodeMaxMs = Math.max(1000, intArg('--fuzz-episode-max-ms', null, 180000));
const fuzzMaxBots = Math.max(1, intArg('--fuzz-max-bots', null, 128));
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
    // CraftBukkit-derived servers ping as "<mod> <version>" (e.g. "Paper 1.21.4"), so the raw
    // name is not a minecraft-data key; match the protocol number first, then a parsed version.
    const proto = status.version.protocol;
    const supported = (v) => !!minecraftData(v);
    const parsed = /\d+\.\d+(?:\.\d+)?/.exec(serverVersion);
    const parsedVersion = parsed && supported(parsed[0]) ? parsed[0] : null;
    let named = null;
    if (parsedVersion && (typeof proto !== 'number' || minecraftData(parsedVersion).version.version === proto)) {
      named = parsedVersion;
    } else if (typeof proto === 'number') {
      const byProto = minecraftData.versions.pc.filter((v) => v.version === proto && supported(v.minecraftVersion));
      const best = byProto.find((v) => v.releaseType === 'release') || byProto[0];
      if (best) named = best.minecraftVersion;
    }
    if (!named && supported(serverVersion)) named = serverVersion;
    if (named) {
      resolvedAutoVersion = named;
      activeVersion = named;
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

const FUZZ_ACTIONS = new Set(['rtp', 'rtp2', 'wait', 'move', 'quit', 'rejoin', 'die', 'cmd', 'click', 'close_window', 'use_block', 'swing', 'drop', 'policy']);
const EV_BOT_ERROR = 'bot' + '_error';

/**
 * 16-feature integer context (Q7 in [-127, 127]) and 16 compositional micro-skills for
 * the 256-byte Int8 compact stochastic policy runner. All matrix math and sampling uses
 * 32-bit integer arithmetic (`Math.imul`, `| 0`, `>>> 0`) for fast, deterministic CPU execution.
 * @governedBy ADR-111 ADR-125
 */
const POLICY_NUM_FEATURES = 16;
const POLICY_NUM_ACTIONS = 16;
const POLICY_WEIGHT_BYTES = POLICY_NUM_FEATURES * POLICY_NUM_ACTIONS; // 256 bytes

const POLICY_FEATURE_NAMES = [
  'bias',
  'enclosed_horiz',
  'roof_cover',
  'floor_solid',
  'light_deficit',
  'depth_norm',
  'inv_building',
  'inv_wood_raw',
  'inv_torches',
  'inv_functional',
  'inv_ores_val',
  'near_log_or_ore',
  'near_container',
  'anchor_dist',
  'stalled_steps',
  'trait_role'
];

const POLICY_MICRO_SKILLS = [
  'noop_wait',
  'rtp_or_anchor',
  'step_toward_anchor',
  'step_away_anchor',
  'harvest_nearest',
  'dig_stair_down',
  'dig_tunnel_fwd',
  'craft_basics',
  'place_wall_ring',
  'place_roof_cell',
  'place_pillar_or_bridge',
  'place_torch_if_dark',
  'place_utility',
  'place_redstone_link',
  'interact_nearest',
  'deposit_or_trade'
];

// 16-entry Q10 fixed-point fractional 2^(r/16) lookup table (1024 .. 1984) for integer softmax.
const EXP2_FRAC_LUT = new Int32Array([
  1024, 1070, 1117, 1166, 1218, 1272, 1328, 1387,
  1448, 1512, 1579, 1649, 1722, 1798, 1878, 1961
]);

/** Deterministic 32-bit integer PRNG (Mulberry32); returns unsigned 32-bit int. */
function makeIntRng(seed) {
  let state = (seed | 0) || 1;
  return function nextUint32() {
    state = (state + 0x6D2B79F5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return (t ^ (t >>> 14)) >>> 0;
  };
}

/** Clamp integer to signed Int8 range [-127, 127]. */
function clampInt8(v) {
  const n = v | 0;
  return n < -127 ? -127 : (n > 127 ? 127 : n);
}

/** Integer piecewise-linear saturating curve mapping [0, cap] -> [0, 127] (approximates tanh). */
function intSatRatio(count, cap) {
  const c = Math.max(0, count | 0);
  const m = Math.max(1, cap | 0);
  if (c >= m) return 127;
  // Concave integer curve: fast rise for first half, gentler for second half
  const num = Math.imul(c, 254) - Math.imul(Math.floor(Math.imul(c, c) / m), 127);
  return clampInt8(Math.floor(num / m));
}

const POLICY_PRESET_ORDER = [
  'house',
  'mineshaft',
  'caving',
  'villager',
  'shop',
  'machine',
  'explorer',
  'diamond'
];

// Prebaked 256-byte Int8 policy models (base64-encoded, 344 ASCII chars each) trained via PolicyCem.
const PREBAKED_POLICIES = Object.freeze({
  house: '6v/69Prw9/z//fkIBf36+wb5/wH4Aev/9P0F/wP//AD8/Pf+A//5BQYE+gEHJAP8+wIGCvD89Pv+DgH+BQHzAhUB+ff39Pz6CAEBNfL89/fs/vX++AYH+vwG/QH78AL6/P0ADwIH+fQGCgUA+QH8BfUE8vwGBQEl9PcC+v/89AML//bx+wgtA/oDB/n5+AL5/jH6///6Lfb49/D9/f37+/cE8vz89/78AQHzDQT7APkCB/79I/sH+h7//fYJ+wj/BgX+AggF9vn1PgP9/u77/PD7AAL3CwX0CQLw+AMECwIEAAD8//YB+Pz/9vsL9+8J9AEFDAT3APkBAPb5/P/3+A==',
  mineshaft: 'BQPxA+74+vUA+v74/f/3+w/1/gMF+/4H9P/u9gb4CPv5APT5Bv/8Afn9K/v+LfYH/vn8/wH++/wBBwD4AAf8//cFAPT8JQECAQL5I/X+/fof/+8K+Qf5+/MJAv3vAPL59foDBgci//72Bvj4AgH7+/z3+u35AQElAvoi+PoF+QL4//Xy/AL7B/0D+gkE/ggH/gUGCgT8+PQH+/YEAf/6CPv89fUA+AH5//T5A/X+9fwD/v0FIgv98hr5+vj49/oD9/P39/n0+/YDBAYD9v0G//kHAfb6/f77AQPv/gD+Dfr5/fYG++/qBA32+P0F/PwIBAIE8QX7A/UC8/36+QUB+w==',
  caving: '9QL7Be36+PXzAPb2+gX78gbt+gb0//AB9An0///2CwP8AvcEBgL/AAAE/+sCJ/gH9fj9AQ0G/wL7Bfzt/v7/+/72/vX7K/gJCAH5I/L+7vEO9f0AAf33BPUA9Pj87/0GJ//+APMp+Qf+//4I+gXu8/bq+/UM/gYn9P0x/vsE9QIE9xH69AQF/vH0+v0ABP8I//0F+AkE+/3+BvQB/AMAA/z7+P348hQF/AEB8PT69fj98QABGvz/BR779AH+9/r9+uj/8AT0Bv0CAf7x+gX9+/H7+/vxA/vzA//8/vz1Avz6/gL98/b2AP3u+QL78AL//f/88vn5/AL0+wT88Qf49w==',
  villager: '+v/+//r69PnxDQL46PwFAAH2BQLuA/kGA/v6BfQA/wL1+fz7+f/6//sL9QD6IO7w/QIIBQL99woM/fkDAPEB+AD+/Q39AvsIBgD1LQsB8/8F9wX8AP3sCAAK9fkA/PcLBfoJCAMA//wAAAP99gjxAvD3/v0A+vU29P8N+vj7AgT++AcH/vgA/wDw/wT9//j0+fX4Cvz6BwP5/PL4/fIE8uz1/wD4+PkHAPkB8wX7///19wD/I/b5BB/9/v8C9/oL//8B9fsAAwf7LfH9DQPwBfn1/fT4Bv/5Cfv/Bfj8//gEAPX49uz//ez1+PUv8wL+AQDu/ff58/kE/wEHMwb7Ag==',
  shop: 'CAPw//359QkB/vgHA////Qb8/AUI/QADAfkHAv3///wB/QDw+vj9AwX79gP6G/P49fkDDfgI8v8DAv7+AP/1/wL+9/8E/AH7FAj4MgL+9uL/8/3/BAH0/v0HBgT3+/v79fMNAv769foLCwj4/Av//gH6/fsC9vUs+gL3/QD8+v0AAPkD/fkBAPv99AT8AfsC9wL7//r1AgL/9fb9AvYA+/f7B/UI+f/9+/v7DP/89wAD9PwCMPn+AST/BfYEAQEPEQL8/fD29Ab8NAkG/P34AP7xCPQB/vn+BAXx//r+BvgMBwID/fnz/Or/9/4KAPYu/wADBgEBBQUF+P8AKgDzBw==',
  machine: 'APn4/QD49vv8//v+BAYD/An5Afz2/PkECAYFBgMC//kAAPzzBgEFBwn8+v35KAMH+PwGB/MFAwL/AfTy/gXuAib/7O8BAgH/Bvz8K/7r/AXwB/UBAAEBBQbr/wQI8v8U9AH8/vj79fj8EAoF8gL///b9+gj8Av0b9f0F/gb9+/4JBQIB/vUKBv8C/Pn/AfwABQ0F+P399/X6CfsG+vn+Bf8I/P/0+vj8APr7AfD+Cf33/AXzI+8JAh72+/b9Av7z9Af1AwH7Bfr6CP/88/j+9yvyBBADBPb6BAQA+v0BDfsD/wEI//f//AT3AwEpAgcCBvoL8wQE/ff9BfUE+gn2+A==',
  explorer: '9v36+/wD8vz8D/X//QQC/hb9Bvrs//sG/+cD/gEBAAL9A/X3Af7+/wD6/gb6GezxIgIMBvcA+QYF/v//Afj5A/oC9AQA8fn++wD1KgH69AX19/r8Af/3C/8F+fz//fwB+/77C/wF/gf8CgL89AH6AfIB+v73/vQn9ggu/gL+BPcAAf/4A/jy+/r//gL4+fUD+Qj4CQHxCgH89PX9/Pj8+/P///0C+gQA9AH+/wf9+fr1/QAAIvID/CL8Af8J8vELCQYC9PEE9AP/AwDzCPv5AvXvAfb4BPrz/vb/Bvv3AfgFAP8A/fgE/O4C8/8AMfb9/fzv/PT19QEE+fX9ASz9+g==',
  diamond: '9AH1/fz39foA+//89vQB/f/4/fX5/wEK+P0E8/gG/gP/9gH9+PcG8/f7+O/yKAT7DgH19v74/QHxAf3xAPz/NATy+/b0+f74BgP7MgQEAgUD8fgACP7yAQH3+QT2Bv7A/fv+8fsx+f74+fr8+frvq/cA+fz0+wMsAQQ+/fz8Afvz9fzuBP/6BPz6+wH7BwL2+/X7BPP7//z6APoLAAf6DgLy9/QHBwL9/PgD+AEL/vb9AQHyJ/z5BSX19gL88/H9/v398v75BQPxJwb8/P3+7ADz9fv76wL+CP/8/wHpAQH+//v7Afz1/PUD9/gF//86/vz68vr69fcH/AL0Jv78QA==',
  combat: '/PXMK+4ZB+bQ0ODm/v8Dvuj6FOUm6gL8BTjyC9QN9tbq2OLp2RDW7yHmELvm2O71+jsIEhz4DucW6+dY6uEN0PLe5d7JIKzFxxUBC74M0m3oEP7mAvLbBuC/4O0q3t/c7RAm9zUS9/LgMNGm+gEN5ff1Biv82AHnF/kf++y99f0s8BTzOwLtByC9+Bjq9+rv7AneuRvf4+7m+RgA+Avw1t695iUV/fIA9Vb6ZODRA84W3ffdzcHWOdDT+7XpCPzjsg4eCcEKCAHg5/bxASHv3bW14xH/2vsBDuf2EPrZ8wPiEc7n+9wUE+jhHwm08/H3FBLVA/X30fPW/AD9yiMD9w=='
});

const MOB_NUM_FEATURES = 8;
const MOB_NUM_ACTIONS = 8;
const MOB_WEIGHT_BYTES = MOB_NUM_FEATURES * MOB_NUM_ACTIONS; // 64 bytes

const MOB_FEATURE_NAMES = [
  'bias',
  'is_ranged',
  'ranged_too_close',
  'ranged_optimal_los',
  'melee_in_range',
  'target_far',
  'target_out_of_reach',
  'target_geared'
];

const MOB_MICRO_SKILLS = [
  'mob_wait',
  'mob_advance',
  'mob_retreat',
  'mob_strafe',
  'mob_melee_strike',
  'mob_ranged_shot',
  'mob_leap_lunge',
  'mob_brace_cover'
];

const PREBAKED_MOB_POLICIES = Object.freeze({
  combat: 'zf3tvN7/8OgC1v32BAEC49sPLd0KwsDz6fz59LIz/+Dd5/nd/+jo/AXd6Ou15/8Q5szV4UM/wucNN+Tp5N/R+w=='
});

/**
 * 8 orthogonal human biometric personas for LeafSleuth (`REQ-SLEUTH-BIO-002`..`005` / `leafsleuth-ADR-002`):
 * - `mouse_gcd_deg`: pairwise absDiff >= 0.030 deg (>= 0.025) and relDiff >= 26% (>= 22%) -> LLR -1.20
 * - `turn_jerk_deg`: pairwise jerkRatio > 0.50 -> LLR -0.55
 * - `click_mean_ms`: pairwise clickDiff > 0.40 -> LLR -0.50, with human CV ~ 0.21 (> 0.08 macro gate)
 * - `hotbar_slots`: 8 edge-disjoint directed 3-cycles in the 9x9 hotbar transition matrix -> cosine = 0.0 -> LLR -0.40
 * - `goal_loop`: 8 edge-disjoint 3-state GameplayState cycles -> Markov cosine = 0.0 -> LLR -0.85
 */
const BOT_PERSONA_PROFILES = Object.freeze([
  Object.freeze({
    idx: 0, id: 'human_0', mouse_gcd_deg: 0.065, turn_jerk_deg: 1.2, click_mean_ms: 85,
    phys_counts_per_tick: 5.2, path_curvature: 0.46, pitch_yaw_anisotropy: 0.22,
    turn_while_running_rate: 0.85, strafe_turn_rate: 0.70, airborne_turn_rate: 0.35,
    hotbar_slots: Object.freeze([0, 1, 2]),
    goal_loop: Object.freeze(['MINING', 'BUILDING', 'CRAFTING']),
    chunk_x: 16, chunk_z: -12
  }),
  Object.freeze({
    idx: 1, id: 'human_1', mouse_gcd_deg: 0.105, turn_jerk_deg: 2.3, click_mean_ms: 140,
    phys_counts_per_tick: 8.4, path_curvature: 0.12, pitch_yaw_anisotropy: 0.78,
    turn_while_running_rate: 0.20, strafe_turn_rate: 0.10, airborne_turn_rate: 0.05,
    hotbar_slots: Object.freeze([3, 4, 5]),
    goal_loop: Object.freeze(['COMBAT', 'CONTAINER_SORT', 'COMMAND_CHAT']),
    chunk_x: 32, chunk_z: -24
  }),
  Object.freeze({
    idx: 2, id: 'human_2', mouse_gcd_deg: 0.165, turn_jerk_deg: 4.3, click_mean_ms: 225,
    phys_counts_per_tick: 12.6, path_curvature: 0.38, pitch_yaw_anisotropy: 0.35,
    turn_while_running_rate: 0.65, strafe_turn_rate: 0.45, airborne_turn_rate: 0.20,
    hotbar_slots: Object.freeze([6, 7, 8]),
    goal_loop: Object.freeze(['BUILDING', 'MINING', 'AFK']),
    chunk_x: 48, chunk_z: -36
  }),
  Object.freeze({
    idx: 3, id: 'human_3', mouse_gcd_deg: 0.255, turn_jerk_deg: 8.0, click_mean_ms: 355,
    phys_counts_per_tick: 17.5, path_curvature: 0.06, pitch_yaw_anisotropy: 0.92,
    turn_while_running_rate: 0.92, strafe_turn_rate: 0.82, airborne_turn_rate: 0.55,
    hotbar_slots: Object.freeze([0, 4, 8]),
    goal_loop: Object.freeze(['CRAFTING', 'COMBAT', 'MINING']),
    chunk_x: 64, chunk_z: -48
  }),
  Object.freeze({
    idx: 4, id: 'human_4', mouse_gcd_deg: 0.375, turn_jerk_deg: 14.5, click_mean_ms: 540,
    phys_counts_per_tick: 24.0, path_curvature: 0.52, pitch_yaw_anisotropy: 0.16,
    turn_while_running_rate: 0.12, strafe_turn_rate: 0.05, airborne_turn_rate: 0.02,
    hotbar_slots: Object.freeze([1, 5, 6]),
    goal_loop: Object.freeze(['CONTAINER_SORT', 'BUILDING', 'COMBAT']),
    chunk_x: 80, chunk_z: -60
  }),
  Object.freeze({
    idx: 5, id: 'human_5', mouse_gcd_deg: 0.082, turn_jerk_deg: 1.7, click_mean_ms: 110,
    phys_counts_per_tick: 6.8, path_curvature: 0.22, pitch_yaw_anisotropy: 0.64,
    turn_while_running_rate: 0.75, strafe_turn_rate: 0.58, airborne_turn_rate: 0.28,
    hotbar_slots: Object.freeze([2, 3, 7]),
    goal_loop: Object.freeze(['COMMAND_CHAT', 'MINING', 'CONTAINER_SORT']),
    chunk_x: 96, chunk_z: -72
  }),
  Object.freeze({
    idx: 6, id: 'human_6', mouse_gcd_deg: 0.132, turn_jerk_deg: 3.2, click_mean_ms: 178,
    phys_counts_per_tick: 10.5, path_curvature: 0.41, pitch_yaw_anisotropy: 0.28,
    turn_while_running_rate: 0.30, strafe_turn_rate: 0.18, airborne_turn_rate: 0.08,
    hotbar_slots: Object.freeze([2, 1, 0]),
    goal_loop: Object.freeze(['AFK', 'CRAFTING', 'BUILDING']),
    chunk_x: 112, chunk_z: -84
  }),
  Object.freeze({
    idx: 7, id: 'human_7', mouse_gcd_deg: 0.205, turn_jerk_deg: 5.8, click_mean_ms: 280,
    phys_counts_per_tick: 15.2, path_curvature: 0.09, pitch_yaw_anisotropy: 0.85,
    turn_while_running_rate: 0.88, strafe_turn_rate: 0.76, airborne_turn_rate: 0.48,
    hotbar_slots: Object.freeze([5, 4, 3]),
    goal_loop: Object.freeze(['COMBAT', 'AFK', 'COMMAND_CHAT']),
    chunk_x: 128, chunk_z: -96
  })
]);

const COPRIME_YAW_MULTS = Object.freeze([2, 3, 5, 3, 5, 2, 7, 3]);
const COPRIME_PITCH_MULTS = Object.freeze([1, 2, 3, 1, 2, 3, 2, 5]);
// Zero-sum percentage offsets yielding sample CV ~ 0.21 (> 0.08 macro threshold) while preserving exact mean
const HUMAN_CADENCE_JITTER_PCT = Object.freeze([-26, 24, -18, 28, -22, 16, -14, 12]);

const SLEUTH_PERSONA_REGISTRY = new Map();
const SLEUTH_PERSONA_LIST = [];

function registerSleuthPersonas(personas) {
  SLEUTH_PERSONA_REGISTRY.clear();
  SLEUTH_PERSONA_LIST.length = 0;
  if (!Array.isArray(personas)) return [];
  personas.forEach((rawP, idx) => {
    if (!rawP || typeof rawP !== 'object') return;
    const p = { ...rawP, idx: Number.isFinite(rawP.idx) ? (rawP.idx | 0) : idx };
    const pid = String(p.id || p.player_name || `sleuth_${idx}`).trim();
    p.id = pid;
    SLEUTH_PERSONA_LIST.push(p);
    SLEUTH_PERSONA_REGISTRY.set(pid.toLowerCase(), p);
    SLEUTH_PERSONA_REGISTRY.set(String(idx), p);
    if (p.account_id) {
      SLEUTH_PERSONA_REGISTRY.set(String(p.account_id).toLowerCase(), p);
    }
  });
  return [...SLEUTH_PERSONA_LIST];
}

function parsePersonaIndex(rawToken, fallbackIdx = 0) {
  if (typeof rawToken === 'number' && Number.isFinite(rawToken)) {
    return Math.abs(Math.trunc(rawToken)) & 7;
  }
  const s = String(rawToken || '').trim().toLowerCase();
  if (!s) return Math.abs(fallbackIdx | 0) & 7;
  const numMatch = s.match(/(\d+)/);
  if (numMatch) return Math.abs(parseInt(numMatch[1], 10)) & 7;
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h = Math.imul(h ^ s.charCodeAt(i), 16777619) >>> 0;
  }
  return h & 7;
}

/**
 * Resolve a bot's biometric persona configuration from `personaSpec` and `botIdx`:
 * - `"distinct"` / `""` (default): independent human persona `BOT_PERSONA_PROFILES[botIdx & 7]`
 * - `"human:<n>"` / `"distinct:<n>"`: explicit human persona `n`
 * - `"shared:<id>"`: shared human persona (e.g. one person multiboxing/tab-switching two accounts)
 * - `"bot:grid"` / `"bot:atan2"` / `"bot:macro"`: synthetic cheat/macro look-solver regimes
 */
function resolveBotPersona(personaSpec, botIdx = 0) {
  const raw = String(personaSpec || 'distinct').trim().toLowerCase();
  const bIdx = Math.abs(botIdx | 0);
  if (raw.startsWith('bot:') || raw === 'axis_grid_snap_bot' || raw === 'trigonometric_atan2_packet_bot' || raw === 'static_fixed_angle_macro') {
    const sub = raw.startsWith('bot:') ? raw.slice(4) : raw;
    if (sub === 'evasive') {
      return {
        idx: 0,
        bot_idx: bIdx,
        id: 'bot_evasive',
        mode: 'bot_evasive',
        expected_solver: 'AXIS_GRID_SNAP_BOT',
        mouse_gcd_deg: 0.15,
        turn_jerk_deg: 6.75,
        click_mean_ms: 120,
        phys_counts_per_tick: 300.0,
        path_curvature: 0.0,
        pitch_yaw_anisotropy: 0.003,
        turn_while_running_rate: 1.0,
        strafe_turn_rate: 0.0,
        airborne_turn_rate: 0.0,
        hotbar_slots: [0, 1],
        goal_loop: ['MINING', 'MINING', 'MINING'],
        chunk_x: 0,
        chunk_z: 0
      };
    }
    if (sub === 'baritone_smooth' || sub === 'humanized_baritone') {
      return {
        idx: 0,
        bot_idx: bIdx,
        id: 'bot_baritone_smooth',
        mode: 'bot_baritone_smooth',
        expected_solver: 'AXIS_GRID_SNAP_BOT',
        mouse_gcd_deg: 0.15,
        turn_jerk_deg: 0.45,
        click_mean_ms: 140,
        phys_counts_per_tick: 75.0,
        path_curvature: 0.01,
        pitch_yaw_anisotropy: 0.013,
        turn_while_running_rate: 1.0,
        strafe_turn_rate: 0.0,
        airborne_turn_rate: 0.0,
        hotbar_slots: [0, 1],
        goal_loop: ['MINING', 'MINING', 'MINING'],
        chunk_x: 0,
        chunk_z: 0
      };
    }
    if (sub === 'ghost_clicker' || sub === 'autoclicker') {
      return {
        idx: 0,
        bot_idx: bIdx,
        id: 'bot_ghost_clicker',
        mode: 'bot_ghost_clicker',
        expected_solver: 'HUMAN_QUANTIZED_GCD',
        mouse_gcd_deg: 0.15,
        turn_jerk_deg: 1.2,
        click_mean_ms: 100,
        phys_counts_per_tick: 5.5,
        path_curvature: 0.18,
        pitch_yaw_anisotropy: 0.42,
        turn_while_running_rate: 0.65,
        strafe_turn_rate: 0.35,
        airborne_turn_rate: 0.20,
        hotbar_slots: [0],
        goal_loop: ['COMBAT', 'COMBAT', 'COMBAT'],
        chunk_x: 0,
        chunk_z: 0
      };
    }
    if (sub === 'afk_fisher' || sub === 'micro_twitch_fisher') {
      return {
        idx: 0,
        bot_idx: bIdx,
        id: 'bot_afk_fisher',
        mode: 'bot_afk_fisher',
        expected_solver: 'STATIC_FIXED_ANGLE_MACRO',
        mouse_gcd_deg: 0.15,
        turn_jerk_deg: 0.15,
        click_mean_ms: 220,
        phys_counts_per_tick: 1.5,
        path_curvature: 0.0,
        pitch_yaw_anisotropy: 0.67,
        turn_while_running_rate: 0.0,
        strafe_turn_rate: 0.0,
        airborne_turn_rate: 0.0,
        hotbar_slots: [0],
        goal_loop: ['AFK', 'AFK', 'AFK'],
        chunk_x: 0,
        chunk_z: 0
      };
    }
    const solver = (sub === 'atan2' || sub === 'trigonometric_atan2_packet_bot')
      ? 'TRIGONOMETRIC_ATAN2_PACKET_BOT'
      : ((sub === 'macro' || sub === 'static_fixed_angle_macro')
        ? 'STATIC_FIXED_ANGLE_MACRO'
        : 'AXIS_GRID_SNAP_BOT');
    const mode = solver === 'TRIGONOMETRIC_ATAN2_PACKET_BOT'
      ? 'bot_atan2'
      : (solver === 'STATIC_FIXED_ANGLE_MACRO' ? 'bot_macro' : 'bot_grid');
    return {
      idx: 0,
      bot_idx: bIdx,
      id: mode,
      mode,
      expected_solver: solver,
      mouse_gcd_deg: 0.0,
      turn_jerk_deg: 0.0,
      click_mean_ms: 150,
      phys_counts_per_tick: 0.0,
      path_curvature: 0.0,
      pitch_yaw_anisotropy: 0.0,
      turn_while_running_rate: 0.0,
      strafe_turn_rate: 0.0,
      airborne_turn_rate: 0.0,
      hotbar_slots: [0, 1, 0],
      goal_loop: ['MINING', 'MINING', 'MINING'],
      chunk_x: 0,
      chunk_z: 0
    };
  }
  if (raw === 'default_wrist') {
    return {
      idx: 0,
      bot_idx: bIdx,
      id: 'default_wrist',
      mode: 'default_wrist',
      expected_solver: 'HUMAN_QUANTIZED_GCD',
      mouse_gcd_deg: 0.15,
      turn_jerk_deg: 1.8,
      click_mean_ms: 160,
      phys_counts_per_tick: 3.8,
      path_curvature: 0.48,
      pitch_yaw_anisotropy: 0.12,
      turn_while_running_rate: 0.08,
      strafe_turn_rate: 0.05,
      airborne_turn_rate: 0.02,
      hotbar_slots: [0, 1],
      goal_loop: ['MINING', 'MINING', 'MINING'],
      chunk_x: 10,
      chunk_z: 10
    };
  }
  if (raw === 'default_arm') {
    return {
      idx: 1,
      bot_idx: bIdx,
      id: 'default_arm',
      mode: 'default_arm',
      expected_solver: 'HUMAN_QUANTIZED_GCD',
      mouse_gcd_deg: 0.15,
      turn_jerk_deg: 1.8,
      click_mean_ms: 160,
      phys_counts_per_tick: 21.2,
      path_curvature: 0.04,
      pitch_yaw_anisotropy: 0.96,
      turn_while_running_rate: 0.92,
      strafe_turn_rate: 0.78,
      airborne_turn_rate: 0.50,
      hotbar_slots: [0, 1],
      goal_loop: ['MINING', 'MINING', 'MINING'],
      chunk_x: -40,
      chunk_z: 40
    };
  }
  if (raw === 'slider_alt' || raw.startsWith('slider_alt:')) {
    const pctToken = raw.includes(':') ? parseFloat(raw.split(':')[1]) : 65.0;
    const sensPct = Number.isFinite(pctToken) ? Math.max(10.0, Math.min(200.0, pctToken)) : 65.0;
    const f = 0.6 * (sensPct / 200.0) + 0.2;
    const gcd = Number((1.2 * f * f * f).toFixed(4));
    const jerkDeg = Number((12.0 * gcd).toFixed(4));
    return {
      idx: 1,
      bot_idx: bIdx,
      id: `slider_alt_${Math.round(sensPct)}`,
      mode: 'slider_alt',
      expected_solver: 'HUMAN_QUANTIZED_GCD',
      mouse_gcd_deg: gcd,
      turn_jerk_deg: jerkDeg,
      click_mean_ms: 160,
      phys_counts_per_tick: 21.2,
      path_curvature: 0.04,
      pitch_yaw_anisotropy: 0.96,
      turn_while_running_rate: 0.92,
      strafe_turn_rate: 0.78,
      airborne_turn_rate: 0.50,
      hotbar_slots: [0, 1],
      goal_loop: ['MINING', 'MINING', 'MINING'],
      chunk_x: -40,
      chunk_z: 40
    };
  }
  if (raw === 'shared' || raw.startsWith('shared:')) {
    const token = raw.includes(':') ? raw.split(':')[1] : '0';
    const pIdx = parsePersonaIndex(token, 0);
    const base = BOT_PERSONA_PROFILES[pIdx];
    return {
      ...base,
      bot_idx: bIdx,
      id: `shared_${pIdx}`,
      mode: 'shared',
      expected_solver: 'HUMAN_QUANTIZED_GCD',
      hotbar_slots: [...base.hotbar_slots],
      goal_loop: [...base.goal_loop]
    };
  }
  if (raw === 'sleuth' || raw.startsWith('sleuth:')) {
    const parts = raw.split(':');
    const key = (parts.length >= 2 && parts[1].trim()) ? parts[1].trim().toLowerCase() : String(bIdx);
    let base = SLEUTH_PERSONA_REGISTRY.get(key);
    if (!base && SLEUTH_PERSONA_LIST.length > 0) {
      base = SLEUTH_PERSONA_LIST[parsePersonaIndex(key, bIdx) % SLEUTH_PERSONA_LIST.length];
    }
    if (!base) {
      base = BOT_PERSONA_PROFILES[parsePersonaIndex(key, bIdx)];
    }
    const pIdx = ((base.idx !== undefined ? base.idx : bIdx) | 0) & 7;
    let deltaPct = 0;
    let hasMutation = false;
    if (parts.length >= 3 && parts[2].trim()) {
      const parsedDelta = parseInt(parts[2], 10);
      if (Number.isFinite(parsedDelta)) {
        deltaPct = Math.max(0, Math.min(100, parsedDelta));
        hasMutation = true;
      }
    }
    const subMode = parts.length >= 4 ? parts[3].trim().toLowerCase() : (hasMutation ? 'relog' : 'distinct');
    const altBase = BOT_PERSONA_PROFILES[(pIdx + 3) & 7];
    const sign = ((pIdx + bIdx) & 1) === 0 ? 1 : -1;
    const frac = deltaPct / 100.0;
    const heavy = deltaPct >= 70;
    let modeName = 'distinct';
    if (subMode === 'sim') modeName = 'mutated_sim';
    else if (subMode === 'relog' || hasMutation) modeName = 'mutated_relog';
    else if (subMode === 'shared') modeName = 'shared';
    const baseGcd = Number(base.mouse_gcd_deg || 0.15);
    const baseJerk = Number(base.turn_jerk_deg || 1.8);
    const baseClick = Number(base.click_mean_ms || 160);
    const basePhys = Number(base.phys_counts_per_tick || 8.0);
    const baseCurv = Number(base.path_curvature || 0.25);
    const baseAniso = Number(base.pitch_yaw_anisotropy || 0.45);
    const baseRun = Number(base.turn_while_running_rate || 0.60);
    const baseStrafe = Number(base.strafe_turn_rate || 0.40);
    const baseAir = Number(base.airborne_turn_rate || 0.20);
    return {
      ...base,
      idx: pIdx,
      bot_idx: bIdx,
      id: String(base.id || `sleuth_${pIdx}`),
      mode: modeName,
      mutation_pct: deltaPct,
      expected_solver: String(base.expected_solver || 'HUMAN_QUANTIZED_GCD'),
      mouse_gcd_deg: heavy ? altBase.mouse_gcd_deg : baseGcd,
      turn_jerk_deg: Number(Math.max(0.4, baseJerk * (1.0 + sign * frac * 0.65)).toFixed(4)),
      click_mean_ms: Math.max(45, Math.round(baseClick * (1.0 - sign * frac * 0.55))),
      phys_counts_per_tick: Number(Math.max(1.5, basePhys * (1.0 + sign * frac * 0.75)).toFixed(2)),
      path_curvature: Number(Math.max(0.02, Math.min(0.85, baseCurv + sign * frac * 0.35)).toFixed(4)),
      pitch_yaw_anisotropy: Number(Math.max(0.05, Math.min(1.50, baseAniso - sign * frac * 0.60)).toFixed(4)),
      turn_while_running_rate: Number(Math.max(0.02, Math.min(0.98, baseRun - sign * frac * 0.55)).toFixed(4)),
      strafe_turn_rate: Number(Math.max(0.02, Math.min(0.95, baseStrafe - sign * frac * 0.50)).toFixed(4)),
      airborne_turn_rate: Number(Math.max(0.01, Math.min(0.90, baseAir - sign * frac * 0.35)).toFixed(4)),
      hotbar_slots: [...(heavy ? altBase.hotbar_slots : (base.hotbar_slots || [0, 1, 2]))],
      goal_loop: [...(heavy ? altBase.goal_loop : (base.goal_loop || ['MINING', 'BUILDING', 'CRAFTING']))],
      chunk_x: (heavy ? altBase.chunk_x : (base.chunk_x !== undefined ? base.chunk_x : 16)) | 0,
      chunk_z: (heavy ? altBase.chunk_z : (base.chunk_z !== undefined ? base.chunk_z : -12)) | 0
    };
  }
  if (raw === 'mutated' || raw.startsWith('mutated:')) {
    const parts = raw.split(':');
    const pIdx = parsePersonaIndex(parts[1] || '0', 0);
    const rawDelta = parts.length >= 3 ? parseInt(parts[2], 10) : 15;
    const deltaPct = Number.isFinite(rawDelta) ? Math.max(0, Math.min(100, rawDelta)) : 15;
    const subMode = (parts.length >= 4 && parts[3] === 'sim') ? 'sim' : 'relog';
    const base = BOT_PERSONA_PROFILES[pIdx];
    const altBase = BOT_PERSONA_PROFILES[(pIdx + 3) & 7];
    const sign = ((pIdx + bIdx) & 1) === 0 ? 1 : -1;
    const frac = deltaPct / 100.0;
    const heavy = deltaPct >= 70;
    return {
      ...base,
      idx: pIdx,
      bot_idx: bIdx,
      id: `mutated_${pIdx}_${deltaPct}_${subMode}`,
      mode: subMode === 'sim' ? 'mutated_sim' : 'mutated_relog',
      mutation_pct: deltaPct,
      expected_solver: 'HUMAN_QUANTIZED_GCD',
      mouse_gcd_deg: heavy ? altBase.mouse_gcd_deg : base.mouse_gcd_deg,
      turn_jerk_deg: Number(Math.max(0.4, base.turn_jerk_deg * (1.0 + sign * frac * 0.65)).toFixed(4)),
      click_mean_ms: Math.max(45, Math.round(base.click_mean_ms * (1.0 - sign * frac * 0.55))),
      phys_counts_per_tick: Number(Math.max(1.5, base.phys_counts_per_tick * (1.0 + sign * frac * 0.75)).toFixed(2)),
      path_curvature: Number(Math.max(0.02, Math.min(0.85, base.path_curvature + sign * frac * 0.35)).toFixed(4)),
      pitch_yaw_anisotropy: Number(Math.max(0.05, Math.min(1.50, base.pitch_yaw_anisotropy - sign * frac * 0.60)).toFixed(4)),
      turn_while_running_rate: Number(Math.max(0.02, Math.min(0.98, base.turn_while_running_rate - sign * frac * 0.55)).toFixed(4)),
      strafe_turn_rate: Number(Math.max(0.02, Math.min(0.95, base.strafe_turn_rate - sign * frac * 0.50)).toFixed(4)),
      airborne_turn_rate: Number(Math.max(0.01, Math.min(0.90, base.airborne_turn_rate - sign * frac * 0.35)).toFixed(4)),
      hotbar_slots: heavy ? [...altBase.hotbar_slots] : [...base.hotbar_slots],
      goal_loop: heavy ? [...altBase.goal_loop] : [...base.goal_loop],
      chunk_x: heavy ? altBase.chunk_x : base.chunk_x,
      chunk_z: heavy ? altBase.chunk_z : base.chunk_z
    };
  }
  const token = raw.includes(':') ? raw.split(':')[1] : '';
  const pIdx = token ? parsePersonaIndex(token, bIdx) : (bIdx & 7);
  const base = BOT_PERSONA_PROFILES[pIdx];
  return {
    ...base,
    bot_idx: bIdx,
    id: base.id,
    mode: 'distinct',
    expected_solver: 'HUMAN_QUANTIZED_GCD',
    hotbar_slots: [...base.hotbar_slots],
    goal_loop: [...base.goal_loop]
  };
}

function ensureBiometricState(bs, personaSpec) {
  const ts = ensureTaskState(bs);
  const bIdx = (bs && typeof bs.botIdx === 'number') ? (bs.botIdx | 0) : 0;
  if (!ts.biometrics || (personaSpec && ts.biometrics.raw_spec !== personaSpec)) {
    const persona = resolveBotPersona(personaSpec || (bs && bs.persona) || 'distinct', bIdx);
    const transitions = Array.from({ length: 9 }, () => [0, 0, 0, 0, 0, 0, 0, 0, 0]);
    ts.biometrics = {
      raw_spec: personaSpec || (bs && bs.persona) || 'distinct',
      persona,
      clock_ms: 10000,
      yaw_deg: 0.0,
      pitch_deg: 0.0,
      current_slot: persona.hotbar_slots[0] | 0,
      hotbar_transitions: transitions,
      slot_switches: [],
      camera_deltas: [],
      click_timestamps_ms: [],
      click_intervals_ms: [],
      markov_states: [],
      visited_chunks: [],
      accessed_containers: [],
      activity_buckets: {}
    };
  }
  return ts.biometrics;
}

/**
 * Emit one step's biometric camera micro-kinematics, hotbar slot transition, click interval
 * cadence, and Markov goal state into both `ts.biometrics` and the live Mineflayer socket.
 */
function recordBiometricStep(bs, bot, skill, stepIdx) {
  const bio = ensureBiometricState(bs);
  const p = bio.persona;
  const bSalt = (p.bot_idx | 0) & 7;
  const sIdx = stepIdx | 0;

  // 1. Inter-action click cadence with human CV (~0.21), period-2 alternating macro, or bot metronome (CV = 0.0)
  let intervalMs = p.click_mean_ms | 0;
  if (p.mode === 'bot_evasive') {
    intervalMs = (sIdx & 1) === 0 ? 100 : 140;
  } else if (p.mode === 'bot_baritone_smooth') {
    const baritoneJitter = [-14, 16, -10, 12, -12, 10, -8, 6];
    intervalMs = 140 + baritoneJitter[sIdx & 7];
  } else if (p.mode === 'bot_ghost_clicker') {
    const ghostJitter = [-13, 11, -9, 14, -11, 8, -7, 12];
    intervalMs = 100 + ghostJitter[sIdx & 7];
  } else if (p.mode === 'bot_afk_fisher') {
    const fisherJitter = [-18, 22, -14, 16, -20, 12, -10, 14];
    intervalMs = 220 + fisherJitter[sIdx & 7];
  } else if (p.mode === 'default_wrist') {
    const erratic = [-38, 42, -32, 46, -40, 36, -28, 14];
    intervalMs = Math.max(20, Math.round((p.click_mean_ms * (100 + erratic[sIdx & 7])) / 100));
  } else if (p.mode === 'default_arm' || p.mode === 'slider_alt') {
    const steady = [-12, 14, -10, 12, -14, 10, -8, 8];
    intervalMs = Math.max(20, Math.round((p.click_mean_ms * (100 + steady[sIdx & 7])) / 100));
  } else if (p.mode === 'distinct' || p.mode === 'shared' || p.mode === 'mutated_relog' || p.mode === 'mutated_sim') {
    const pct = HUMAN_CADENCE_JITTER_PCT[(sIdx + bSalt) & 7];
    intervalMs = Math.max(20, Math.round((p.click_mean_ms * (100 + pct)) / 100));
  }
  if (sIdx > 0) {
    bio.click_intervals_ms.push(intervalMs);
  }
  bio.clock_ms = (bio.clock_ms + intervalMs) | 0;
  const clickTs = bio.clock_ms;
  bio.click_timestamps_ms.push(clickTs);

  // 2. Hotbar slot muscle-memory cycle (orthogonal 3-cycles across personas)
  const fromSlot = bio.current_slot | 0;
  const toSlot = p.hotbar_slots[(sIdx + 1) % p.hotbar_slots.length] | 0;
  if (fromSlot !== toSlot && fromSlot >= 0 && fromSlot < 9 && toSlot >= 0 && toSlot < 9) {
    bio.hotbar_transitions[fromSlot][toSlot] = (bio.hotbar_transitions[fromSlot][toSlot] + 1) | 0;
    bio.slot_switches.push({ t_ms: clickTs, from_slot: fromSlot, to_slot: toSlot });
    bio.current_slot = toSlot;
    if (bot && bot._client) {
      try { bot._client.write('held_item_slot', { slotId: toSlot }); } catch (e) { /* ignore */ }
    }
  }

  // 3. Camera yaw/pitch micro-kinematics (2 sub-steps >= 25ms apart to pass 5ms packet-burst filter)
  const rotTs0 = Math.max(10005, clickTs - 25);
  const rotTs1 = clickTs;
  if (p.mode === 'bot_grid') {
    const dyaw0 = (sIdx & 1) === 0 ? 45.0 : 90.0;
    const dyaw1 = (sIdx & 1) === 0 ? 90.0 : 45.0;
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: 0.0 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: 0.0 });
    bio.yaw_deg = (bio.yaw_deg + dyaw0 + dyaw1) % 360.0;
  } else if (p.mode === 'bot_evasive') {
    const dyaw0 = (sIdx & 1) === 0 ? 44.85 : 89.85;
    const dyaw1 = (sIdx & 1) === 0 ? 89.85 : 44.85;
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: 0.15 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: 0.15 });
    bio.yaw_deg = (bio.yaw_deg + dyaw0 + dyaw1) % 360.0;
  } else if (p.mode === 'bot_baritone_smooth') {
    const dir = ((sIdx >> 1) & 1) === 0 ? 1.0 : -1.0;
    const dyaw0 = Number((dir * ((sIdx & 1) === 0 ? 11.10 : 11.25)).toFixed(6));
    const dyaw1 = Number((dir * ((sIdx & 1) === 0 ? 11.40 : 11.25)).toFixed(6));
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: 0.15 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: -0.15 });
    bio.yaw_deg = (bio.yaw_deg + dyaw0 + dyaw1) % 360.0;
  } else if (p.mode === 'bot_afk_fisher') {
    const dyaw0 = 0.30;
    const dpitch0 = 0.15;
    const dyaw1 = -0.30;
    const dpitch1 = -0.15;
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: dpitch0 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: dpitch1 });
  } else if (p.mode === 'bot_atan2') {
    const dyaw0 = Number((Math.sqrt(sIdx + 1.37) * 0.4193).toFixed(6));
    const dpitch0 = Number((Math.sin(sIdx + 0.73) * 0.5317).toFixed(6));
    const dyaw1 = Number((Math.sqrt(sIdx + 2.11) * 0.3719).toFixed(6));
    const dpitch1 = Number((Math.cos(sIdx + 1.19) * 0.4871).toFixed(6));
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: dpitch0 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: dpitch1 });
    bio.yaw_deg = (bio.yaw_deg + dyaw0 + dyaw1) % 360.0;
  } else if (p.mode === 'distinct' || p.mode === 'shared' || p.mode === 'default_wrist' || p.mode === 'default_arm' || p.mode === 'slider_alt' || p.mode === 'mutated_relog' || p.mode === 'mutated_sim' || p.mode === 'bot_ghost_clicker') {
    const g = p.mouse_gcd_deg;
    const ky = COPRIME_YAW_MULTS[(sIdx + bSalt) & 7];
    const kp = COPRIME_PITCH_MULTS[(sIdx + bSalt) & 7];
    const dyaw0 = Number((ky * g).toFixed(6));
    const dpitch0 = Number((kp * g).toFixed(6));
    const speed0 = Math.hypot(dyaw0, dpitch0);

    const kp1 = (sIdx & 1) === 0 ? 1 : 2;
    const dpitch1 = Number((kp1 * g).toFixed(6));
    const targetSpeed1 = speed0 + p.turn_jerk_deg;
    const rawYaw1 = Math.sqrt(Math.max(g * g, targetSpeed1 * targetSpeed1 - dpitch1 * dpitch1));
    let mult1 = Math.max(3, Math.round(rawYaw1 / g));
    let dyaw1 = Number((mult1 * g).toFixed(6));
    const mod45 = dyaw1 % 45.0;
    if (dyaw1 >= 5.0 && (mod45 < 0.25 || (45.0 - mod45) < 0.25)) {
      mult1 += 1;
      dyaw1 = Number((mult1 * g).toFixed(6));
    }
    bio.camera_deltas.push({ t_ms: rotTs0, dyaw: dyaw0, dpitch: dpitch0 });
    bio.camera_deltas.push({ t_ms: rotTs1, dyaw: dyaw1, dpitch: dpitch1 });
    bio.yaw_deg = (bio.yaw_deg + dyaw0 + dyaw1) % 360.0;
    bio.pitch_deg = Math.max(-60.0, Math.min(60.0, bio.pitch_deg + ((sIdx & 1) ? dpitch0 : -dpitch0)));
    if (bot && bot._client) {
      try {
        bot._client.write('look', {
          yaw: bio.yaw_deg,
          pitch: bio.pitch_deg,
          onGround: true,
          flags: { onGround: true, hasHorizontalCollision: false }
        });
      } catch (e) { /* protocol variation */ }
    }
  }

  // 4. Gameplay Goal Markov state transition & spatial sector
  const gState = p.goal_loop[sIdx % p.goal_loop.length];
  bio.markov_states.push(gState);
  const cx = (p.chunk_x + (sIdx & 1)) | 0;
  const cz = p.chunk_z | 0;
  const chunkKey = `world:${cx}:${cz}`;
  if (!bio.visited_chunks.includes(chunkKey)) bio.visited_chunks.push(chunkKey);
  const contKey = `world:${cx * 16 + (sIdx & 1)}:64:${cz * 16}`;
  if (!bio.accessed_containers.includes(contKey)) bio.accessed_containers.push(contKey);

  // 5. Coarse 2000ms session activity bucket impulse for SimultaneousActivityCorrelator
  const bucketIdx = sIdx % 10;
  if (p.mode === 'shared' || p.mode === 'slider_alt') {
    // Shared human operator tab-switches/alternates between accounts across 2-second buckets
    if ((bucketIdx & 1) === ((p.bot_idx | 0) & 1)) {
      const bKey = String(5 + bucketIdx);
      bio.activity_buckets[bKey] = Number(((bio.activity_buckets[bKey] || 0.0) + 1.5).toFixed(4));
    }
  } else if (p.mode === 'default_arm') {
    if ((bucketIdx & 1) === ((p.bot_idx | 0) & 1)) {
      const bKey = String(5 + bucketIdx);
      bio.activity_buckets[bKey] = Number(((bio.activity_buckets[bKey] || 0.0) + 1.5).toFixed(4));
    }
  } else if (p.mode === 'mutated_relog') {
    const bKey = String(5 + bucketIdx);
    const activeHalf = (p.bot_idx | 0) === 0 ? (bucketIdx < 5) : (bucketIdx >= 5);
    if (activeHalf) {
      bio.activity_buckets[bKey] = Number(((bio.activity_buckets[bKey] || 0.0) + 1.5).toFixed(4));
    } else if (!(bKey in bio.activity_buckets)) {
      bio.activity_buckets[bKey] = 0.0;
    }
  } else if (p.mode === 'mutated_sim') {
    const bKey = String(5 + bucketIdx);
    const mask = (((p.idx + (p.bot_idx | 0) + 1) & 7) + 1);
    let wave = 1.2;
    if (bucketIdx < 8) {
      let bits = bucketIdx & mask;
      bits = bits - ((bits >> 1) & 0x55);
      bits = (bits & 0x33) + ((bits >> 2) & 0x33);
      wave = (bits & 1) === 0 ? 1.6 : 0.8;
    }
    bio.activity_buckets[bKey] = wave;
  } else if (p.mode === 'default_wrist') {
    // Non-overlapping play hours for default_wrist vs default_arm
    const bKey = String(50 + bucketIdx);
    bio.activity_buckets[bKey] = 1.4;
  } else {
    // Distinct humans act simultaneously in overlapping 2s buckets with Walsh-Hadamard orthogonal waves (r = 0.0)
    const bKey = String(5 + bucketIdx);
    const mask = ((p.idx & 7) + 1);
    let wave = 1.2;
    if (bucketIdx < 8) {
      let bits = bucketIdx & mask;
      bits = bits - ((bits >> 1) & 0x55);
      bits = (bits & 0x33) + ((bits >> 2) & 0x33);
      wave = (bits & 1) === 0 ? 1.6 : 0.8;
    }
    bio.activity_buckets[bKey] = wave;
  }
  return intervalMs;
}

function buildBiometricSummary(bs) {
  const bio = ensureBiometricState(bs);
  const p = bio.persona;
  const intervals = bio.click_intervals_ms;
  let clickMean = p.click_mean_ms;
  let clickCv = 0.0;
  let effPeriodicCv = 0.0;
  if (intervals.length >= 2) {
    clickMean = intervals.reduce((a, b) => a + b, 0) / intervals.length;
    const variance = intervals.reduce((acc, v) => acc + (v - clickMean) * (v - clickMean), 0) / (intervals.length - 1);
    clickCv = clickMean > 1.0 ? Math.sqrt(variance) / clickMean : 0.0;
    effPeriodicCv = clickCv;
    if (intervals.length >= 6 && clickMean > 1.0) {
      let sumLag1 = 0.0;
      for (let k = 1; k < intervals.length; k++) sumLag1 += Math.abs(intervals[k] - intervals[k - 1]);
      const lag1Ratio = (sumLag1 / (intervals.length - 1)) / clickMean;
      const minIv = Math.min(...intervals);
      const maxIv = Math.max(...intervals);
      if (
        intervals.length >= 10 &&
        bio.slot_switches.length <= 1 &&
        clickMean >= 35.0 &&
        clickMean <= 145.0 &&
        clickCv >= 0.025 &&
        clickCv <= 0.175 &&
        minIv > 1.0 &&
        (maxIv / minIv) <= 1.85 &&
        (maxIv / clickMean) <= 1.35 &&
        lag1Ratio >= 0.035
      ) {
        effPeriodicCv = Math.min(effPeriodicCv, 0.045);
      } else if (lag1Ratio >= 0.08) {
        for (let lag = 2; lag <= 3; lag++) {
          if (intervals.length > lag + 2) {
            let sumLag = 0.0;
            for (let k = lag; k < intervals.length; k++) sumLag += Math.abs(intervals[k] - intervals[k - lag]);
            const lagRatio = (sumLag / (intervals.length - lag)) / clickMean;
            if (lagRatio <= 0.04) effPeriodicCv = Math.min(effPeriodicCv, lagRatio);
          }
        }
      }
    }
  }
  let bestFrom = -1;
  let bestTo = -1;
  let bestCnt = 0;
  for (let i = 0; i < 9; i++) {
    for (let j = 0; j < 9; j++) {
      if (bio.hotbar_transitions[i][j] > bestCnt) {
        bestCnt = bio.hotbar_transitions[i][j];
        bestFrom = i;
        bestTo = j;
      }
    }
  }
  const domHotbar = bestCnt > 0 ? `${bestFrom}->${bestTo}` : 'NONE';
  const domGoal = p.goal_loop.length >= 2 ? `${p.goal_loop[0]}->${p.goal_loop[1]}` : 'NONE';
  return {
    persona_id: p.id,
    persona_mode: p.mode,
    persona_idx: p.idx,
    expected_solver: p.expected_solver,
    mouse_gcd_deg: p.mouse_gcd_deg,
    turn_jerk_deg: p.turn_jerk_deg,
    click_mean_ms: Number(clickMean.toFixed(2)),
    click_cv: Number(clickCv.toFixed(4)),
    effective_periodic_cv: Number(effPeriodicCv.toFixed(4)),
    phys_counts_per_tick: Number((p.phys_counts_per_tick || 0).toFixed(2)),
    path_curvature: Number((p.path_curvature || 0).toFixed(4)),
    pitch_yaw_anisotropy: Number((p.pitch_yaw_anisotropy || 0).toFixed(4)),
    turn_while_running_rate: Number((p.turn_while_running_rate || 0).toFixed(4)),
    strafe_turn_rate: Number((p.strafe_turn_rate || 0).toFixed(4)),
    airborne_turn_rate: Number((p.airborne_turn_rate || 0).toFixed(4)),
    hotbar_slots: [...p.hotbar_slots],
    dominant_hotbar_cycle: domHotbar,
    hotbar_transitions: bio.hotbar_transitions.map((row) => [...row]),
    slot_switches: bio.slot_switches.slice(0, 64),
    camera_deltas: bio.camera_deltas.slice(0, 64),
    click_timestamps_ms: bio.click_timestamps_ms.slice(0, 64),
    markov_states: bio.markov_states.slice(0, 64),
    dominant_state_loop: domGoal,
    visited_chunks: [...bio.visited_chunks],
    accessed_containers: [...bio.accessed_containers],
    activity_buckets: { ...bio.activity_buckets }
  };
}

/**
 * Evaluate two bot `task_summary` records using LeafSleuth's 4-pillar Bayesian identity math
 * (`MotorKinematicsProfiler` + `GoalMarkovProfiler` + `SimultaneousActivityCorrelator`).
 */
function evaluateSleuthIdentityPair(summaryA, summaryB, sharedIp = true) {
  const bioA = (summaryA && summaryA.biometric_scan) || summaryA || {};
  const bioB = (summaryB && summaryB.biometric_scan) || summaryB || {};
  let motorLlr = 0.0;
  const gcdA = Number(bioA.mouse_gcd_deg || 0);
  const gcdB = Number(bioB.mouse_gcd_deg || 0);
  let gcdMatched = false;
  let gcdDiverged = false;
  if (gcdA > 0 && gcdB > 0) {
    const absDiff = Math.abs(gcdA - gcdB);
    const relDiff = absDiff / Math.max(0.01, 0.5 * (gcdA + gcdB));
    if (absDiff <= 0.008 || relDiff <= 0.08) {
      const vanillaDefault = Math.abs(0.5 * (gcdA + gcdB) - 0.15) <= 0.004;
      motorLlr += vanillaDefault ? 0.65 : 1.25;
      gcdMatched = true;
    } else if (absDiff >= 0.025 && relDiff >= 0.22) {
      motorLlr -= 1.20;
      gcdDiverged = true;
    }
  }
  const solverA = String(bioA.expected_solver || 'HUMAN_QUANTIZED_GCD');
  const solverB = String(bioB.expected_solver || 'HUMAN_QUANTIZED_GCD');
  if (!gcdMatched && solverA === solverB && solverA !== 'HUMAN_QUANTIZED_GCD' && solverA !== 'INSUFFICIENT_DATA') {
    motorLlr += 1.15;
  }

  // Physical mouse counts/tick, 2D path curvature, pitch/yaw anisotropy, and turning-while-running locomotion
  const physA = Number(bioA.phys_counts_per_tick || 0);
  const physB = Number(bioB.phys_counts_per_tick || 0);
  let physMatched = false;
  if (physA > 0 && physB > 0) {
    const physDiff = Math.abs(physA - physB) / Math.max(1.5, 0.5 * (physA + physB));
    if (physDiff >= 0.55 && Math.abs(physA - physB) >= 3.5) motorLlr -= 0.70;
    else if (physDiff <= 0.18) physMatched = true;
  }
  const curvA = Number(bioA.path_curvature || 0);
  const curvB = Number(bioB.path_curvature || 0);
  let curvMatched = false;
  if (curvA > 0 || curvB > 0) {
    const curvDiff = Math.abs(curvA - curvB);
    if (curvDiff >= 0.22) motorLlr -= 0.75;
    else if (curvDiff <= 0.07) curvMatched = true;
  }
  const anisoA = Number(bioA.pitch_yaw_anisotropy || 0);
  const anisoB = Number(bioB.pitch_yaw_anisotropy || 0);
  if (anisoA > 0 && anisoB > 0) {
    const anisoAbs = Math.abs(anisoA - anisoB);
    const anisoRel = anisoAbs / Math.max(0.15, 0.5 * (anisoA + anisoB));
    if (anisoRel >= 0.75 && anisoAbs >= 0.35) motorLlr -= 0.95;
    else if (anisoRel <= 0.18) motorLlr += 0.20;
  }
  const runA = Number(bioA.turn_while_running_rate || 0);
  const runB = Number(bioB.turn_while_running_rate || 0);
  const strafeA = Number(bioA.strafe_turn_rate || 0);
  const strafeB = Number(bioB.strafe_turn_rate || 0);
  const airA = Number(bioA.airborne_turn_rate || 0);
  const airB = Number(bioB.airborne_turn_rate || 0);
  let locoMatched = false;
  if (runA > 0 || runB > 0) {
    const runDiff = Math.abs(runA - runB);
    const strafeDiff = Math.abs(strafeA - strafeB);
    const airDiff = Math.abs(airA - airB);
    const meanLocoDiff = (runDiff + strafeDiff + airDiff) / 3.0;
    if (meanLocoDiff >= 0.30 || runDiff >= 0.45 || strafeDiff >= 0.45) motorLlr -= 0.85;
    else if (meanLocoDiff <= 0.12) {
      motorLlr += 0.40;
      locoMatched = true;
    }
  }
  if (gcdDiverged && physMatched && curvMatched && locoMatched) {
    motorLlr += 1.75;
  }

  const jerkA = Number(bioA.turn_jerk_deg || 0);
  const jerkB = Number(bioB.turn_jerk_deg || 0);
  const cmpJerkA = (gcdDiverged && gcdA > 0) ? (jerkA / gcdA) : jerkA;
  const cmpJerkB = (gcdDiverged && gcdB > 0) ? (jerkB / gcdB) : jerkB;
  if (cmpJerkA > 0 && cmpJerkB > 0) {
    const jerkRatio = Math.abs(cmpJerkA - cmpJerkB) / Math.max(0.5, 0.5 * (cmpJerkA + cmpJerkB));
    if (jerkRatio < 0.22) motorLlr += 0.45;
    else if (jerkRatio > 0.50) motorLlr -= 0.55;
  }
  const clickA = Number(bioA.click_mean_ms || 0);
  const clickB = Number(bioB.click_mean_ms || 0);
  if (clickA > 0 && clickB > 0) {
    const clickDiff = Math.abs(clickA - clickB) / Math.max(25.0, 0.5 * (clickA + clickB));
    if (clickDiff < 0.18) motorLlr += 0.45;
    else if (clickDiff > 0.40) motorLlr -= 0.50;
  }
  const cvA = Number(bioA.effective_periodic_cv ?? bioA.click_cv ?? 0);
  const cvB = Number(bioB.effective_periodic_cv ?? bioB.click_cv ?? 0);
  if (cvA > 0 && cvB > 0 && Math.abs(cvA - cvB) >= 0.18) {
    motorLlr -= 0.45;
  }
  let dot = 0.0;
  let normA = 0.0;
  let normB = 0.0;
  let defaultTwoSlotsOnly = true;
  const htA = bioA.hotbar_transitions || [];
  const htB = bioB.hotbar_transitions || [];
  for (let i = 0; i < 9; i++) {
    for (let j = 0; j < 9; j++) {
      const a = (htA[i] && htA[i][j]) || 0;
      const b = (htB[i] && htB[i][j]) || 0;
      if ((a > 0 || b > 0) && (i > 1 || j > 1)) defaultTwoSlotsOnly = false;
      dot += a * b;
      normA += a * a;
      normB += b * b;
    }
  }
  const hotbarCosine = (normA > 0 && normB > 0) ? (dot / (Math.sqrt(normA) * Math.sqrt(normB))) : 0.0;
  if (hotbarCosine >= 0.72) motorLlr += (defaultTwoSlotsOnly ? 0.30 : 0.65) * hotbarCosine;
  else if (hotbarCosine <= 0.22) motorLlr -= 0.40;

  // Goal Markov similarity
  const statesOrder = ['MINING', 'BUILDING', 'CRAFTING', 'COMBAT', 'CONTAINER_SORT', 'COMMAND_CHAT', 'AFK'];
  const buildTrans = (seq) => {
    const m = Array.from({ length: 7 }, () => [0, 0, 0, 0, 0, 0, 0]);
    for (let i = 1; i < (seq || []).length; i++) {
      const u = statesOrder.indexOf(seq[i - 1]);
      const v = statesOrder.indexOf(seq[i]);
      if (u >= 0 && v >= 0) m[u][v]++;
    }
    return m;
  };
  const tmA = buildTrans(bioA.markov_states);
  const tmB = buildTrans(bioB.markov_states);
  let gDot = 0.0;
  let gNormA = 0.0;
  let gNormB = 0.0;
  let activeRowsA = 0;
  let activeRowsB = 0;
  for (let i = 0; i < 7; i++) {
    const rA = tmA[i].reduce((x, y) => x + y, 0);
    const rB = tmB[i].reduce((x, y) => x + y, 0);
    if (rA > 0) activeRowsA++;
    if (rB > 0) activeRowsB++;
    for (let j = 0; j < 7; j++) {
      const pa = rA > 0 ? tmA[i][j] / rA : 0.0;
      const pb = rB > 0 ? tmB[i][j] / rB : 0.0;
      gDot += pa * pb;
      gNormA += pa * pa;
      gNormB += pb * pb;
    }
  }
  const goalCosine = (gNormA > 0 && gNormB > 0) ? (gDot / (Math.sqrt(gNormA) * Math.sqrt(gNormB))) : 0.0;
  const chunksA = new Set(bioA.visited_chunks || []);
  const chunksB = new Set(bioB.visited_chunks || []);
  let inter = 0;
  for (const c of chunksA) if (chunksB.has(c)) inter++;
  const union = chunksA.size + chunksB.size - inter;
  const spatialOverlap = union > 0 ? inter / union : 0.0;
  let goalLlr = 0.0;
  if (goalCosine >= 0.78) {
    const singleStateDisjoint = activeRowsA <= 1 && activeRowsB <= 1 && spatialOverlap === 0.0;
    goalLlr += (singleStateDisjoint ? 0.35 : 0.85) + 0.40 * spatialOverlap;
  } else if (goalCosine <= 0.42) {
    goalLlr -= 0.85;
  }

  // Temporal activity correlation across 2000ms buckets
  const buckA = bioA.activity_buckets || {};
  const buckB = bioB.activity_buckets || {};
  const allKeys = [...new Set([...Object.keys(buckA), ...Object.keys(buckB)])].sort((a, b) => Number(a) - Number(b));
  let tempLlr = 0.0;
  let pearsonR = 0.0;
  let dualRatio = 0.0;
  let independentSimultaneous = false;
  if (allKeys.length >= 6) {
    const xa = allKeys.map((k) => Number(buckA[k] || 0.0));
    const xb = allKeys.map((k) => Number(buckB[k] || 0.0));
    const n = allKeys.length;
    const mA = xa.reduce((a, b) => a + b, 0) / n;
    const mB = xb.reduce((a, b) => a + b, 0) / n;
    let cov = 0.0;
    let vA = 0.0;
    let vB = 0.0;
    let dual = 0;
    let excl = 0;
    for (let i = 0; i < n; i++) {
      cov += (xa[i] - mA) * (xb[i] - mB);
      vA += (xa[i] - mA) * (xa[i] - mA);
      vB += (xb[i] - mB) * (xb[i] - mB);
      if (xa[i] > 0.1 && xb[i] > 0.1) dual++;
      else if (xa[i] > 0.1 || xb[i] > 0.1) excl++;
    }
    pearsonR = (vA > 1e-9 && vB > 1e-9) ? Math.max(-1.0, Math.min(1.0, cov / Math.sqrt(vA * vB))) : 0.0;
    dualRatio = dual / n;
    const exclRatio = excl / n;
    if ((pearsonR <= -0.35 && (exclRatio >= 0.40 || dualRatio < 0.55)) || (exclRatio >= 0.85 && pearsonR < -0.20)) {
      tempLlr = 1.65 + Math.min(0.85, Math.abs(pearsonR + 0.30) * 1.4);
    } else if (dualRatio >= 0.35 && dual >= 5 && pearsonR > -0.22 && pearsonR < 0.35) {
      tempLlr = -2.25 - Math.min(0.55, dualRatio);
      if (n >= 8) independentSimultaneous = true;
    }
  }

  const shrink = (llr, nEff, kappa) => Math.max(-2.8, Math.min(2.8, (nEff / (nEff + kappa)) * llr));
  const rawSMotor = shrink(motorLlr, 24, 8.0);
  const sMotor = (independentSimultaneous && rawSMotor > 0.0) ? (rawSMotor * 0.5) : rawSMotor;
  const rawSGoal = shrink(goalLlr, 16, 6.0);
  const sGoal = (independentSimultaneous && rawSGoal > 0.0) ? 0.0 : rawSGoal;
  const sTemp = independentSimultaneous ? Math.min(shrink(tempLlr, 10, 6.0), -2.80) : shrink(tempLlr, 10, 6.0);
  const prior = sharedIp ? 0.0 : -0.85;
  const postLogOdds = prior + sMotor + sGoal + sTemp;
  const posterior = 1.0 / (1.0 + Math.exp(-postLogOdds));
  const posPillars = (sMotor >= 0.25 ? 1 : 0) + (sGoal >= 0.25 ? 1 : 0) + (sTemp >= 0.25 ? 1 : 0);
  const negPillars = (sMotor <= -0.25 ? 1 : 0) + (sGoal <= -0.25 ? 1 : 0) + (sTemp <= -0.25 ? 1 : 0);

  let verdict = 'SHARED_ENV_AMBIGUOUS';
  if (independentSimultaneous && posterior <= 0.35) {
    verdict = 'LIKELY_DISTINCT_PEOPLE';
  } else if (posterior >= 0.80 && posPillars >= 2) {
    verdict = 'LIKELY_SAME_PERSON';
  } else if (posterior <= 0.22 && negPillars >= 2) {
    verdict = 'LIKELY_DISTINCT_PEOPLE';
  }
  return {
    verdict,
    posterior: Number(posterior.toFixed(4)),
    motor_llr: Number(sMotor.toFixed(4)),
    goal_llr: Number(sGoal.toFixed(4)),
    temporal_llr: Number(sTemp.toFixed(4)),
    hotbar_cosine: Number(hotbarCosine.toFixed(4)),
    goal_cosine: Number(goalCosine.toFixed(4)),
    pearson_r: Number(pearsonR.toFixed(4)),
    independent_simultaneous: independentSimultaneous
  };
}

/**
 * Simulate a bot logging off after an initial session, followed by a new bot account
 * logging in with a mutated version of the same fingerprint (`mutationPct` in `0..100`),
 * and compare it against both a simultaneously active counterfactual bot and 3 bots
 * initialized with distinct starting fingerprints.
 */
function simulateLogoffMutatedRelogEpisode(basePersonaIdx = 0, mutationPct = 15, steps = 24) {
  const pIdx = parsePersonaIndex(basePersonaIdx, 0);
  const mPct = Math.max(0, Math.min(100, mutationPct | 0));
  const nSteps = Math.max(12, steps | 0);
  const runTrace = (spec, bIdx) => {
    const bs = { botIdx: bIdx, persona: spec };
    ensureBiometricState(bs, spec);
    for (let s = 0; s < nSteps; s++) {
      recordBiometricStep(bs, null, 'harvest_nearest', s);
    }
    return buildBiometricSummary(bs);
  };

  const originalRelog = runTrace(`mutated:${pIdx}:0:relog`, 0);
  const mutatedRelog = runTrace(`mutated:${pIdx}:${mPct}:relog`, 1);
  const originalSim = runTrace(`mutated:${pIdx}:0:sim`, 0);
  const mutatedSim = runTrace(`mutated:${pIdx}:${mPct}:sim`, 1);

  const relogEval = evaluateSleuthIdentityPair(originalRelog, mutatedRelog, true);
  const simultaneousEval = evaluateSleuthIdentityPair(originalSim, mutatedSim, true);

  const distinctEvals = [];
  let maxDistinctPosterior = 0.0;
  for (let k = 1; k <= 3; k++) {
    const dIdx = (pIdx + k) & 7;
    const dTrace = runTrace(`distinct:${dIdx}`, dIdx);
    const dEval = evaluateSleuthIdentityPair(originalRelog, dTrace, true);
    distinctEvals.push(dEval);
    if (dEval.posterior > maxDistinctPosterior) {
      maxDistinctPosterior = dEval.posterior;
    }
  }

  const probDrop = Number((relogEval.posterior - simultaneousEval.posterior).toFixed(4));
  const detectedTop = relogEval.posterior > maxDistinctPosterior && relogEval.verdict === 'LIKELY_SAME_PERSON';
  return {
    base_persona_idx: pIdx,
    mutation_pct: mPct,
    relog_eval: relogEval,
    simultaneous_eval: simultaneousEval,
    distinct_evals: distinctEvals,
    simultaneous_probability_drop: probDrop,
    max_distinct_posterior: Number(maxDistinctPosterior.toFixed(4)),
    detected_relog_as_top_match: detectedTop
  };
}

function decodeMobPolicyWeights(raw, where = 'mob_weights') {
  if (raw instanceof Int8Array && raw.length === MOB_WEIGHT_BYTES) return raw;
  if (typeof raw === 'string' && raw.length > 0) {
    const presetB64 = PREBAKED_MOB_POLICIES[raw.toLowerCase()];
    const b64Str = presetB64 || raw;
    const buf = Buffer.from(b64Str, 'base64');
    if (buf.length !== MOB_WEIGHT_BYTES) {
      fail(`${where}: base64 must decode to ${MOB_WEIGHT_BYTES} bytes, got ${buf.length}`);
    }
    const out = new Int8Array(MOB_WEIGHT_BYTES);
    for (let i = 0; i < MOB_WEIGHT_BYTES; i++) out[i] = clampInt8((buf[i] << 24) >> 24);
    return out;
  }
  if (Array.isArray(raw)) {
    if (raw.length !== MOB_WEIGHT_BYTES) {
      fail(`${where}: array must have ${MOB_WEIGHT_BYTES} entries, got ${raw.length}`);
    }
    const out = new Int8Array(MOB_WEIGHT_BYTES);
    for (let i = 0; i < MOB_WEIGHT_BYTES; i++) out[i] = clampInt8(Number(raw[i]) | 0);
    return out;
  }
  return decodeMobPolicyWeights(PREBAKED_MOB_POLICIES.combat, where);
}

function selectMobActionInt(weightsInt8, ctxInt8, tempShift, nextUint32, outLogitsInt32) {
  const logits = outLogitsInt32 || new Int32Array(MOB_NUM_ACTIONS);
  const shift = Math.max(4, Math.min(16, (tempShift | 0) || 8));
  let maxLogit = -0x7fffffff;
  for (let a = 0; a < MOB_NUM_ACTIONS; a++) {
    let acc = 0;
    const row = a << 3;
    for (let f = 0; f < MOB_NUM_FEATURES; f++) {
      acc = (acc + Math.imul(weightsInt8[row + f] | 0, ctxInt8[f] | 0)) | 0;
    }
    logits[a] = acc;
    if (acc > maxLogit) maxLogit = acc;
  }
  const weights = new Int32Array(MOB_NUM_ACTIONS);
  let totalWeight = 0;
  for (let a = 0; a < MOB_NUM_ACTIONS; a++) {
    const diff = (logits[a] - maxLogit) >> (shift - 4);
    if (diff <= -240) {
      weights[a] = 1;
    } else {
      const oct = (-diff) >> 4;
      const rem = (-diff) & 15;
      const base = EXP2_FRAC_LUT[15 - rem];
      const w = base >> oct;
      weights[a] = w > 1 ? w : 1;
    }
    totalWeight = (totalWeight + weights[a]) | 0;
  }
  const r = (nextUint32() % (totalWeight >>> 0)) | 0;
  let rem = r;
  for (let a = 0; a < MOB_NUM_ACTIONS; a++) {
    rem = (rem - weights[a]) | 0;
    if (rem < 0) return a;
  }
  return MOB_NUM_ACTIONS - 1;
}

function computeMobContextInt8(c) {
  const ctx = new Int8Array(MOB_NUM_FEATURES);
  const isRanged = c.mob_kind === 'ranged' ? 1 : 0;
  const dist = c.mob_dist | 0;
  const elev = c.elevation_adv | 0;
  const sheltered = c.sheltered | 0;
  const geared = (c.tool_tier | 0) >= 3 ? 1 : 0;
  if (isRanged) {
    if (dist <= 2) ctx[2] = 127;
    else if (dist >= 3 && dist <= 9 && !sheltered) ctx[3] = 127;
    else if (sheltered) ctx[6] = 127;
    else ctx[5] = 127;
  } else {
    if (elev >= 3 || sheltered) ctx[6] = 127;
    else if (dist <= 2) ctx[4] = 127;
    else ctx[5] = 127;
  }
  if (geared) ctx[7] = 16;
  return ctx;
}

function computePlayerCombatContextInt8(c) {
  const ctx = new Int8Array(POLICY_NUM_FEATURES);
  const sheltered = c.sheltered | 0;
  const elev = c.elevation_adv | 0;
  const geared = (c.tool_tier | 0) >= 3 ? 1 : 0;
  const isMelee = c.mob_kind === 'melee' ? 1 : 0;
  const isRanged = c.mob_kind === 'ranged' ? 1 : 0;
  const mobActive = c.mob_active | 0;
  const canStrikeFromPillar = (isMelee && elev >= 3) ? 1 : 0;
  const readyToFight = (geared || canStrikeFromPillar) ? 1 : 0;
  if (!mobActive) {
    ctx[0] = 127;
    return ctx;
  }
  if (readyToFight) {
    ctx[15] = 127;
  } else if (sheltered && !geared && (c.ores | 0) >= 2) {
    ctx[10] = 127;
  } else if (isMelee && elev < 3) {
    ctx[11] = 127;
  } else if (isRanged && !sheltered) {
    ctx[12] = 127;
  } else {
    ctx[0] = 127;
  }
  return ctx;
}

/** Decode base64 or array weights into an Int8Array(256). */
function decodePolicyWeights(raw, where = 'weights') {
  if (raw instanceof Int8Array && raw.length === POLICY_WEIGHT_BYTES) return raw;
  if (typeof raw === 'string' && raw.length > 0) {
    const presetB64 = PREBAKED_POLICIES[raw.toLowerCase()];
    const b64Str = presetB64 || raw;
    const buf = Buffer.from(b64Str, 'base64');
    if (buf.length !== POLICY_WEIGHT_BYTES) {
      fail(`${where}: base64 must decode to ${POLICY_WEIGHT_BYTES} bytes, got ${buf.length}`);
    }
    const out = new Int8Array(POLICY_WEIGHT_BYTES);
    for (let i = 0; i < POLICY_WEIGHT_BYTES; i++) out[i] = clampInt8((buf[i] << 24) >> 24);
    return out;
  }
  if (Array.isArray(raw)) {
    if (raw.length !== POLICY_WEIGHT_BYTES) {
      fail(`${where}: array must have ${POLICY_WEIGHT_BYTES} entries, got ${raw.length}`);
    }
    const out = new Int8Array(POLICY_WEIGHT_BYTES);
    for (let i = 0; i < POLICY_WEIGHT_BYTES; i++) out[i] = clampInt8(Number(raw[i]) | 0);
    return out;
  }
  return new Int8Array(POLICY_WEIGHT_BYTES);
}

/** Resolve policy weights from explicit `weights_b64` / `weights` or a named/auto `preset`. */
function resolvePolicyWeights(act, botIdx = 0) {
  if (act && typeof act === 'object') {
    if (typeof act.weights_b64 === 'string' && act.weights_b64.length > 0) {
      return decodePolicyWeights(act.weights_b64, 'policy.weights_b64');
    }
    const presetRaw = typeof act.preset === 'string' ? act.preset.trim().toLowerCase() : '';
    if (presetRaw === 'sleuth' || (typeof act.persona === 'string' && act.persona.toLowerCase().startsWith('sleuth'))) {
      const p = resolveBotPersona(act.persona || 'sleuth', botIdx);
      if (p && typeof p.pretrained_policy_b64 === 'string' && p.pretrained_policy_b64.length > 0) {
        return decodePolicyWeights(p.pretrained_policy_b64, 'policy.sleuth');
      }
    }
    if (presetRaw === 'auto') {
      const key = POLICY_PRESET_ORDER[Math.abs(botIdx | 0) % POLICY_PRESET_ORDER.length];
      return decodePolicyWeights(PREBAKED_POLICIES[key], `policy.preset(${key})`);
    }
    if (presetRaw && PREBAKED_POLICIES[presetRaw]) {
      return decodePolicyWeights(PREBAKED_POLICIES[presetRaw], `policy.preset(${presetRaw})`);
    }
  }
  return new Int8Array(POLICY_WEIGHT_BYTES);
}

/**
 * Pure integer compact policy evaluator:
 * - Dot product of Int8[16x16] weights and Int8[16] context using Math.imul -> Int32 logits.
 * - Integer temperature shift (`tempShift` in [4..16], default 10) and base-2 LUT integer
 *   roulette-wheel selection using 32-bit unsigned PRNG (`nextUint32()`).
 */
function selectPolicyActionInt(weightsInt8, ctxInt8, tempShift, nextUint32, outLogitsInt32) {
  const logits = outLogitsInt32 || new Int32Array(POLICY_NUM_ACTIONS);
  const shift = Math.max(4, Math.min(16, (tempShift | 0) || 10));
  let maxLogit = -0x7fffffff;
  for (let a = 0; a < POLICY_NUM_ACTIONS; a++) {
    let acc = 0;
    const row = a << 4;
    for (let f = 0; f < POLICY_NUM_FEATURES; f++) {
      acc = (acc + Math.imul(weightsInt8[row + f] | 0, ctxInt8[f] | 0)) | 0;
    }
    logits[a] = acc;
    if (acc > maxLogit) maxLogit = acc;
  }
  const weights = new Int32Array(POLICY_NUM_ACTIONS);
  let totalWeight = 0;
  for (let a = 0; a < POLICY_NUM_ACTIONS; a++) {
    // Non-positive scaled delta from maxLogit in 1/16ths of an octave
    const diff = (logits[a] - maxLogit) >> (shift - 4);
    if (diff <= -240) {
      weights[a] = 1;
    } else {
      const oct = (-diff) >> 4;
      const rem = (-diff) & 15;
      const base = EXP2_FRAC_LUT[15 - rem];
      const w = base >> oct;
      weights[a] = w > 1 ? w : 1;
    }
    totalWeight = (totalWeight + weights[a]) | 0;
  }
  const r = (nextUint32() % (totalWeight >>> 0)) | 0;
  let rem = r;
  for (let a = 0; a < POLICY_NUM_ACTIONS; a++) {
    rem = (rem - weights[a]) | 0;
    if (rem < 0) return a;
  }
  return POLICY_NUM_ACTIONS - 1;
}

/** Ensure per-bot virtual task state exists (tracks local blocks/inventory for both live and mock worlds). */
function ensureTaskState(bs, role = 0) {
  if (!bs.taskState) {
    bs.taskState = {
      anchor: null,
      pos: { x: 0, y: 64, z: 0 },
      yawIdx: 0,
      role: clampInt8(role),
      stepsSinceProgress: 0,
      rtps: 0,
      deaths: 0,
      tradesCompleted: 0,
      villagerRolls: 0,
      villagerTrades: 0,
      skillTrades: 0,
      redstoneLinks: 0,
      maxDepth: 0,
      tunnelCells: 0,
      cavesFound: 0,
      inv: {
        logs: 0,
        planks: 0,
        sticks: 0,
        cobble: 0,
        torches: 0,
        utilities: 0,
        ores: 0,
        coal: 0,
        iron: 0,
        diamond: 0,
        emeralds: 0,
        redstone: 0,
        tool_tier: 0,
        diamond_tools: 0
      },
      placedWalls: new Set(),
      placedRoofs: new Set(),
      placedPillars: new Set(),
      placedTorches: new Set(),
      placedUtilities: new Set(),
      dugCells: new Set(),
      chunksExplored: new Set(['0,0']),
      diamondSources: new Set(),
      combat: null,
      trace: []
    };
  }
  return bs.taskState;
}

const HOSTILE_RANGED_NAMES = new Set([
  'skeleton', 'stray', 'bogged', 'pillager', 'blaze', 'witch', 'breeze', 'ghast', 'shulker'
]);
const HOSTILE_MELEE_NAMES = new Set([
  'zombie', 'husk', 'drowned', 'spider', 'cave_spider', 'creeper', 'vindicator',
  'vindication_illager', 'warden', 'enderman', 'ravager', 'zombified_piglin', 'piglin_brute', 'slime'
]);

/** Initialize or return the per-bot adversarial Player-vs-Mob combat state. */
function ensureCombatState(bs, opts = {}) {
  const ts = ensureTaskState(bs, opts.role || 0);
  if (!ts.combat) {
    const seed = (opts.seed | 0) || 1001;
    ts.combat = {
      mob_active: 1,
      mob_kind: opts.mobKind === 'ranged' ? 'ranged' : 'melee',
      wave: 0,
      mob_hp: 12,
      mob_dist: 3,
      player_hp: 20,
      elevation_adv: 0,
      sheltered: 0,
      hole_depth: 0,
      tool_tier: 1,
      diamond_tools: 0,
      blocks: 8,
      logs: 1,
      ores: 0,
      mobs_killed: 0,
      mob_drops: 0,
      damage_dealt: 0,
      damage_taken: 0,
      deaths: 0,
      pillared_up: false,
      hid_in_hole: false,
      killed_when_equipped: false,
      mob_kited_steps: 0,
      mob_rushed_steps: 0,
      steps_used: 0,
      temp_shift: Math.max(4, Math.min(16, (opts.tempShift | 0) || 7)),
      mob_weights: decodeMobPolicyWeights(opts.mobWeightsB64 || PREBAKED_MOB_POLICIES.combat),
      mob_rng: makeIntRng(seed ^ 0x5a5a5a5a),
      live_entity_id: null
    };
  }
  return ts.combat;
}

/** Inspect live Mineflayer `bot.entities` for nearby hostile mobs (<= 16 blocks) and sync `ts.combat`. */
function scanLiveHostileMob(bot, ts) {
  if (!bot || !bot.entities || !bot.entity || !bot.entity.position) return null;
  const selfPos = bot.entity.position;
  let nearest = null;
  let bestDist = 16.0;
  for (const id of Object.keys(bot.entities)) {
    const ent = bot.entities[id];
    if (!ent || ent === bot.entity || !ent.position) continue;
    const rawName = String(ent.name || ent.mobType || ent.displayName || '').toLowerCase();
    const isRanged = HOSTILE_RANGED_NAMES.has(rawName);
    const isMelee = HOSTILE_MELEE_NAMES.has(rawName);
    if (!isRanged && !isMelee) continue;
    const dx = ent.position.x - selfPos.x;
    const dy = ent.position.y - selfPos.y;
    const dz = ent.position.z - selfPos.z;
    const d = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (d <= bestDist) {
      bestDist = d;
      nearest = { entity: ent, dist: Math.max(1, Math.round(d)) | 0, isRanged, dy: Math.round(selfPos.y - ent.position.y) | 0 };
    }
  }
  if (nearest) {
    const c = ensureCombatState({ taskState: ts }, { seed: 1337 });
    c.mob_active = 1;
    c.mob_kind = nearest.isRanged ? 'ranged' : 'melee';
    c.mob_dist = nearest.dist;
    c.live_entity_id = nearest.entity.id;
    if (typeof bot.health === 'number' && bot.health > 0) {
      c.player_hp = Math.round(bot.health) | 0;
    }
    return nearest;
  }
  return null;
}

/**
 * Compute 16-feature integer context vector c_t in Int8 [-127, 127].
 * Combines real Mineflayer blockAt/inventory queries (when keepWorld is active) with
 * deterministic local taskState counters so both live servers and offline tests are exact.
 */
function computeContextInt8(bot, bs) {
  const ts = ensureTaskState(bs);
  scanLiveHostileMob(bot, ts);
  if (ts.combat && ts.combat.mob_active) {
    return computePlayerCombatContextInt8(ts.combat);
  }
  const ctx = new Int8Array(POLICY_NUM_FEATURES);
  ctx[0] = 127; // feature 0: constant bias (+1.0 in Q7)

  // Sync integer position from bot entity on first spawn or after server teleport
  const p = posOf(bot);
  if (p && (!ts.spawnSynced || ts.tpPending)) {
    ts.pos.x = Math.floor(p.x) | 0;
    ts.pos.y = Math.floor(p.y) | 0;
    ts.pos.z = Math.floor(p.z) | 0;
    ts.spawnSynced = true;
    ts.tpPending = false;
  }
  if (!ts.anchor) {
    ts.anchor = { x: ts.pos.x, y: ts.pos.y, z: ts.pos.z };
  }

  // Read live inventory counts if Mineflayer inventory is populated
  let liveLogs = 0, liveBuild = 0, liveTorches = 0, liveFunc = 0, liveOres = 0;
  const items = bot && bot.inventory && typeof bot.inventory.items === 'function' ? bot.inventory.items() : [];
  for (const it of items) {
    const nm = String((it && it.name) || '').toLowerCase();
    const cnt = (it && it.count) | 0;
    if (nm.includes('log') || nm.includes('stem')) liveLogs += cnt;
    else if (nm.includes('plank') || nm.includes('cobble') || nm.includes('stone') || nm.includes('dirt') || nm.includes('brick')) liveBuild += cnt;
    else if (nm.includes('torch') || nm.includes('lantern')) liveTorches += cnt;
    else if (nm.includes('door') || nm.includes('bed') || nm.includes('chest') || nm.includes('crafting_table') || nm.includes('repeater') || nm.includes('hopper')) liveFunc += cnt;
    else if (nm.includes('coal') || nm.includes('iron') || nm.includes('gold') || nm.includes('diamond') || nm.includes('emerald')) liveOres += cnt;
  }

  // Scan local world neighborhood when bot.blockAt is available
  let liveHorizHits = 0, liveRoof = 0, liveFloor = 1, liveLight = 7, liveNearResource = 0, liveNearContainer = 0;
  if (bot && typeof bot.blockAt === 'function' && bot.entity && bot.entity.position) {
    const pos = bot.entity.position;
    try {
      const dirs = [[1, 0], [-1, 0], [0, 1], [0, -1]];
      for (const [dx, dz] of dirs) {
        for (let r = 1; r <= 3; r++) {
          const b = bot.blockAt(pos.offset(dx * r, 0, dz * r));
          if (b && b.boundingBox === 'block') { liveHorizHits++; break; }
        }
      }
      for (let dy = 2; dy <= 4; dy++) {
        const b = bot.blockAt(pos.offset(0, dy, 0));
        if (b && b.boundingBox === 'block') { liveRoof = 1; break; }
      }
      const under = bot.blockAt(pos.offset(0, -1, 0));
      if (under) {
        const nm = String(under.name || '');
        liveFloor = (under.boundingBox === 'block' && !nm.includes('lava') && !nm.includes('fire')) ? 1 : -1;
      }
      const feet = bot.blockAt(pos);
      if (feet && typeof feet.light === 'number') liveLight = feet.light | 0;
      for (let dx = -2; dx <= 2; dx += 2) {
        for (let dz = -2; dz <= 2; dz += 2) {
          const b = bot.blockAt(pos.offset(dx, 0, dz));
          const nm = String((b && b.name) || '');
          if (nm.includes('log') || nm.includes('ore') || nm.includes('stone')) liveNearResource = 1;
          if (nm.includes('chest') || nm.includes('sign') || nm.includes('hopper') || nm.includes('button') || nm.includes('lever')) liveNearContainer = 1;
        }
      }
    } catch (e) { /* world column not decoded */ }
  }

  const wallCount = ts.placedWalls.size | 0;
  const roofCount = ts.placedRoofs.size | 0;
  const torchCount = ts.placedTorches.size | 0;
  const horizHits = Math.max(liveHorizHits, Math.min(4, wallCount >> 2));
  ctx[1] = clampInt8(((horizHits * 64) - 127) | 0); // enclosed_horiz in [-127, 127]
  ctx[2] = (liveRoof || roofCount >= 4) ? 127 : clampInt8(-127 + roofCount * 32); // roof_cover
  ctx[3] = liveFloor > 0 ? 127 : -127; // floor_solid
  const effLight = Math.min(15, Math.max(liveLight, 4 + torchCount * 5));
  ctx[4] = clampInt8(Math.imul(7 - effLight, 18)); // light_deficit (>0 when dark)
  const depth = (64 - ts.pos.y) | 0;
  if (depth > ts.maxDepth) ts.maxDepth = depth;
  ctx[5] = clampInt8((depth * 2) | 0); // depth_norm
  ctx[6] = intSatRatio((ts.inv.planks + ts.inv.cobble + liveBuild) | 0, 32); // inv_building
  ctx[7] = intSatRatio((ts.inv.logs + liveLogs) | 0, 12); // inv_wood_raw
  ctx[8] = intSatRatio((ts.inv.torches + liveTorches) | 0, 8); // inv_torches
  ctx[9] = intSatRatio((ts.inv.utilities + liveFunc) | 0, 4); // inv_functional
  ctx[10] = intSatRatio((ts.inv.ores + liveOres) | 0, 12); // inv_ores_val
  ctx[11] = (liveNearResource || ts.inv.logs < 8 || ts.pos.y < 56) ? 96 : -64; // near_log_or_ore
  ctx[12] = (liveNearContainer || ts.placedUtilities.has('chest') || ts.role > 0) ? 96 : -64; // near_container
  const distAnchor = (Math.abs(ts.pos.x - ts.anchor.x) + Math.abs(ts.pos.z - ts.anchor.z)) | 0;
  ctx[13] = intSatRatio(distAnchor, 16); // anchor_dist
  ctx[14] = intSatRatio(ts.stepsSinceProgress | 0, 8); // stalled_steps
  ctx[15] = clampInt8(ts.role | 0); // trait_role
  return ctx;
}

const RING_OFFSETS_12 = [
  [-2, -1], [-2, 0], [-2, 1],
  [2, -1], [2, 0], [2, 1],
  [-1, -2], [0, -2], [1, -2],
  [-1, 2], [0, 2], [1, 2]
];

function fuzzLiveDig(bot, bs, dx, dy, dz) {
  if (!bot || !bot._client) return;
  const p = posOf(bot);
  if (!p) return;
  const bx = (Math.floor(p.x) + (dx | 0)) | 0;
  const by = (Math.floor(p.y) + (dy | 0)) | 0;
  const bz = (Math.floor(p.z) + (dz | 0)) | 0;
  try {
    bot._client.write('held_item_slot', { slotId: 0 });
    bs.actionId = ((bs.actionId || 0) + 1) | 0;
    bot._client.write('block_dig', {
      status: 0,
      location: { x: bx, y: by, z: bz },
      face: 1,
      sequence: bs.actionId
    });
    bs.actionId = ((bs.actionId || 0) + 1) | 0;
    bot._client.write('block_dig', {
      status: 2,
      location: { x: bx, y: by, z: bz },
      face: 1,
      sequence: bs.actionId
    });
    bot._client.write('arm_animation', { hand: 0 });
  } catch (e) { /* protocol variation */ }
}

function fuzzLivePlace(bot, bs, slotId, dx, dy, dz, face = 1) {
  if (!bot || !bot._client) return;
  const p = posOf(bot);
  if (!p) return;
  const bx = (Math.floor(p.x) + (dx | 0)) | 0;
  const by = (Math.floor(p.y) + (dy | 0)) | 0;
  const bz = (Math.floor(p.z) + (dz | 0)) | 0;
  try {
    bot._client.write('held_item_slot', { slotId: slotId & 7 });
    bs.actionId = ((bs.actionId || 0) + 1) | 0;
    bot._client.write('block_place', {
      location: { x: bx, y: by, z: bz },
      direction: face | 0,
      hand: 0,
      cursorX: 0.5,
      cursorY: 1.0,
      cursorZ: 0.5,
      insideBlock: false,
      sequence: bs.actionId
    });
    bot._client.write('arm_animation', { hand: 0 });
  } catch (e) { /* protocol variation */ }
}

/**
 * Execute one simultaneous Player-vs-Mob round when `ts.combat.mob_active` is active,
 * updating integer combat state, live Mineflayer packets (`attack`, `use_block`, `block_dig`),
 * and returning the mapped `wireAct`.
 */
async function stepCombatRound(ep, bs, bot, skill, skillIdx, stepIdx, dirX, dirZ) {
  const ts = ensureTaskState(bs);
  const c = ensureCombatState(bs);
  c.steps_used = (c.steps_used + 1) | 0;
  const mCtx = computeMobContextInt8(c);
  const mAct = selectMobActionInt(c.mob_weights, mCtx, c.temp_shift || 7, c.mob_rng);
  const mSkill = MOB_MICRO_SKILLS[mAct] || 'mob_wait';
  let wireAct = { a: 'wait', ms: 50 };

  // 1. Player defensive / build / craft / movement micro-skill
  if (skill === 'place_pillar_or_bridge') {
    if (c.blocks > 0) {
      c.blocks = (c.blocks - 1) | 0;
      c.sheltered = 0;
      c.hole_depth = 0;
      c.elevation_adv = Math.min(4, (c.elevation_adv + 3) | 0);
      ts.placedPillars.add(`p_${ts.placedPillars.size}`);
      if (c.elevation_adv >= 3 && c.mob_kind === 'melee') {
        c.pillared_up = true;
      }
    }
    wireAct = { a: 'use_block', dx: 0, dy: -1, dz: 0, face: 1, hand: 0 };
    if (bot) fuzzLivePlace(bot, bs, 2, 0, -1, 0, 1);
  } else if (skill === 'dig_stair_down' || skill === 'dig_tunnel_fwd' || skill === 'place_wall_ring' || skill === 'place_roof_cell') {
    c.elevation_adv = 0;
    c.hole_depth = (c.hole_depth + 1) | 0;
    if (c.mob_kind === 'ranged' || c.mob_dist >= 4) {
      c.sheltered = 1;
      c.ores = (c.ores + 2) | 0;
      c.blocks = (c.blocks + 1) | 0;
      ts.inv.ores = (ts.inv.ores + 2) | 0;
      ts.inv.iron = (ts.inv.iron + 1) | 0;
      ts.inv.diamond = (ts.inv.diamond + 1) | 0;
      ts.dugCells.add(`${ts.pos.x},${ts.pos.y - c.hole_depth},${ts.pos.z}`);
      if (c.mob_kind === 'ranged') {
        c.hid_in_hole = true;
      }
    }
    if (skill === 'place_wall_ring' || skill === 'place_roof_cell') {
      ts.placedWalls.add(`w_${ts.placedWalls.size}`);
      wireAct = { a: 'use_block', dx: dirX, dy: 0, dz: dirZ, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 1, dirX, 0, dirZ, 1);
    } else {
      wireAct = { a: 'swing', hand: 0 };
      if (bot) fuzzLiveDig(bot, bs, 0, -1, 0);
    }
  } else if (skill === 'craft_basics') {
    if (c.sheltered && c.ores >= 2) {
      c.ores = (c.ores - 2) | 0;
      c.tool_tier = 4;
      c.diamond_tools = (c.diamond_tools + 1) | 0;
      ts.inv.tool_tier = 4;
      ts.inv.diamond_tools = (ts.inv.diamond_tools + 1) | 0;
      ts.diamondSources.add('combat');
    }
    wireAct = { a: 'click', slot: 0, button: 0, mode: 0 };
  } else if (skill === 'step_toward_anchor') {
    c.sheltered = 0;
    c.mob_dist = Math.max(1, (c.mob_dist - 2) | 0);
    wireAct = { a: 'move', dx: dirX * 2, dz: dirZ * 2, ms: 80 };
    if (bot) await fuzzMove(ep, bs, wireAct);
  } else if (skill === 'step_away_anchor') {
    c.sheltered = 0;
    c.mob_dist = Math.min(10, (c.mob_dist + 2) | 0);
    wireAct = { a: 'move', dx: -dirX * 2, dz: -dirZ * 2, ms: 80 };
    if (bot) await fuzzMove(ep, bs, wireAct);
  }

  // 2. Mob movement & attack resolution
  if (mSkill === 'mob_advance') {
    c.mob_dist = Math.max(1, (c.mob_dist - 2) | 0);
    if (c.mob_kind === 'melee') c.mob_rushed_steps = (c.mob_rushed_steps + 1) | 0;
  } else if (mSkill === 'mob_retreat') {
    if (c.mob_kind === 'ranged' && c.mob_dist <= 2) {
      c.mob_kited_steps = (c.mob_kited_steps + 1) | 0;
    }
    c.mob_dist = Math.min(10, (c.mob_dist + 2) | 0);
    if (c.mob_kind === 'ranged' && !c.sheltered && c.mob_dist >= 3 && c.mob_dist <= 9) {
      const rawDmg = c.tool_tier >= 3 ? 1 : 6;
      c.player_hp = (c.player_hp - rawDmg) | 0;
      c.damage_taken = (c.damage_taken + rawDmg) | 0;
      if (c.elevation_adv > 0) c.elevation_adv = 0;
    }
  } else if (mSkill === 'mob_leap_lunge') {
    c.mob_dist = Math.max(1, (c.mob_dist - 2) | 0);
    if (c.mob_kind === 'melee') {
      c.mob_rushed_steps = (c.mob_rushed_steps + 1) | 0;
      if (c.mob_dist <= 2 && c.elevation_adv < 3 && !c.sheltered) {
        const rawDmg = c.tool_tier >= 3 ? 1 : 6;
        c.player_hp = (c.player_hp - rawDmg) | 0;
        c.damage_taken = (c.damage_taken + rawDmg) | 0;
      }
    }
  } else if (mSkill === 'mob_melee_strike') {
    if (c.mob_kind === 'melee' && c.mob_dist <= 2 && c.elevation_adv < 3 && !c.sheltered) {
      const rawDmg = c.tool_tier >= 3 ? 1 : 6;
      c.player_hp = (c.player_hp - rawDmg) | 0;
      c.damage_taken = (c.damage_taken + rawDmg) | 0;
    }
  } else if (mSkill === 'mob_ranged_shot') {
    if (c.mob_kind === 'ranged' && c.mob_dist >= 3 && c.mob_dist <= 9) {
      c.mob_kited_steps = (c.mob_kited_steps + 1) | 0;
      if (!c.sheltered) {
        const rawDmg = c.tool_tier >= 3 ? 1 : 6;
        c.player_hp = (c.player_hp - rawDmg) | 0;
        c.damage_taken = (c.damage_taken + rawDmg) | 0;
        if (c.elevation_adv > 0) c.elevation_adv = 0;
      }
    }
  }

  // 3. Player attack resolution
  if (skill === 'harvest_nearest' || skill === 'interact_nearest') {
    let canHit = (c.mob_dist <= 2 && !c.sheltered) || (c.elevation_adv >= 3 && c.mob_kind === 'melee');
    if (c.tool_tier >= 3) {
      c.sheltered = 0;
      c.elevation_adv = 0;
      c.mob_dist = Math.max(1, (c.mob_dist - 2) | 0);
      canHit = true;
    }
    if (canHit) {
      let dmg = c.tool_tier >= 3 ? 9 : (c.elevation_adv >= 3 ? 6 : 2);
      if (mSkill === 'mob_brace_cover') dmg = Math.max(1, dmg >> 1);
      c.mob_hp = (c.mob_hp - dmg) | 0;
      c.damage_dealt = (c.damage_dealt + dmg) | 0;
    }
    wireAct = { a: 'swing', hand: 0 };
    if (bot) {
      try {
        if (c.live_entity_id !== null && bot.entities && bot.entities[c.live_entity_id] && typeof bot.attack === 'function') {
          bot.attack(bot.entities[c.live_entity_id]);
        } else if (bot._client) {
          bot._client.write('arm_animation', { hand: 0 });
        }
      } catch (e) { /* ignore protocol mismatch */ }
    }
  }

  if (c.player_hp <= 0) {
    c.deaths = (c.deaths + 1) | 0;
    ts.deaths = (ts.deaths + 1) | 0;
    c.player_hp = 20;
    c.elevation_adv = 0;
    c.sheltered = 0;
  }

  if (c.mob_hp <= 0) {
    c.mobs_killed = (c.mobs_killed + 1) | 0;
    const dropGain = 2 + (c.tool_tier >= 3 ? 2 : 0);
    c.mob_drops = (c.mob_drops + dropGain) | 0;
    ts.inv.emeralds = (ts.inv.emeralds + dropGain) | 0;
    if (c.tool_tier >= 3) {
      c.killed_when_equipped = true;
    }
    c.wave = (c.wave + 1) | 0;
    if (c.wave === 1) {
      c.mob_kind = 'ranged';
      c.mob_hp = 16;
      c.mob_dist = 2;
      c.sheltered = 0;
    } else if (c.wave === 2) {
      c.mob_kind = 'melee';
      c.mob_hp = 18;
      c.mob_dist = 4;
      c.elevation_adv = 0;
    } else {
      c.mob_active = 0;
    }
  }

  const stepDelayMs = recordBiometricStep(bs, bot, skill, stepIdx);
  ts.stepsSinceProgress = 0;
  ts.trace.push({ step: stepIdx, skill, skill_idx: skillIdx, mob_skill: mSkill, wire_act: wireAct });
  return { skill, skillIdx, mobSkill: mSkill, wireAct, progressed: true, stepDelayMs };
}

/**
 * Execute one compositional micro-skill in both the integer task model and over the
 * wire (when `bot` is connected), returning the mapped concrete primitive action.
 */
async function executeMicroSkill(ep, bs, bot, skillIdx, stepIdx) {
  const ts = ensureTaskState(bs);
  const skill = POLICY_MICRO_SKILLS[skillIdx] || 'noop_wait';
  const dirs = [[1, 0], [0, 1], [-1, 0], [0, -1]];
  const [dirX, dirZ] = dirs[ts.yawIdx & 3];
  if (ts.combat && ts.combat.mob_active) {
    return stepCombatRound(ep, bs, bot, skill, skillIdx, stepIdx, dirX, dirZ);
  }
  let progressed = false;
  let wireAct = { a: 'wait', ms: 50 };

  switch (skill) {
    case 'noop_wait':
      wireAct = { a: 'wait', ms: 50 };
      break;
    case 'rtp_or_anchor':
      if (ts.rtps === 0) {
        ts.rtps++;
        ts.anchor = { x: ts.pos.x, y: ts.pos.y, z: ts.pos.z };
        progressed = true;
        wireAct = { a: 'rtp', args: '' };
        if (bot) {
          const line = '/' + ep.cmd.split('{self}').join(bs.username);
          try {
            bot.chat(line);
          } catch (e) {
            if (ep && typeof ep.emit === 'function') {
              ep.emit({ ev: EV_BOT_ERROR, bot: bs.username, text: String(e && e.message ? e.message : e).slice(0, 256) });
            }
          }
        }
      } else {
        ts.anchor = { x: ts.pos.x, y: ts.pos.y, z: ts.pos.z };
        wireAct = { a: 'swing', hand: 0 };
      }
      break;
    case 'step_toward_anchor': {
      const dx = Math.sign((ts.anchor ? ts.anchor.x : 0) - ts.pos.x) * 2;
      const dz = Math.sign((ts.anchor ? ts.anchor.z : 0) - ts.pos.z) * 2;
      ts.pos.x = (ts.pos.x + dx) | 0;
      ts.pos.z = (ts.pos.z + dz) | 0;
      if (ts.anchor && ts.pos.y < ts.anchor.y) {
        ts.pos.y = Math.min(ts.anchor.y, (ts.pos.y + 4) | 0);
      }
      wireAct = { a: 'move', dx, dz, ms: 100 };
      if (bot) await fuzzMove(ep, bs, wireAct);
      break;
    }
    case 'step_away_anchor': {
      const dx = dirX * 2;
      const dz = dirZ * 2;
      ts.pos.x = (ts.pos.x + dx) | 0;
      ts.pos.z = (ts.pos.z + dz) | 0;
      ts.yawIdx = (ts.yawIdx + 1) & 3;
      const cellKey = `${ts.pos.x >> 2},${ts.pos.z >> 2}`;
      if (!ts.chunksExplored.has(cellKey)) {
        ts.chunksExplored.add(cellKey);
        progressed = true;
        if ((ts.chunksExplored.size & 1) === 0) {
          ts.cavesFound = (ts.cavesFound + 1) | 0;
          ts.inv.emeralds = (ts.inv.emeralds + 1) | 0;
        }
      }
      wireAct = { a: 'move', dx, dz, ms: 100 };
      if (bot) await fuzzMove(ep, bs, wireAct);
      break;
    }
    case 'harvest_nearest': {
      const bonus = ts.inv.tool_tier >= 3 ? 2 : 1;
      if (ts.pos.y < 58 || ts.cavesFound >= 2) {
        ts.inv.cobble = (ts.inv.cobble + 2 * bonus) | 0;
        ts.inv.ores = (ts.inv.ores + bonus) | 0;
        if (ts.pos.y <= 40 || ts.cavesFound >= 2) {
          ts.inv.diamond = (ts.inv.diamond + 1) | 0;
          ts.diamondSources.add(ts.tunnelCells > 0 ? 'caving' : 'mining');
        } else if (ts.pos.y <= 50) {
          ts.inv.iron = (ts.inv.iron + bonus) | 0;
        } else {
          ts.inv.coal = (ts.inv.coal + bonus) | 0;
        }
      } else {
        ts.inv.logs = (ts.inv.logs + 2) | 0;
        ts.inv.cobble = (ts.inv.cobble + 1) | 0;
        ts.inv.redstone = (ts.inv.redstone + 1) | 0;
      }
      progressed = true;
      wireAct = { a: 'swing', hand: 0 };
      if (bot) fuzzLiveDig(bot, bs, dirX, -1, dirZ);
      break;
    }
    case 'dig_stair_down': {
      ts.pos.x = (ts.pos.x + dirX) | 0;
      ts.pos.z = (ts.pos.z + dirZ) | 0;
      ts.pos.y = Math.max(-56, (ts.pos.y - 2) | 0);
      ts.inv.cobble = (ts.inv.cobble + 2) | 0;
      const depth = (64 - ts.pos.y) | 0;
      if (depth > ts.maxDepth) ts.maxDepth = depth;
      if ((depth & 3) === 0) {
        ts.inv.ores = (ts.inv.ores + 1) | 0;
        if (depth >= 20) {
          ts.inv.diamond = (ts.inv.diamond + 1) | 0;
          ts.diamondSources.add('mining');
        } else if (depth >= 12) {
          ts.inv.iron = (ts.inv.iron + 1) | 0;
        } else {
          ts.inv.coal = (ts.inv.coal + 1) | 0;
        }
      }
      ts.dugCells.add(`${ts.pos.x},${ts.pos.y},${ts.pos.z}`);
      progressed = true;
      wireAct = { a: 'swing', hand: 0 };
      if (bot) {
        fuzzLiveDig(bot, bs, dirX, -1, dirZ);
        await fuzzMove(ep, bs, { a: 'move', dx: dirX, dz: dirZ, ms: 60 });
      }
      break;
    }
    case 'dig_tunnel_fwd': {
      ts.pos.x = (ts.pos.x + dirX) | 0;
      ts.pos.z = (ts.pos.z + dirZ) | 0;
      ts.tunnelCells = (ts.tunnelCells + 1) | 0;
      if ((ts.tunnelCells & 1) === 0) ts.cavesFound = (ts.cavesFound + 1) | 0;
      ts.inv.cobble = (ts.inv.cobble + 1) | 0;
      if (ts.pos.y < 58 || ts.tunnelCells >= 2) {
        ts.inv.ores = (ts.inv.ores + 1) | 0;
        if (ts.pos.y <= 44 || ts.tunnelCells >= 3) {
          ts.inv.diamond = (ts.inv.diamond + 1) | 0;
          ts.diamondSources.add('caving');
        } else if (ts.pos.y <= 52 || ts.tunnelCells >= 2) {
          ts.inv.iron = (ts.inv.iron + 1) | 0;
        } else {
          ts.inv.coal = (ts.inv.coal + 1) | 0;
        }
      }
      ts.dugCells.add(`${ts.pos.x},${ts.pos.y},${ts.pos.z}`);
      progressed = true;
      wireAct = { a: 'swing', hand: 0 };
      if (bot) {
        fuzzLiveDig(bot, bs, dirX * 2, -1, dirZ * 2);
        await fuzzMove(ep, bs, { a: 'move', dx: dirX, dz: dirZ, ms: 60 });
      }
      break;
    }
    case 'craft_basics': {
      if (ts.inv.logs > 0) {
        const convert = Math.min(2, ts.inv.logs);
        ts.inv.logs = (ts.inv.logs - convert) | 0;
        ts.inv.planks = (ts.inv.planks + convert * 4) | 0;
        ts.inv.sticks = (ts.inv.sticks + convert * 2) | 0;
        ts.inv.torches = (ts.inv.torches + 2) | 0;
        ts.inv.utilities = (ts.inv.utilities + 1) | 0;
        progressed = true;
      } else if (ts.inv.cobble >= 2) {
        ts.inv.torches = (ts.inv.torches + 1) | 0;
        ts.inv.utilities = (ts.inv.utilities + 1) | 0;
        progressed = true;
      }
      if (ts.inv.diamond >= 2) {
        ts.inv.diamond = (ts.inv.diamond - 1) | 0;
        ts.inv.diamond_tools = (ts.inv.diamond_tools + 1) | 0;
        ts.inv.tool_tier = 4;
        if (ts.diamondSources.size === 0) {
          ts.diamondSources.add(ts.tunnelCells > 0 ? 'caving' : 'mining');
        }
        progressed = true;
      } else if (ts.inv.iron >= 2 && ts.inv.tool_tier < 3) {
        ts.inv.tool_tier = 3;
        progressed = true;
      } else if (ts.inv.cobble >= 2 && ts.inv.tool_tier < 2) {
        ts.inv.tool_tier = 2;
        progressed = true;
      } else if (ts.inv.planks >= 2 && ts.inv.tool_tier < 1) {
        ts.inv.tool_tier = 1;
        progressed = true;
      }
      wireAct = { a: 'click', slot: 0, button: 0, mode: 0 };
      if (bot) fuzzLivePlace(bot, bs, 1, RING_OFFSETS_12[stepIdx % 12][0], -1, RING_OFFSETS_12[stepIdx % 12][1], 1);
      break;
    }
    case 'place_wall_ring': {
      const idx = ts.placedWalls.size;
      if ((ts.inv.planks + ts.inv.cobble) > 0 && idx < 16) {
        if (ts.inv.planks > 0) ts.inv.planks--;
        else ts.inv.cobble--;
        ts.placedWalls.add(`w_${idx}`);
        progressed = true;
      }
      const [rx, rz] = RING_OFFSETS_12[idx % RING_OFFSETS_12.length];
      wireAct = { a: 'use_block', dx: rx, dy: -1, dz: rz, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 1, rx, -1, rz, 1);
      break;
    }
    case 'place_roof_cell': {
      const idx = ts.placedRoofs.size;
      if ((ts.inv.planks + ts.inv.cobble) > 0 && idx < 9) {
        if (ts.inv.planks > 0) ts.inv.planks--;
        else ts.inv.cobble--;
        ts.placedRoofs.add(`r_${idx}`);
        progressed = true;
      }
      const [rx, rz] = RING_OFFSETS_12[(idx + 3) % RING_OFFSETS_12.length];
      wireAct = { a: 'use_block', dx: rx, dy: -1, dz: rz, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 2, rx, -1, rz, 1);
      break;
    }
    case 'place_pillar_or_bridge': {
      const idx = ts.placedPillars.size;
      if ((ts.inv.planks + ts.inv.cobble) > 0) {
        if (ts.inv.planks > 0) ts.inv.planks--;
        else ts.inv.cobble--;
        ts.placedPillars.add(`p_${idx}`);
        progressed = true;
      }
      const [rx, rz] = RING_OFFSETS_12[(idx + 6) % RING_OFFSETS_12.length];
      wireAct = { a: 'use_block', dx: rx, dy: -1, dz: rz, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 2, rx, -1, rz, 1);
      break;
    }
    case 'place_torch_if_dark': {
      const idx = ts.placedTorches.size;
      if (ts.inv.torches > 0 && idx < 8) {
        ts.inv.torches--;
        ts.placedTorches.add(`t_${idx}`);
        progressed = true;
      }
      wireAct = { a: 'use_block', dx: dirX, dy: -1, dz: dirZ, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 3, dirX, -1, dirZ, 1);
      break;
    }
    case 'place_utility': {
      if (ts.inv.utilities > 0) {
        ts.inv.utilities--;
        const order = ts.role > 0
          ? ['lectern', 'smithing_table', 'chest', 'door', 'torch_lamp', 'bed', 'crafting_table']
          : ['door', 'torch_lamp', 'chest', 'bed', 'crafting_table', 'lectern', 'smithing_table'];
        for (const u of order) {
          if (!ts.placedUtilities.has(u)) {
            ts.placedUtilities.add(u);
            progressed = true;
            break;
          }
        }
      }
      const utilSlot = ts.role > 0 ? 5 : 4;
      wireAct = { a: 'use_block', dx: dirX * 2, dy: -1, dz: dirZ * 2, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, utilSlot, dirX * 2, -1, dirZ * 2, 1);
      break;
    }
    case 'place_redstone_link': {
      const idx = ts.redstoneLinks | 0;
      if ((ts.inv.redstone + ts.inv.utilities + ts.inv.cobble) > 0) {
        if (ts.inv.redstone > 0) ts.inv.redstone--;
        ts.redstoneLinks = (ts.redstoneLinks + 1) | 0;
        progressed = true;
      }
      const [rx, rz] = RING_OFFSETS_12[idx % RING_OFFSETS_12.length];
      wireAct = { a: 'use_block', dx: rx, dy: -1, dz: rz, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 6, rx, -1, rz, 1);
      break;
    }
    case 'interact_nearest': {
      if (ts.placedUtilities.has('lectern') || ts.placedUtilities.has('smithing_table') || ts.placedUtilities.has('chest') || ts.role > 0) {
        ts.villagerRolls = (ts.villagerRolls + 1) | 0;
        ts.tradesCompleted = (ts.tradesCompleted + 1) | 0;
        progressed = true;
      } else if (ts.redstoneLinks > 0) {
        ts.tradesCompleted = (ts.tradesCompleted + 1) | 0;
        progressed = true;
      }
      if (ts.chunksExplored.size >= 2) {
        ts.inv.emeralds = (ts.inv.emeralds + 2) | 0;
        if (ts.chunksExplored.size >= 3) {
          ts.inv.diamond = (ts.inv.diamond + 1) | 0;
          ts.diamondSources.add('exploration');
        }
        progressed = true;
      }
      wireAct = { a: 'use_block', dx: dirX, dy: -1, dz: dirZ, face: 1, hand: 0 };
      if (bot) fuzzLivePlace(bot, bs, 5, dirX * 2, -1, dirZ * 2, 1);
      break;
    }
    case 'deposit_or_trade': {
      if ((ts.inv.sticks + ts.inv.ores + ts.inv.planks + ts.inv.logs + ts.inv.emeralds) > 0) {
        ts.tradesCompleted = (ts.tradesCompleted + 1) | 0;
        const emeraldGain = ts.villagerRolls > 0 ? 3 : 2;
        ts.inv.emeralds = (ts.inv.emeralds + emeraldGain) | 0;
        const cost = ts.villagerRolls > 0 ? 2 : 4;
        if (ts.inv.emeralds >= cost) {
          ts.inv.emeralds = (ts.inv.emeralds - cost) | 0;
          ts.inv.diamond_tools = (ts.inv.diamond_tools + 1) | 0;
          ts.inv.tool_tier = 4;
          ts.villagerTrades = (ts.villagerTrades + 1) | 0;
          ts.diamondSources.add(ts.villagerRolls > 0 ? 'villager' : 'trade');
        }
        if (ts.role !== 0 || (ep && ep.sharedExchange)) {
          ts.skillTrades = (ts.skillTrades + 1) | 0;
          if (ep && ep.sharedExchange) {
            ep.sharedExchange.Pool = (ep.sharedExchange.Pool + 1) | 0;
          }
          if (ts.skillTrades >= 2) {
            ts.inv.diamond_tools = (ts.inv.diamond_tools + 1) | 0;
            ts.inv.tool_tier = 4;
            ts.diamondSources.add('skill_trade');
          }
        }
        progressed = true;
      }
      wireAct = { a: 'click', slot: 0, button: 0, mode: 1 };
      if (bot) fuzzLivePlace(bot, bs, 4, dirX * 2, -1, dirZ * 2, 1);
      break;
    }
  }

  if (progressed) ts.stepsSinceProgress = 0;
  else ts.stepsSinceProgress = (ts.stepsSinceProgress + 1) | 0;

  const stepDelayMs = recordBiometricStep(bs, bot, skill, stepIdx);
  ts.trace.push({ step: stepIdx, skill, skill_idx: skillIdx, wire_act: wireAct });
  return { skill, skillIdx, wireAct, progressed, stepDelayMs };
}

/** Build the episode-end `task_summary` NDJSON telemetry payload for a bot. */
function buildTaskSummary(bs) {
  const ts = ensureTaskState(bs);
  const walls = ts.placedWalls.size | 0;
  const roofs = ts.placedRoofs.size | 0;
  const enclosedCells = Math.min(9, Math.floor((walls * 9) / 12));
  const roofedCells = Math.min(enclosedCells, roofs);
  const torches = ts.placedTorches.size | 0;
  const minLight = Math.min(15, 4 + torches * 4);
  const canReturn = ts.anchor
    ? ((Math.abs(ts.pos.x - ts.anchor.x) + Math.abs(ts.pos.z - ts.anchor.z) + Math.abs(ts.pos.y - ts.anchor.y)) <= 12)
    : true;
  return {
    ev: 'task_summary',
    bot: bs.username,
    role: ts.role,
    anchor: ts.anchor || { x: 0, y: 64, z: 0 },
    pos: { ...ts.pos },
    inv: { ...ts.inv },
    footprint_scan: {
      wall_blocks: walls,
      roof_blocks: roofs,
      enclosed_cells: enclosedCells,
      roofed_cells: roofedCells,
      floating_blocks: ts.placedPillars.size | 0,
      min_light: minLight,
      has_door: ts.placedUtilities.has('door'),
      utilities: [...ts.placedUtilities]
    },
    mining_scan: {
      max_depth: ts.maxDepth,
      dug_cells: ts.dugCells.size | 0,
      tunnel_cells: ts.tunnelCells | 0,
      caves_found: ts.cavesFound | 0,
      can_return_to_anchor: canReturn,
      deaths: ts.deaths | 0
    },
    economy_scan: {
      trades_completed: ts.tradesCompleted | 0,
      villager_rolls: ts.villagerRolls | 0,
      villager_trades: ts.villagerTrades | 0,
      skill_trades: ts.skillTrades | 0,
      chunks_explored: ts.chunksExplored.size | 0,
      redstone_links: ts.redstoneLinks | 0,
      tool_tier: ts.inv.tool_tier | 0,
      diamond_tools: ts.inv.diamond_tools | 0,
      diamond_sources: [...ts.diamondSources]
    },
    biometric_scan: buildBiometricSummary(bs),
    ...(ts.combat ? {
      combat_scan: {
        mobs_killed: ts.combat.mobs_killed | 0,
        mob_drops: ts.combat.mob_drops | 0,
        damage_dealt: ts.combat.damage_dealt | 0,
        damage_taken: ts.combat.damage_taken | 0,
        deaths: ts.combat.deaths | 0,
        player_hp: ts.combat.player_hp | 0,
        pillared_up: Boolean(ts.combat.pillared_up),
        hid_in_hole: Boolean(ts.combat.hid_in_hole),
        killed_when_equipped: Boolean(ts.combat.killed_when_equipped),
        mob_kited_steps: ts.combat.mob_kited_steps | 0,
        mob_rushed_steps: ts.combat.mob_rushed_steps | 0,
        steps_used: ts.combat.steps_used | 0,
        emergent_tactics: [
          ...(ts.combat.pillared_up ? ['pillar_up'] : []),
          ...(ts.combat.hid_in_hole ? ['hide_in_hole'] : []),
          ...(ts.combat.killed_when_equipped ? ['equip_kill'] : []),
          ...(ts.combat.mob_kited_steps > 0 ? ['mob_kite'] : []),
          ...(ts.combat.mob_rushed_steps > 0 ? ['mob_rush'] : [])
        ]
      }
    } : {}),
    trace: ts.trace.slice(0, 128)
  };
}
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
    case 'click':
      return {
        a: 'click',
        slot: Math.round(num(o.slot, -999, 512, 0, `${where}.slot`)),
        button: Math.round(num(o.button, 0, 10, 0, `${where}.button`)),
        mode: Math.round(num(o.mode, 0, 6, 0, `${where}.mode`))
      };
    case 'close_window':
      return { a: 'close_window' };
    case 'use_block':
      return {
        a: 'use_block',
        dx: Math.round(num(o.dx, -4, 4, 0, `${where}.dx`)),
        dy: Math.round(num(o.dy, -2, 2, -1, `${where}.dy`)),
        dz: Math.round(num(o.dz, -4, 4, 0, `${where}.dz`)),
        face: Math.round(num(o.face, 0, 5, 1, `${where}.face`)),
        hand: Math.round(num(o.hand, 0, 1, 0, `${where}.hand`))
      };
    case 'swing':
      return {
        a: 'swing',
        hand: Math.round(num(o.hand, 0, 1, 0, `${where}.hand`))
      };
    case 'drop':
      if (o.all !== undefined && typeof o.all !== 'boolean') fail(`${where}.all must be a boolean`);
      return {
        a: 'drop',
        slot: Math.round(num(o.slot, 0, 44, 36, `${where}.slot`)),
        all: o.all === true
      };
    case 'policy': {
      const preset = cleanText(o.preset, 32, `${where}.preset`).toLowerCase();
      if (preset && preset !== 'auto' && !PREBAKED_POLICIES[preset]) {
        fail(`${where}.preset: unknown policy preset ${JSON.stringify(preset)}`);
      }
      const weightsB64 = cleanText(o.weights_b64, 1024, `${where}.weights_b64`);
      if (weightsB64) decodePolicyWeights(weightsB64, `${where}.weights_b64`);
      else if (o.weights !== undefined) decodePolicyWeights(o.weights, `${where}.weights`);
      const resolvedB64 = weightsB64
        || (preset && preset !== 'auto' ? PREBAKED_POLICIES[preset] : '')
        || (o.weights !== undefined ? Buffer.from(decodePolicyWeights(o.weights, `${where}.weights`).buffer).toString('base64') : '');
      return {
        a: 'policy',
        ...(preset ? { preset } : {}),
        ...(o.persona ? { persona: cleanText(o.persona, 64, `${where}.persona`).toLowerCase() } : {}),
        ...(o.mob ? { mob: cleanText(o.mob, 32, `${where}.mob`).toLowerCase() } : {}),
        ...(o.mob_weights_b64 ? { mob_weights_b64: cleanText(o.mob_weights_b64, 512, `${where}.mob_weights_b64`) } : {}),
        weights_b64: resolvedB64 || (preset === 'auto' ? '' : PREBAKED_POLICIES.diamond),
        steps: Math.round(num(o.steps, 1, 200, 24, `${where}.steps`)),
        step_ms: Math.round(num(o.step_ms, 0, 5000, 150, `${where}.step_ms`)),
        temp_shift: Math.round(num(o.temp_shift, 4, 16, 10, `${where}.temp_shift`)),
        seed: Math.round(num(o.seed, -2147483648, 2147483647, 1, `${where}.seed`)),
        role: Math.round(num(o.role, -127, 127, 0, `${where}.role`))
      };
    }
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
  let hasPolicyAction = false;
  const bots = req.bots.map((b, i) => {
    if (!b || typeof b !== 'object') fail(`bots[${i}] must be an object`);
    if (typeof b.name !== 'string' || !FUZZ_NAME_RE.test(b.name)) fail(`bots[${i}].name must match ${FUZZ_NAME_RE}`);
    if (names.has(b.name.toLowerCase())) fail(`duplicate bot name ${b.name}`);
    names.add(b.name.toLowerCase());
    if (!Array.isArray(b.program)) fail(`bots[${i}].program must be an array`);
    if (b.program.length > FUZZ_MAX_PROGRAM) fail(`bots[${i}].program longer than ${FUZZ_MAX_PROGRAM}`);
    const persona = b.persona !== undefined ? cleanText(b.persona, 64, `bots[${i}].persona`).toLowerCase() : undefined;
    const program = b.program.map((a, k) => parseAction(a, `bots[${i}].program[${k}]`));
    if (program.some((x) => x.a === 'policy')) hasPolicyAction = true;
    return { name: b.name, ...(persona ? { persona } : {}), program };
  });
  if (req.telemetry !== undefined && typeof req.telemetry !== 'boolean') fail('telemetry must be a boolean');
  if (req.task_mode !== undefined && typeof req.task_mode !== 'boolean') fail('task_mode must be a boolean');
  const telemetry = req.telemetry === true;
  const taskMode = req.task_mode === true || fuzzTaskMode || hasPolicyAction;
  return { id, cmd, maxMs, bots, telemetry, taskMode };
}

const NODE_KINDS = ['root', 'literal', 'argument'];
let prismarineWindows = null;
try { prismarineWindows = require('prismarine-windows'); } catch (e) { prismarineWindows = null; }

function jsonSafeValue(v, depth = 0) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'bigint') {
    const n = Number(v);
    return Number.isSafeInteger(n) ? n : v.toString();
  }
  if (typeof v === 'number') return Number.isFinite(v) ? v : String(v);
  if (typeof v === 'boolean' || typeof v === 'string') return v;
  if (depth > 8) return null;
  if (Array.isArray(v)) {
    return v.map((x) => jsonSafeValue(x, depth + 1));
  }
  if (typeof v === 'object') {
    const out = {};
    for (const k of Object.keys(v)) out[k] = jsonSafeValue(v[k], depth + 1);
    return out;
  }
  return String(v);
}

/** Decode a 64-bit integer bound from brigadier:long min/max properties. */
function decodeLongBound(v) {
  if (v === null || v === undefined) return undefined;
  if (typeof v === 'bigint') {
    const n = Number(v);
    return Number.isSafeInteger(n) ? n : v.toString();
  }
  if (typeof v === 'number') return Number.isFinite(v) ? v : String(v);
  if (typeof v === 'object') {
    if (typeof v.valueOf === 'function') {
      const val = v.valueOf();
      if (typeof val === 'bigint') {
        const n = Number(val);
        return Number.isSafeInteger(n) ? n : val.toString();
      }
    }
    if (Array.isArray(v) && v.length === 2 && Number.isInteger(v[0]) && Number.isInteger(v[1])) {
      const big = (BigInt(v[0]) << 32n) | BigInt(v[1] >>> 0);
      const n = Number(big);
      return Number.isSafeInteger(n) ? n : big.toString();
    }
  }
  return jsonSafeValue(v);
}

/** Serialise a declare_commands packet into a normalised Brigadier node graph. */
function serializeCommandPacket(pkt) {
  const rawNodes = (pkt && Array.isArray(pkt.nodes)) ? pkt.nodes : [];
  const rootIndex = (pkt && Number.isInteger(pkt.rootIndex) && pkt.rootIndex >= 0 && pkt.rootIndex < rawNodes.length)
    ? pkt.rootIndex : 0;
  const nodes = rawNodes.map((raw, index) => {
    const flags = (raw && typeof raw.flags === 'object' && raw.flags !== null) ? raw.flags : {};
    const typeNum = typeof flags.command_node_type === 'number'
      ? flags.command_node_type
      : (typeof raw?.flags === 'number' ? (raw.flags & 0x03) : 0);
    const kind = raw && typeof raw.kind === 'string'
      ? raw.kind
      : (NODE_KINDS[typeNum] || 'unknown');
    const extra = (raw && typeof raw.extraNodeData === 'object' && raw.extraNodeData !== null)
      ? raw.extraNodeData : {};
    const name = typeof extra.name === 'string'
      ? extra.name
      : (typeof raw?.name === 'string' ? raw.name : '');
    const parser = kind === 'argument'
      ? (typeof extra.parser === 'string' ? extra.parser : (typeof raw?.parser === 'string' ? raw.parser : null))
      : null;
    const rawProps = extra.properties !== undefined ? extra.properties : raw?.properties;
    let properties = {};
    if (kind === 'argument' && rawProps !== undefined && rawProps !== null) {
      properties = (typeof rawProps === 'object' && !Array.isArray(rawProps))
        ? jsonSafeValue(rawProps)
        : { value: jsonSafeValue(rawProps) };
      if (parser === 'brigadier:long' && typeof rawProps === 'object' && !Array.isArray(rawProps)) {
        if (rawProps.min !== undefined) properties.min = decodeLongBound(rawProps.min);
        if (rawProps.max !== undefined) properties.max = decodeLongBound(rawProps.max);
      }
    }
    const children = Array.isArray(raw?.children)
      ? raw.children.filter((c) => Number.isInteger(c) && c >= 0 && c < rawNodes.length)
      : [];
    const hasRedirect = flags.has_redirect_node !== undefined
      ? Boolean(flags.has_redirect_node)
      : (typeof raw?.flags === 'number' ? Boolean(raw.flags & 0x08) : (raw?.redirectNode !== undefined || raw?.redirect !== undefined));
    const redirVal = raw?.redirectNode !== undefined ? raw.redirectNode : raw?.redirect;
    const redirect = (hasRedirect && Number.isInteger(redirVal) && redirVal >= 0 && redirVal < rawNodes.length)
      ? redirVal : null;
    const executable = flags.has_command !== undefined
      ? Boolean(flags.has_command)
      : (typeof raw?.flags === 'number' ? Boolean(raw.flags & 0x04) : Boolean(raw?.executable));
    const hasSuggest = flags.has_custom_suggestions !== undefined
      ? Boolean(flags.has_custom_suggestions)
      : (typeof raw?.flags === 'number' ? Boolean(raw.flags & 0x10) : Boolean(extra.suggestionType || raw?.suggestionType || raw?.suggestions));
    const suggestRaw = extra.suggestionType || raw?.suggestionType || raw?.suggestions || null;
    const suggestionType = (hasSuggest && typeof suggestRaw === 'string') ? suggestRaw : null;
    return {
      index,
      kind,
      name,
      parser,
      properties,
      children,
      redirect,
      executable,
      suggestions: suggestionType,
      suggestionType
    };
  });
  return { rootIndex, nodes };
}

function parseDiscover(req) {
  const id = req.id;
  if (!((typeof id === 'string' && id.length > 0 && id.length <= 64) || Number.isSafeInteger(id))) {
    fail('id must be a non-empty string (<=64) or an integer');
  }
  const tier = cleanText(req.tier || 'default', 32, 'tier') || 'default';
  const rawName = req.bot !== undefined ? req.bot : (req.name !== undefined ? req.name : `fzd_${tier.slice(0, 8)}`);
  if (typeof rawName !== 'string' || !FUZZ_NAME_RE.test(rawName)) {
    fail(`bot must match ${FUZZ_NAME_RE}`);
  }
  const maxMs = Math.round(num(req.max_ms, 1000, 300000, fuzzSpawnTimeoutMs, 'max_ms'));
  return { id, tier, username: rawName, maxMs };
}

/** Derive upper container slot count from an open_window inventoryType, or null when unknown. */
function resolveWindowSlots(windowType, version) {
  if (windowType === null || windowType === undefined) return null;
  if (prismarineWindows) {
    try {
      const pw = prismarineWindows(version || activeVersion || '1.20.4');
      if (pw && pw.windows) {
        if (typeof windowType === 'string' && pw.windows[windowType] && pw.windows[windowType].inventory) {
          const start = pw.windows[windowType].inventory.start;
          if (Number.isInteger(start) && start >= 0) return start;
        }
        if (Number.isInteger(windowType)) {
          for (const info of Object.values(pw.windows)) {
            if (info && info.type === windowType && info.inventory && Number.isInteger(info.inventory.start)) {
              return info.inventory.start;
            }
          }
        }
      }
    } catch (e) { /* fall through to static generic table */ }
  }
  if (typeof windowType === 'string') {
    const m = /^minecraft:generic_9x([1-6])$/.exec(windowType);
    if (m) return Number(m[1]) * 9;
    if (windowType === 'minecraft:generic_3x3') return 9;
    if (windowType === 'minecraft:hopper' || windowType === 'minecraft:brewing_stand') return 5;
    if (windowType === 'minecraft:shulker_box') return 27;
  }
  if (Number.isInteger(windowType)) {
    if (windowType >= 0 && windowType <= 5) return (windowType + 1) * 9;
    if (windowType === 6) return 9;
  }
  return null;
}

/** Parse an open_window packet into a passive telemetry event object. */
function parseWindowPacket(botName, pkt, version) {
  if (!pkt || typeof pkt !== 'object') return null;
  const windowId = Number.isInteger(pkt.windowId) ? pkt.windowId : 0;
  const windowType = pkt.inventoryType !== undefined ? pkt.inventoryType : (pkt.windowType !== undefined ? pkt.windowType : null);
  const explicitSlots = Number.isInteger(pkt.slotCount) ? pkt.slotCount : (Number.isInteger(pkt.slots) ? pkt.slots : null);
  const slots = explicitSlots !== null ? explicitSlots : resolveWindowSlots(windowType, version);
  const title = pkt.windowTitle !== undefined ? reasonText(pkt.windowTitle).slice(0, 256) : '';
  return {
    ev: 'window',
    bot: botName,
    window_id: windowId,
    window_type: windowType,
    slots,
    title
  };
}

/** Attach passive protocol listeners: declare_commands only for discover, and
 *  update_time/keep_alive/open_window only when ep.telemetry is enabled. */
function attachProtocolTelemetry(ep, conn, bot, onCommands) {
  const client = bot && bot._client;
  if (!client || typeof client.on !== 'function') return;
  conn.lastUpdateTimeAt = 0;
  conn.lastKeepAliveAt = 0;
  conn.keepAliveMs = null;
  conn.openWindow = null;
  if (typeof onCommands === 'function') {
    client.on('declare_commands', (pkt) => {
      try {
        const graph = serializeCommandPacket(pkt);
        onCommands(graph);
      } catch (err) {
        ep.emit({ ev: 'bot_error', bot: conn.username, text: `declare_commands parse failed: ${err && err.message ? err.message : err}`.slice(0, 256) });
      }
    });
  }
  client.on('open_window', (pkt) => {
    const w = parseWindowPacket(conn.username, pkt, bot.version || activeVersion);
    if (!w) return;
    conn.openWindow = { windowId: w.window_id, windowType: w.window_type, slots: w.slots, title: w.title, stateId: 0 };
    if (ep && ep.telemetry) ep.emit(w);
  });
  client.on('window_items', (pkt) => {
    if (pkt && conn.openWindow && pkt.windowId === conn.openWindow.windowId && Number.isInteger(pkt.stateId)) {
      conn.openWindow.stateId = pkt.stateId;
    }
  });
  client.on('set_slot', (pkt) => {
    if (pkt && conn.openWindow && pkt.windowId === conn.openWindow.windowId && Number.isInteger(pkt.stateId)) {
      conn.openWindow.stateId = pkt.stateId;
    }
  });
  client.on('close_window', () => {
    conn.openWindow = null;
  });
  if (!ep || !ep.telemetry) return;
  client.on('keep_alive', () => {
    const now = Date.now();
    // Server keep_alive inter-arrival interval in ms (not round-trip latency).
    if (conn.lastKeepAliveAt > 0) conn.keepAliveMs = now - conn.lastKeepAliveAt;
    conn.lastKeepAliveAt = now;
  });
  client.on('update_time', () => {
    const now = Date.now();
    if (conn.spawned && conn.lastUpdateTimeAt > 0) {
      const gapMs = now - conn.lastUpdateTimeAt;
      ep.emit({ ev: 'tick_gap', bot: conn.username, ms: gapMs, at: now, keepalive_ms: conn.keepAliveMs });
    }
    conn.lastUpdateTimeAt = now;
  });
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
  if (ep && ep.telemetry && conn.spawned && conn.lastUpdateTimeAt > 0) {
    const now = Date.now();
    const tailGap = now - conn.lastUpdateTimeAt;
    if (tailGap >= 500) {
      ep.emit({ ev: 'tick_gap', bot: conn.username, ms: tailGap, at: now, keepalive_ms: conn.keepAliveMs });
    }
  }
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
    attachProtocolTelemetry(ep, conn, bot, bs.onCommands);
    if (!keepWorld && !(ep && ep.taskMode)) {
      bot.once('login', () => { for (const p of WORLD_PACKETS) bot._client.removeAllListeners(p); });
    }
    bot.on('spawn', () => {
      if (!conn.lastUpdateTimeAt) conn.lastUpdateTimeAt = Date.now();
      ep.emit({ ev: 'spawn', bot: conn.username, pos: posOf(bot) });
      if (!conn.spawned) {
        conn.spawned = true;
        bs.spawns++;
        clearTimeout(conn.spawnTimer);
        settle('spawn');
      }
    });
    bot.on('death', () => {
      if (bs.taskState) bs.taskState.deaths = (bs.taskState.deaths + 1) | 0;
      ep.emit({ ev: 'death', bot: conn.username });
    });
    bot.on('forcedMove', () => {
      if (bs.taskState) bs.taskState.tpPending = true;
      ep.emit({ ev: 'tp', bot: conn.username, pos: posOf(bot) });
    });
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
    const spawnWaitMs = ep.spawnTimeoutMs || fuzzSpawnTimeoutMs;
    conn.spawnTimer = setTimeout(() => {
      if (conn.spawned || conn.ended) return;
      ep.emit({ ev: 'bot_error', bot: conn.username, text: `no spawn within ${spawnWaitMs} ms` });
      settle('end');
      fuzzDisconnect(ep, bs, 'spawn timeout');
    }, spawnWaitMs);
  });
}

/** Quit the current connection; force-close after a grace period so the
 *  episode never waits on a silent socket. */
function fuzzDisconnect(ep, bs, why) {
  const conn = bs.conn;
  if (!conn || conn.ended) return Promise.resolve();
  if (conn.spawnTimer) {
    clearTimeout(conn.spawnTimer);
    conn.spawnTimer = null;
  }
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
  const bio = bs && bs.taskState && bs.taskState.biometrics;
  for (let k = 0; k < steps; k++) {
    if (ep.aborted || onlineBot(bs) !== bot || !posOf(bot)) return;
    const p = bot.entity.position.offset(act.dx / steps, 0, act.dz / steps);
    bot.entity.position = p;
    try {
      if (bio && bio.persona && bio.persona.mouse_gcd_deg > 0) {
        const g = bio.persona.mouse_gcd_deg;
        bio.yaw_deg = (bio.yaw_deg + ((k & 1) ? 2 * g : 3 * g)) % 360.0;
        bio.pitch_deg = Math.max(-60.0, Math.min(60.0, bio.pitch_deg + ((k & 1) ? g : -g)));
        bot._client.write('position_look', { x: p.x, y: p.y, z: p.z, yaw: bio.yaw_deg, pitch: bio.pitch_deg,
          onGround: true, flags: { onGround: true, hasHorizontalCollision: false } });
      } else {
        bot._client.write('position', { x: p.x, y: p.y, z: p.z, onGround: true,
          flags: { onGround: true, hasHorizontalCollision: false } });
      }
    } catch (e) { return; }
    await epSleep(ep, act.ms / steps);
  }
}

async function runFuzzProgram(ep, bs) {
  if (bs.joinDelayMs > 0) await epSleep(ep, bs.joinDelayMs);
  if (ep.aborted) return;
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
        case 'click': {
          const w = bs.conn && bs.conn.openWindow;
          const winId = w && Number.isInteger(w.windowId) ? w.windowId : 0;
          const stateId = w && Number.isInteger(w.stateId) ? w.stateId : 0;
          const maxSlot = w && Number.isInteger(w.slots) && w.slots > 0 ? w.slots - 1 : 53;
          const slot = Math.max(-999, Math.min(maxSlot, Number.isInteger(act.slot) ? act.slot : 0));
          bs.actionId = (bs.actionId || 0) + 1;
          try {
            bot._client.write('window_click', {
              windowId: winId,
              stateId,
              slot,
              mouseButton: act.button || 0,
              action: bs.actionId,
              mode: act.mode || 0,
              changedSlots: [],
              cursorItem: { present: false }
            });
          } catch (e) { /* protocol variation across MC versions */ }
          break;
        }
        case 'close_window': {
          const w = bs.conn && bs.conn.openWindow;
          const winId = w && Number.isInteger(w.windowId) ? w.windowId : 0;
          try { bot._client.write('close_window', { windowId: winId }); } catch (e) { /* ignore */ }
          if (bs.conn) bs.conn.openWindow = null;
          break;
        }
        case 'use_block': {
          const p = posOf(bot);
          if (p) {
            const bx = Math.floor(p.x) + (act.dx || 0);
            const by = Math.floor(p.y) + (act.dy !== undefined ? act.dy : -1);
            const bz = Math.floor(p.z) + (act.dz || 0);
            bs.actionId = (bs.actionId || 0) + 1;
            try {
              bot._client.write('block_place', {
                location: { x: bx, y: by, z: bz },
                direction: Number.isInteger(act.face) ? act.face : 1,
                hand: Number.isInteger(act.hand) ? act.hand : 0,
                cursorX: 0.5,
                cursorY: 0.5,
                cursorZ: 0.5,
                insideBlock: false,
                sequence: bs.actionId
              });
            } catch (e) { /* protocol variation across MC versions */ }
          }
          break;
        }
        case 'swing': {
          try {
            bot._client.write('arm_animation', { hand: Number.isInteger(act.hand) ? act.hand : 0 });
          } catch (e) { /* ignore */ }
          break;
        }
        case 'drop': {
          try {
            bot._client.write('block_dig', {
              status: act.all ? 3 : 4,
              location: { x: 0, y: 0, z: 0 },
              face: 0,
              sequence: 0
            });
          } catch (e) { /* ignore */ }
          break;
        }
        case 'policy': {
          const weights = resolvePolicyWeights(act, bs.botIdx || 0);
          const nextUint32 = makeIntRng(act.seed);
          const ts = ensureTaskState(bs, act.role);
          ts.role = clampInt8(act.role);
          ensureBiometricState(bs, act.persona || bs.persona || 'distinct');
          if (act.preset === 'combat' || act.mob) {
            ensureCombatState(bs, {
              role: act.role,
              seed: act.seed,
              tempShift: act.temp_shift,
              mobKind: act.mob === 'ranged' ? 'ranged' : 'melee',
              mobWeightsB64: act.mob_weights_b64
            });
          }
          if (onlineBot(bs)) await epSleep(ep, 350);
          const logits = new Int32Array(POLICY_NUM_ACTIONS);
          for (let stepIdx = 0; stepIdx < act.steps; stepIdx++) {
            if (ep.aborted || bs.kicked || !onlineBot(bs)) break;
            const ctx = computeContextInt8(onlineBot(bs), bs);
            const skillIdx = selectPolicyActionInt(weights, ctx, act.temp_shift, nextUint32, logits);
            const res = await executeMicroSkill(ep, bs, onlineBot(bs), skillIdx, stepIdx);
            ep.emit({
              ev: 'policy_step',
              bot: bs.username,
              step: stepIdx,
              skill: res.skill,
              skill_idx: skillIdx,
              logit: logits[skillIdx] | 0,
              wire_act: res.wireAct
            });
            const sleepMs = act.step_ms > 0
              ? (res.stepDelayMs ? Math.min(act.step_ms * 2, res.stepDelayMs) : act.step_ms)
              : 0;
            if (sleepMs > 0) await epSleep(ep, sleepMs);
          }
          if (onlineBot(bs)) await epSleep(ep, 250);
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
    if (ep.taskMode || bs.taskState) {
      ep.emit(buildTaskSummary(bs));
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
  const ep = {
    id: spec.id,
    cmd: spec.cmd,
    telemetry: Boolean(spec.telemetry),
    taskMode: Boolean(spec.taskMode),
    sharedExchange: { Pool: 0 },
    start: Date.now(),
    aborted: null,
    wakers: new Set(),
    sock
  };
  ep.emit = (obj) => fuzzSend(ep.sock, { ...obj, id: ep.id, t: Date.now() - ep.start });
  fuzzEpisode = ep;
  fuzzSend(sock, { ev: 'accepted', id: spec.id, bots: spec.bots.length });
  console.log(`[fuzz] episode ${spec.id}: ${spec.bots.length} bot(s), cmd=/${spec.cmd}, max ${spec.maxMs} ms`);
  const deadline = setTimeout(() => abortEpisode(ep, 'timeout'), spec.maxMs);
  const states = spec.bots.map((b, idx) => ({ botIdx: idx, baseName: b.name, username: b.name, persona: b.persona || 'distinct', program: b.program, generation: 0,
    joinDelayMs: spec.bots.length > 4 ? idx * 25 : 0,
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

async function runFuzzDiscover(sock, spec) {
  await resolveVersion();
  const ep = {
    id: spec.id,
    telemetry: false,
    spawnTimeoutMs: spec.maxMs,
    start: Date.now(),
    aborted: null,
    wakers: new Set(),
    sock
  };
  ep.emit = (obj) => fuzzSend(ep.sock, { ...obj, id: ep.id, t: Date.now() - ep.start });
  fuzzEpisode = ep;
  fuzzSend(sock, { ev: 'accepted', id: spec.id, tier: spec.tier, bot: spec.username });
  console.log(`[fuzz] discover ${spec.id}: tier=${spec.tier}, bot=${spec.username}, max ${spec.maxMs} ms`);
  const deadline = setTimeout(() => abortEpisode(ep, 'timeout'), spec.maxMs);
  let captured = null;
  let resolveGraph;
  const graphPromise = new Promise((resolve) => { resolveGraph = resolve; });
  ep.wakers.add(() => resolveGraph(null));
  const bs = {
    baseName: spec.username,
    username: spec.username,
    program: [],
    generation: 0,
    conn: null,
    kicked: false,
    joins: 0,
    spawns: 0,
    ran: 0,
    skipped: 0,
    lastCmd: null,
    onCommands: (graph) => {
      if (!captured) {
        captured = graph;
        if (bs.conn && bs.conn.spawnTimer) {
          clearTimeout(bs.conn.spawnTimer);
          bs.conn.spawnTimer = null;
        }
        resolveGraph(graph);
      }
    }
  };
  ep.states = [bs];
  const connectPromise = fuzzConnect(ep, bs).then((res) => {
    if (res !== 'spawn' || !bs.conn || bs.conn.ended) resolveGraph(null);
    else bs.conn.waiters.push(() => resolveGraph(null));
    return res;
  });
  try {
    await graphPromise;
  } finally {
    clearTimeout(deadline);
    await fuzzDisconnect(ep, bs, 'discover end');
    await connectPromise;
  }
  const ms = Date.now() - ep.start;
  const out = captured || { rootIndex: 0, nodes: [] };
  fuzzSend(ep.sock, {
    ev: 'commands',
    id: spec.id,
    tier: spec.tier,
    bot: spec.username,
    rootIndex: out.rootIndex,
    nodes: out.nodes,
    captured: !!captured,
    ms
  });
  fuzzSend(ep.sock, {
    ev: 'done',
    id: spec.id,
    tier: spec.tier,
    ms,
    reason: captured ? 'complete' : (ep.aborted || 'no_commands'),
    bots: [{ name: bs.baseName, last: bs.username, joins: bs.joins, spawns: bs.spawns, kicked: bs.kicked }]
  });
  console.log(`[fuzz] discover ${spec.id} (${spec.tier}) done in ${ms} ms (${out.nodes.length} node(s))`);
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
    case 'discover': {
      if (shuttingDown) { fuzzSend(sock, { ev: 'error', id, error: 'stopping' }); return; }
      if (fuzzEpisode) { fuzzSend(sock, { ev: 'error', id, error: `busy with episode ${fuzzEpisode.id}` }); return; }
      let spec;
      try { spec = parseDiscover(req); } catch (e) { fuzzSend(sock, { ev: 'error', id, error: e.message }); return; }
      runFuzzDiscover(sock, spec).catch((e) => {
        fuzzEpisode = null;
        fuzzSend(sock, { ev: 'error', id, error: `discover failed: ${e && e.message ? e.message : e}` });
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

if (require.main === module) {
  if (fuzzMode) runFuzz(); else if (workers > 1) runParent(); else runBots().catch((err) => {
    console.error(`[bench] startup failed: ${err && err.message ? err.message : err}`);
    process.exit(2);
  });
} else {
  module.exports = {
    serializeCommandPacket,
    decodeLongBound,
    jsonSafeValue,
    parseDiscover,
    resolveWindowSlots,
    parseWindowPacket,
    attachProtocolTelemetry,
    parseAction,
    parseEpisode,
    reasonText,
    POLICY_NUM_FEATURES,
    POLICY_NUM_ACTIONS,
    POLICY_WEIGHT_BYTES,
    POLICY_FEATURE_NAMES,
    POLICY_MICRO_SKILLS,
    POLICY_PRESET_ORDER,
    PREBAKED_POLICIES,
    MOB_NUM_FEATURES,
    MOB_NUM_ACTIONS,
    MOB_WEIGHT_BYTES,
    MOB_FEATURE_NAMES,
    MOB_MICRO_SKILLS,
    PREBAKED_MOB_POLICIES,
    BOT_PERSONA_PROFILES,
    registerSleuthPersonas,
    resolveBotPersona,
    ensureBiometricState,
    recordBiometricStep,
    buildBiometricSummary,
    evaluateSleuthIdentityPair,
    simulateLogoffMutatedRelogEpisode,
    makeIntRng,
    clampInt8,
    intSatRatio,
    decodePolicyWeights,
    decodeMobPolicyWeights,
    resolvePolicyWeights,
    selectPolicyActionInt,
    selectMobActionInt,
    computeMobContextInt8,
    computePlayerCombatContextInt8,
    ensureTaskState,
    ensureCombatState,
    computeContextInt8,
    executeMicroSkill,
    buildTaskSummary
  };
}
