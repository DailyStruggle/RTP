/**
 * Multi-client concurrency stress swarm for devstack acceptance testing (ADR-091).
 * Launches 10-15 concurrent Mineflayer bots into the devstack lobby,
 * coordinates a simultaneous /rtp burst within a tight window (< 1 second),
 * and asserts that every bot either teleports to valid coordinates or
 * receives the configured busy/queue message (Rule S-007).
 */
const mineflayer = require('mineflayer');

const args = process.argv.slice(2);
function getArg(flag, defaultValue) {
  const idx = args.indexOf(flag);
  return idx !== -1 && idx + 1 < args.length ? args[idx + 1] : defaultValue;
}

const host = getArg('--host', '127.0.0.1');
const port = parseInt(getArg('--port', '25577'), 10);
const mcVersion = getArg('--version', '1.21.11');
const botCount = Math.min(15, Math.max(10, parseInt(getArg('--bot-count', '12'), 10)));
const targetRegion = getArg('--target-region', 'backend-a:default');
const commandTemplate = getArg('--command', `/rtp region=${targetRegion}`);
const timeoutSeconds = parseInt(getArg('--timeout', '60'), 10);

console.log(`[swarm] Initializing stress burst swarm: ${botCount} bots targeting ${host}:${port} (version: ${mcVersion}, timeout: ${timeoutSeconds}s, command: "${commandTemplate}")`);

const overallTimer = setTimeout(() => {
  console.error(`[swarm] FAIL - Timeout exceeded (${timeoutSeconds}s) before all bots settled.`);
  evaluateAndExit();
}, timeoutSeconds * 1000);

const bots = [];
const botStates = [];

for (let i = 0; i < botCount; i++) {
  botStates.push({
    id: i,
    username: `StressBot_${i}`,
    stage: 'CONNECTING',
    spawned: false,
    initialPos: null,
    landingPos: null,
    burstTime: 0,
    completionTime: 0,
    outcome: null, // 'TELEPORTED' | 'BUSY' | 'ERROR'
    messages: []
  });
}

let burstTriggered = false;

function checkAllSpawnedAndTriggerBurst() {
  if (burstTriggered) return;
  const spawnedCount = botStates.filter(s => s.spawned).length;
  if (spawnedCount === botCount) {
    burstTriggered = true;
    console.log(`[swarm] All ${botCount} bots successfully spawned and ready! Scheduling synchronized /rtp burst...`);

    // Target a synchronized firing instant in 500ms
    const burstInstant = Date.now() + 500;
    botStates.forEach((state, idx) => {
      const delay = Math.max(0, burstInstant - Date.now());
      setTimeout(() => {
        state.stage = 'BURSTING';
        state.burstTime = Date.now();
        console.log(`[swarm] [${state.username}] Firing "${commandTemplate}" at t=${state.burstTime}`);
        try {
          bots[idx].chat(commandTemplate);
        } catch (err) {
          console.error(`[swarm] [${state.username}] Error sending chat:`, err);
        }
      }, delay);
    });
  }
}

function checkSettled() {
  if (!burstTriggered) return;
  const finishedCount = botStates.filter(s => s.outcome !== null).length;
  if (finishedCount === botCount) {
    clearTimeout(overallTimer);
    setTimeout(evaluateAndExit, 1000);
  }
}

function evaluateAndExit() {
  const finished = botStates.filter(s => s.outcome !== null);
  const teleported = botStates.filter(s => s.outcome === 'TELEPORTED');
  const busy = botStates.filter(s => s.outcome === 'BUSY');
  const failed = botStates.filter(s => s.outcome === 'ERROR' || s.outcome === null);

  console.log(`\n=================== SWARM STRESS RESULTS ===================`);
  console.log(`Total Bots    : ${botCount}`);
  console.log(`Teleported    : ${teleported.length}`);
  console.log(`Busy / Queued : ${busy.length}`);
  console.log(`Failed / Stuck: ${failed.length}`);

  botStates.forEach(s => {
    console.log(`  - [${s.username}] Outcome: ${s.outcome || 'STUCK'} (burst -> settle: ${s.completionTime ? (s.completionTime - s.burstTime) + 'ms' : 'N/A'}) Details: ${s.details || 'none'}`);
  });
  console.log(`============================================================\n`);

  // Disconnect all bots
  bots.forEach(b => {
    try { b.quit(); } catch (e) {}
  });

  if (failed.length === 0 && (teleported.length + busy.length === botCount)) {
    console.log(JSON.stringify({
      status: 'PASS',
      totalBots: botCount,
      teleported: teleported.length,
      busyOrQueued: busy.length,
      failed: failed.length
    }));
    process.exit(0);
  } else {
    console.error(JSON.stringify({
      status: 'FAIL',
      totalBots: botCount,
      teleported: teleported.length,
      busyOrQueued: busy.length,
      failed: failed.length
    }));
    process.exit(1);
  }
}

// Instantiate each bot with a small stagger to avoid socket storm during connection
botStates.forEach((state, i) => {
  setTimeout(() => {
    try {
      const bot = mineflayer.createBot({
        host: host,
        port: port,
        username: state.username,
        version: mcVersion,
        checkTimeoutInterval: 60 * 1000
      });

      bots[i] = bot;

      bot.on('login', () => {
        state.stage = 'LOGGED_IN';
      });

      bot.on('spawn', () => {
        const pos = bot.entity.position;
        if (!state.spawned) {
          state.spawned = true;
          state.stage = 'READY';
          state.initialPos = { ...pos };
          console.log(`[swarm] [${state.username}] Spawned in lobby at (${pos.x.toFixed(1)}, ${pos.y.toFixed(1)}, ${pos.z.toFixed(1)})`);
          checkAllSpawnedAndTriggerBurst();
        } else if (state.stage === 'BURSTING' || state.stage === 'AWAITING_SETTLE') {
          checkMovement(state, pos);
        }
      });

      bot.on('forcedMove', () => {
        if (state.stage === 'BURSTING' || state.stage === 'AWAITING_SETTLE') {
          checkMovement(state, bot.entity.position);
        }
      });

      bot.on('message', (jsonMsg) => {
        const text = jsonMsg.toString();
        state.messages.push(text);
        const lower = text.toLowerCase();

        // Recognize busy or queue messages (Rule S-007)
        if (lower.includes('busy') ||
            lower.includes('spot in line') ||
            lower.includes('already teleporting') ||
            lower.includes('no locations ready') ||
            lower.includes('limit') ||
            lower.includes('cooldown') ||
            lower.includes('queue') ||
            lower.includes('wait')) {
          if (state.outcome === null && (state.stage === 'BURSTING' || state.stage === 'AWAITING_SETTLE')) {
            state.outcome = 'BUSY';
            state.completionTime = Date.now();
            state.details = `Received busy/queue response: "${text.trim()}"`;
            console.log(`[swarm] [${state.username}] S-007 Handled: ${state.details}`);
            checkSettled();
          }
        }
      });

      bot.on('kicked', (reason) => {
        console.error(`[swarm] [${state.username}] Kicked: ${typeof reason === 'object' ? JSON.stringify(reason) : reason}`);
        if (state.outcome === null) {
          state.outcome = 'ERROR';
          state.completionTime = Date.now();
          state.details = `Kicked: ${JSON.stringify(reason)}`;
          checkSettled();
        }
      });

      bot.on('error', (err) => {
        console.error(`[swarm] [${state.username}] Error:`, err);
      });

    } catch (err) {
      console.error(`[swarm] [${state.username}] Failed to create bot:`, err);
      state.outcome = 'ERROR';
      state.details = err.message;
      checkSettled();
    }
  }, i * 150); // 150ms connection stagger
});

function checkMovement(state, currentPos) {
  if (state.outcome !== null) return;
  if (!state.initialPos) return;

  const dx = currentPos.x - state.initialPos.x;
  const dz = currentPos.z - state.initialPos.z;
  const distSq = dx * dx + dz * dz;

  // Jumped more than 100 blocks
  if (distSq > 100 * 100 && currentPos.y >= 0) {
    state.outcome = 'TELEPORTED';
    state.landingPos = { ...currentPos };
    state.completionTime = Date.now();
    state.details = `Teleported to (${Math.round(currentPos.x)}, ${Math.round(currentPos.y)}, ${Math.round(currentPos.z)}) in ${state.completionTime - state.burstTime}ms`;
    console.log(`[swarm] [${state.username}] SUCCESS: ${state.details}`);
    checkSettled();
  }
}
