// Extra players for GIF recording (docs/publishing/GIF_SHOTLIST.md): joins N offline-mode bots
// with physics on, so they fall and walk like players, and logs what they see.
//
//   node scripts/gif-bots.js [--host 100.111.32.47] [--port 25578] [--count 3] [--prefix gifbot_]
//
// Ctrl+C disconnects them. Uses mineflayer from devstack/clients (npm ci there once).
// Put them into the portal with `./gif-preset.sh fill` on the demo host.
const path = require('path');
const mineflayer = require(require.resolve('mineflayer', { paths: [path.join(__dirname, '..', 'devstack', 'clients')] }));

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && i + 1 < args.length ? args[i + 1] : def;
};
const host = opt('host', '100.111.32.47');
const port = Number(opt('port', '25578'));
const count = Number(opt('count', '3'));
const prefix = opt('prefix', 'gifbot_');

const t0 = Date.now();
const log = (name, msg) => console.log(`${((Date.now() - t0) / 1000).toFixed(1)}s ${name} ${msg}`);
const text = (m) => {
  const s = m && typeof m.toString === 'function' ? m.toString() : '';
  return s && s !== '[object Object]' ? s : JSON.stringify(m);
};

function join(name) {
  const bot = mineflayer.createBot({ host, port, username: name, auth: 'offline', viewDistance: 'tiny' });
  let lastBar = '';
  bot.on('spawn', () => log(name, `spawn ${bot.entity.position.floored()}`));
  bot.on('forcedMove', () => log(name, `moved to ${bot.entity.position.floored()}`));
  bot.on('actionBar', (m) => {
    const s = text(m);
    if (s !== lastBar) { lastBar = s; log(name, `actionbar: ${s}`); }
  });
  bot.on('title', (t, type) => log(name, `title (${type}): ${text(t)}`));
  bot.on('kicked', (r) => log(name, `kicked: ${text(r)}`));
  bot.on('error', (e) => log(name, `error: ${e.message}`));
  bot.on('end', (reason) => {
    log(name, `disconnected (${reason}); rejoining in 5 s`);
    setTimeout(() => join(name), 5000);
  });
}

// Every bot shares one IP: space the joins past the default connection throttle.
for (let i = 0; i < count; i++) setTimeout(() => join(`${prefix}${i}`), i * 1500);
process.on('SIGINT', () => process.exit(0));
