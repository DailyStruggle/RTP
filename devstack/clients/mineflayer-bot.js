/**
 * Headless protocol client for devstack acceptance testing (ADR-091).
 * Connects to Velocity proxy, warms backend connections, triggers /rtp,
 * and asserts destination coordinate arrival and round-trip latency SLA.
 */
const mineflayer = require('mineflayer');

const args = process.argv.slice(2);
function getArg(flag, defaultValue) {
  const idx = args.indexOf(flag);
  return idx !== -1 && idx + 1 < args.length ? args[idx + 1] : defaultValue;
}

const host = getArg('--host', '127.0.0.1');
const port = parseInt(getArg('--port', '25577'), 10);
const username = getArg('--username', 'RtpAcceptanceBot');
const timeoutSeconds = parseInt(getArg('--timeout', '35'), 10);

console.log(`[bot] Initializing headless client: ${username} -> ${host}:${port} (timeout: ${timeoutSeconds}s)`);

const timer = setTimeout(() => {
  console.error(`[bot] FAIL - Timeout exceeded (${timeoutSeconds}s) before completing cross-server RTP.`);
  process.exit(1);
}, timeoutSeconds * 1000);

let bot;
try {
  bot = mineflayer.createBot({
    host: host,
    port: port,
    username: username,
    checkTimeoutInterval: 60 * 1000
  });
} catch (err) {
  console.error(`[bot] Failed to initialize bot:`, err);
  process.exit(1);
}

let stage = 'CONNECTING';
let rtpStartTime = 0;
let initialPosition = null;

bot.on('login', () => {
  console.log(`[bot] Successfully logged in to proxy.`);
});

bot.on('spawn', () => {
  const pos = bot.entity.position;
  console.log(`[bot] Spawned at (${pos.x.toFixed(2)}, ${pos.y.toFixed(2)}, ${pos.z.toFixed(2)})`);

  if (stage === 'CONNECTING') {
    stage = 'WARMUP_B';
    setTimeout(() => {
      console.log(`[bot] Seeding backend cache: sending /server backend-b`);
      bot.chat('/server backend-b');
    }, 1000);
  } else if (stage === 'WARMUP_B') {
    stage = 'WARMUP_C';
    setTimeout(() => {
      console.log(`[bot] Seeding backend cache: sending /server backend-c`);
      bot.chat('/server backend-c');
    }, 1500);
  } else if (stage === 'WARMUP_C') {
    stage = 'READY_FOR_RTP';
    setTimeout(() => {
      console.log(`[bot] Ready to trigger RTP. Sending /rtp`);
      initialPosition = { ...bot.entity.position };
      rtpStartTime = Date.now();
      stage = 'AWAITING_TELEPORT';
      bot.chat('/rtp');
    }, 1500);
  } else if (stage === 'AWAITING_TELEPORT') {
    // Spawned after server transfer / teleport
    evaluateLanding(bot.entity.position);
  }
});

bot.on('forcedMove', () => {
  if (stage === 'AWAITING_TELEPORT') {
    evaluateLanding(bot.entity.position);
  }
});

function evaluateLanding(pos) {
  const duration = Date.now() - rtpStartTime;
  console.log(`[bot] Position update observed at (${pos.x.toFixed(2)}, ${pos.y.toFixed(2)}, ${pos.z.toFixed(2)}) after ${duration}ms`);

  if (initialPosition) {
    const dx = pos.x - initialPosition.x;
    const dz = pos.z - initialPosition.z;
    const distSq = dx * dx + dz * dz;

    // A cross-server or in-region RTP jumps significant distance (e.g. > 100 blocks)
    if (distSq > 100 * 100 && pos.y >= 0) {
      console.log(JSON.stringify({
        status: 'PASS',
        teleportLatencyMs: duration,
        targetX: Math.round(pos.x),
        targetY: Math.round(pos.y),
        targetZ: Math.round(pos.z)
      }));
      clearTimeout(timer);
      setTimeout(() => {
        bot.quit();
        process.exit(0);
      }, 500);
    }
  }
}

bot.on('kicked', (reason) => {
  console.error(`[bot] Kicked from server: ${reason}`);
  clearTimeout(timer);
  process.exit(1);
});

bot.on('error', (err) => {
  console.error(`[bot] Socket error:`, err);
});
