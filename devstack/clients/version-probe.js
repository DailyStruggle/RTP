#!/usr/bin/env node
// Player-side probe for run-version-sweep.sh: join one server, run /rtp, and
// report what the client saw as one `RESULT {...}` JSON line.
//
//   node version-probe.js <host> <port> <version> <username> <phase>
//
// phase=effects  expects a landing teleport plus the sweep effect group
//                (sound, particle, potion, preteleport console and
//                postteleport player COMMAND chat). Glide and firework are
//                reported, not required. Jars built before the COMMAND clone
//                fix run the last-built command for every COMMAND, so they
//                miss the console one here.
// phase=death    expects the landing teleport, a DEATH-effect death, a
//                respawn, and the preteleport and death-stage console
//                COMMAND chat. setRespawnOnTeleport is off by default, so the
//                respawn point is the world spawn; its distance is reported.
//
// A "busy" reply (e.g. a reload still running) is retried a few times.
//
// Exit 0 = every required observation seen, 1 = missing observation or
// timeout, 2 = kicked / connection error.
const mineflayer = require('mineflayer');

const [host, portArg, version, username, phase] = process.argv.slice(2);
const port = parseInt(portArg || '25598', 10);
const LAND_MIN_BLOCKS = 100;
const SETTLE_MS = 8000;
const TIMEOUT_MS = 90000;
const BUSY_RETRIES = 5;
const BUSY_RETRY_MS = 5000;

const seen = {
  landed: false, landedMs: null, at: null,
  sounds: 0, particles: 0, potions: 0, glide: false, fireworkEntity: false,
  consoleCmd: false, playerCmd: false,
  died: false, respawnAt: null, respawnToLandBlocks: null, deathCmd: false,
  chat: [],
};
let start = 0;
let origin = null;
let landedPos = null;
let finished = false;
let busyRetries = 0;

const bot = mineflayer.createBot({ host, port, username, version, auth: 'offline' });

function finish(code, why) {
  if (finished) return;
  finished = true;
  const required = phase === 'death'
    ? { landed: seen.landed, died: seen.died, respawned: seen.respawnAt !== null, consoleCmd: seen.consoleCmd, deathCmd: seen.deathCmd }
    : { landed: seen.landed, sounds: seen.sounds > 0, particles: seen.particles > 0, potions: seen.potions > 0, consoleCmd: seen.consoleCmd, playerCmd: seen.playerCmd };
  const missing = Object.keys(required).filter((k) => !required[k]);
  if (code === 0 && missing.length) code = 1;
  seen.chat = seen.chat.slice(-12);
  console.log('RESULT ' + JSON.stringify({ phase, version, code, why: why || (missing.length ? 'missing ' + missing.join(',') : 'ok'), missing, ...seen }));
  try { bot.quit(); } catch (_) { /* already closed */ }
  setTimeout(() => process.exit(code), 500);
}

function onLanding() {
  landedPos = bot.entity.position.clone();
  seen.landed = true;
  seen.landedMs = Date.now() - start;
  seen.at = [Math.round(landedPos.x), Math.round(landedPos.y), Math.round(landedPos.z)];
  if (phase !== 'death') setTimeout(() => finish(0), SETTLE_MS);
}

bot.once('spawn', () => {
  setTimeout(() => {
    origin = bot.entity.position.clone();
    start = Date.now();
    // Bare /rtp opens the bundled GUI addon's region picker instead.
    bot.chat('/rtp region=default');
  }, 3000);
});

bot.on('forcedMove', () => {
  if (!start || seen.landed || !origin) return;
  if (bot.entity.position.distanceTo(origin) < LAND_MIN_BLOCKS) return;
  onLanding();
});

bot.on('death', () => { if (start) seen.died = true; });
let respawnSeen = false;
bot.on('spawn', () => {
  if (!seen.died || respawnSeen) return;
  respawnSeen = true;
  // 'spawn' can fire before the respawn position packet; read it a bit later.
  setTimeout(() => {
    const p = bot.entity.position;
    seen.respawnAt = [Math.round(p.x), Math.round(p.y), Math.round(p.z)];
    if (landedPos) seen.respawnToLandBlocks = Math.round(p.distanceTo(landedPos));
    setTimeout(() => finish(0), 2000);
  }, 2000);
});

bot._client.on('world_particles', () => { if (seen.landed) seen.particles++; });
for (const ev of ['sound_effect', 'entity_sound_effect']) {
  bot._client.on(ev, () => { if (start) seen.sounds++; });
}
bot._client.on('entity_effect', (p) => { if (start && bot.entity && p.entityId === bot.entity.id) seen.potions++; });
// Entity flags (metadata index 0) bit 0x80 = fall-flying.
bot._client.on('entity_metadata', (p) => {
  if (!start || !bot.entity || p.entityId !== bot.entity.id || !Array.isArray(p.metadata)) return;
  for (const m of p.metadata) {
    if (m.key === 0 && typeof m.value === 'number' && (m.value & 0x80)) seen.glide = true;
  }
});
bot.on('entitySpawn', (e) => { if (start && e && /firework/i.test(String(e.name || ''))) seen.fireworkEntity = true; });

bot.on('message', (m) => {
  const s = m.toString();
  if (!start) return;
  seen.chat.push(s);
  if (!seen.landed && /\bbusy\b/i.test(s) && busyRetries < BUSY_RETRIES) {
    busyRetries++;
    setTimeout(() => { if (!seen.landed && !finished) bot.chat('/rtp region=default'); }, BUSY_RETRY_MS);
  }
  if (s.includes('sweep-console')) seen.consoleCmd = true;
  if (s.includes('sweep-player')) seen.playerCmd = true;
  if (s.includes('sweep-death')) seen.deathCmd = true;
});

bot.on('kicked', (r) => { seen.chat.push('kicked: ' + JSON.stringify(r)); finish(2, 'kicked'); });
bot.on('error', (e) => { seen.chat.push('error: ' + e.message); finish(2, 'error ' + e.message); });
bot.on('end', (r) => finish(2, 'connection ended: ' + r));
setTimeout(() => finish(1, 'timeout'), TIMEOUT_MS);
