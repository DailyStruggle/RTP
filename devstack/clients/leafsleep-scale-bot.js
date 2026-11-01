/**
 * Headless 16-bot multi-server scale, curve-verification, and MSPT benchmark harness for LeafSleep.
 *
 * Connects 16 concurrent Mineflayer bots across Velocity (proxy-a):
 *   - 8 bots on Paper 1.21.11 (backend-a): SleepBotA1 (OP) + SleepBotA2..SleepBotA8 (non-OP)
 *   - 8 bots on Folia 1.21.11 (backend-b): SleepBotB1 (OP) + SleepBotB2..SleepBotB8 (non-OP)
 *
 * Empirically verifies across the 16-player cluster (eligible=16/40, t=15/39=0.3846):
 *   1. All 5 UnitCurve types (logarithmic, power, smootherstep, sigmoid, linear) produce their
 *      exact mathematical sleep/cancel quorum thresholds across shards.
 *   2. Under each curve, (required - 1) sleepers do NOT prematurely skip, 1 cancel click under
 *      TUG_OF_WAR raises effective required sleepers by +1, and reaching the adjusted quorum
 *      commits dawn idempotently across both Paper and Folia.
 *   3. A 16-bot concurrent vote/cancel/ignore burst stress test does not degrade server TPS or
 *      spike server MSPT / LeafSleep coordinator tick time on either Paper or Folia.
 */
const fs = require('fs');
const path = require('path');
const mineflayer = require('mineflayer');

const args = process.argv.slice(2);
function getArg(flag, defaultValue) {
  const idx = args.indexOf(flag);
  return idx !== -1 && idx + 1 < args.length ? args[idx + 1] : defaultValue;
}

const host = getArg('--host', '127.0.0.1');
const port = parseInt(getArg('--port', '25577'), 10);
const mcVersion = getArg('--version', '1.21.11');
const botsPerShard = parseInt(getArg('--bots-per-shard', '5'), 10);
const timeoutSeconds = parseInt(getArg('--timeout', '240'), 10);
const serversArg = getArg('--servers', null);
const serverA = getArg('--server-a', 'backend-a');
const serverB = getArg('--server-b', 'backend-b');
const serverList = serversArg
  ? serversArg.split(',').map((s) => s.trim()).filter(Boolean)
  : [serverA, serverB];
const stackDir = getArg('--stack-dir', path.resolve(__dirname, '../leafsleep-stack'));

const globalTimer = setTimeout(() => {
  console.error(JSON.stringify({
    status: 'FAIL',
    reason: `Timeout (${timeoutSeconds}s) exceeded during LeafSleep 16-bot scale benchmark`
  }));
  process.exit(1);
}, timeoutSeconds * 1000);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

const hasTickEnd = !!(require('minecraft-data')(mcVersion)?.protocol?.play?.toServer?.types?.packet_tick_end);

function createManagedBot(username, verbose = false) {
  const bot = mineflayer.createBot({
    host,
    port,
    username,
    version: mcVersion,
    checkTimeoutInterval: 90 * 1000
  });

  const origWrite = bot._client.write.bind(bot._client);
  bot._client.write = function (name, params) {
    const res = origWrite(name, params);
    if (hasTickEnd && bot._client.state === 'play' && (name === 'position' || name === 'position_look' || name === 'look' || name === 'flying')) {
      origWrite('tick_end', {});
    }
    return res;
  };

  const messages = [];
  bot.on('messagestr', (msg) => {
    if (msg && msg.trim().length > 0) {
      messages.push({ time: Date.now(), text: msg.trim() });
      if (verbose) {
        console.log(`[${username} chat] ${msg.trim()}`);
      }
    }
  });

  bot.on('error', (err) => {
    console.error(`[${username} error]`, err.message || err);
  });

  bot.on('kicked', (reason) => {
    console.error(`[${username} kicked]`, reason);
  });

  function clearMessages() {
    messages.length = 0;
  }

  async function waitForMessage(predicate, waitMs = 10000, label = 'message') {
    const deadline = Date.now() + waitMs;
    while (Date.now() < deadline) {
      const hit = messages.find((m) => predicate(m.text));
      if (hit) {
        return hit.text;
      }
      await sleep(80);
    }
    const recent = messages.map((m) => m.text).slice(-10);
    throw new Error(`[${username}] Timed out waiting for ${label} (${waitMs}ms). Recent: ${JSON.stringify(recent)}`);
  }

  function waitForSpawn(waitMs = 30000) {
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`[${username}] Timed out waiting for spawn`)), waitMs);
      bot.once('spawn', () => {
        clearTimeout(t);
        resolve();
      });
    });
  }

  return {
    bot,
    username,
    messages,
    clearMessages,
    waitForMessage,
    waitForSpawn
  };
}

async function switchServer(managed, targetServer) {
  let respawned = false;
  const onRespawn = () => {
    respawned = true;
  };
  managed.bot.once('respawn', onRespawn);
  managed.bot.chat(`/server ${targetServer}`);
  const deadline = Date.now() + 4000;
  while (!respawned && Date.now() < deadline) {
    await sleep(120);
  }
  managed.bot.removeListener('respawn', onRespawn);
  await sleep(600);
}

function writeCurveConfigForAllShards(curveSpec) {
  for (const shardId of serverList) {
    const cfgPath = path.join(stackDir, shardId, 'plugins', 'LeafSleep', 'config.yml');
    const yaml = `version: "1.0"
serverShardId: "${shardId}"
defaultQuorumPolicy: "curve"
defaultQuorumValue: 25
defaultCancelPolicy: "tug of war"
sleepCurve:
  type: "${curveSpec.type}"
  minRequired: ${curveSpec.sleepMin}
  maxRequired: ${curveSpec.sleepMax}
  parameter: ${curveSpec.parameter}
  midpoint: ${curveSpec.midpoint}
cancelCurve:
  type: "${curveSpec.type}"
  minRequired: ${curveSpec.cancelMin}
  maxRequired: ${curveSpec.cancelMax}
  parameter: ${curveSpec.parameter}
  midpoint: ${curveSpec.midpoint}
warmupDelayTicks: 20
sleepCooldownSeconds: 0
cancelCooldownSeconds: 0
wakeOnCancel: true
clearWeatherOnSkip: true
clearThunderOnSkip: true
resetPhantomStatistic: true
excludeAfkAndVanished: true
excludeCreativeAndSpectator: true
targetDawnTimeTicks: 0
nightStartTicks: 12542
nightEndTicks: 23459
allowSleepDuringThunder: true
budgetMaxFramesPerTick: 64
sharding:
  enabled: true
  transport: "redis pubsub"
  channel: "leafsleep:sync"
  bindHost: "redis"
  bindPort: 6379
  peers:
    - "127.0.0.1:25621"
  hmacSecretEnv: "LEAFSLEEP_HMAC_SECRET"
  heartbeatIntervalTicks: 20
storage:
  type: "sqlite"
  sqliteFile: "leafsleep.db"
  host: "127.0.0.1"
  port: 3306
  database: "leafsleep"
  username: "root"
  passwordEnv: "LEAFSLEEP_DB_PASSWORD"
  tablePrefix: "leafsleep_"
`;
    fs.writeFileSync(cfgPath, yaml, 'utf8');
  }
}

function clamp01(x) {
  return Math.max(0.0, Math.min(1.0, x));
}

function evaluateUnitCurve(type, t, param, mid) {
  const u = clamp01(t);
  if (u <= 0.0) return 0.0;
  if (u >= 1.0) return 1.0;
  switch (type) {
    case 'smootherstep':
      return u * u * u * (u * (u * 6.0 - 15.0) + 10.0);
    case 'linear':
      return u;
    case 'power':
      return Math.pow(u, param <= 0.0 ? 0.5 : param);
    case 'logarithmic': {
      const k = param <= 1.0 ? 10.0 : param;
      return Math.log1p((k - 1.0) * u) / Math.log(k);
    }
    case 'sigmoid': {
      const steep = param <= 0.0 ? 10.0 : param;
      const m = clamp01(mid);
      const sig = (x) => 1.0 / (1.0 + Math.exp(-steep * (x - m)));
      const s0 = sig(0.0);
      const s1 = sig(1.0);
      const su = sig(u);
      return clamp01((su - s0) / (s1 - s0));
    }
    default:
      return u;
  }
}

function computeExpectedRequired(type, eligible, maxPlayers, minReq, maxReq, param, mid) {
  const effectiveMax = Math.max(eligible, Math.max(2, maxPlayers));
  const t = clamp01((eligible - 1) / (effectiveMax - 1));
  const shaped = evaluateUnitCurve(type, t, param, mid);
  const interp = minReq + shaped * (maxReq - minReq);
  let req = Math.round(interp);
  if (minReq === 1 && eligible < effectiveMax) {
    req = Math.min(req, Math.max(1, Math.floor(eligible / 2)));
  }
  return Math.min(eligible, Math.max(minReq, req));
}

function parseStatusMetrics(worldLine, transportLine) {
  const sleepMatch = worldLine.match(/sleeping=(\d+)\/(\d+)/);
  const cancelMatch = worldLine.match(/cancelling=(\d+)\/(\d+)/);
  const eligibleMatch = worldLine.match(/eligible=(\d+)\/(\d+)/);
  const epochMatch = worldLine.match(/epoch=(\d+)/);
  const policyMatch = worldLine.match(/policy=([^/]+)\/([^)]+)\)/);
  const perfMatch = transportLine.match(/TPS=([\d.]+)\s+MSPT=([\d.]+)ms\s+\(leafsleepAvg=([\d.]+)ms\s+max=([\d.]+)ms\)/);

  return {
    policy: policyMatch ? policyMatch[1] : null,
    cancelPolicy: policyMatch ? policyMatch[2] : null,
    sleeping: sleepMatch ? parseInt(sleepMatch[1], 10) : -1,
    required: sleepMatch ? parseInt(sleepMatch[2], 10) : -1,
    cancelling: cancelMatch ? parseInt(cancelMatch[1], 10) : -1,
    cancelRequired: cancelMatch ? parseInt(cancelMatch[2], 10) : -1,
    eligible: eligibleMatch ? parseInt(eligibleMatch[1], 10) : -1,
    maxPlayers: eligibleMatch ? parseInt(eligibleMatch[2], 10) : -1,
    epoch: epochMatch ? parseInt(epochMatch[1], 10) : -1,
    tps: perfMatch ? parseFloat(perfMatch[1]) : -1,
    mspt: perfMatch ? parseFloat(perfMatch[2]) : -1,
    leafsleepAvgMs: perfMatch ? parseFloat(perfMatch[3]) : -1,
    leafsleepMaxMs: perfMatch ? parseFloat(perfMatch[4]) : -1
  };
}

async function queryShardStatus(bot) {
  bot.clearMessages();
  bot.bot.chat('/sleep status');
  const worldLine = await bot.waitForMessage(
    (m) => m.includes('World world (overworld-main'),
    10000,
    '/sleep status world line'
  );
  const transportLine = await bot.waitForMessage(
    (m) => m.includes('Transport=') && m.includes('TPS=') && m.includes('MSPT='),
    10000,
    '/sleep status transport/MSPT line'
  );
  return {
    worldLine,
    transportLine,
    parsed: parseStatusMetrics(worldLine, transportLine)
  };
}

async function runScaleSuite() {
  const numShards = serverList.length;
  const totalBots = botsPerShard * numShards;
  const shardLabels = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'];
  console.log(`[leafsleep-scale] Spawning ${totalBots} concurrent Mineflayer bots across ${numShards} servers (${serverList.join(', ')}; ${botsPerShard} bots/shard)...`);

  const shardBotGroups = serverList.map(() => []);
  for (let i = 1; i <= botsPerShard; i++) {
    for (let s = 0; s < numShards; s++) {
      const label = shardLabels[s] || String(s + 1);
      const bot = createManagedBot(`SleepBot${label}${i}`, i === 1);
      await bot.waitForSpawn();
      await switchServer(bot, serverList[s]);
      shardBotGroups[s].push({ ...bot, shardId: serverList[s] });
    }
  }

  const leadBots = shardBotGroups.map((group) => group[0]);
  const leadA = leadBots[0];
  const leadB = leadBots[1] || leadBots[0];
  const allBots = [];
  for (let i = 0; i < botsPerShard; i++) {
    for (let s = 0; s < numShards; s++) {
      allBots.push(shardBotGroups[s][i]);
    }
  }

  console.log(`[leafsleep-scale] All ${totalBots} bots connected and routed across ${numShards} servers. Waiting 3.0s for heartbeat sync...`);
  await sleep(3000);

  // Measure baseline idle status & MSPT across all shards
  const baselines = {};
  for (const lead of leadBots) {
    const st = await queryShardStatus(lead);
    baselines[lead.shardId] = st.parsed;
    console.log(`[leafsleep-scale] Baseline (${lead.shardId}): eligible=${st.parsed.eligible}/${st.parsed.maxPlayers}, TPS=${st.parsed.tps}, MSPT=${st.parsed.mspt}ms, leafsleepAvg=${st.parsed.leafsleepAvgMs}ms`);
    if (st.parsed.eligible !== totalBots) {
      throw new Error(`Expected eligible=${totalBots} on ${lead.shardId}, got ${st.parsed.eligible}`);
    }
  }

  const totalMaxPlayers = leadBots.length * 20;
  const curveMatrix = [
    {
      type: 'smootherstep',
      sleepMin: 1,
      sleepMax: 20,
      cancelMin: 1,
      cancelMax: 12,
      parameter: 10.0,
      midpoint: 0.35
    },
    {
      type: 'linear',
      sleepMin: 1,
      sleepMax: 14,
      cancelMin: 1,
      cancelMax: 7,
      parameter: 10.0,
      midpoint: 0.35
    },
    {
      type: 'power',
      sleepMin: 1,
      sleepMax: 14,
      cancelMin: 1,
      cancelMax: 7,
      parameter: 0.55,
      midpoint: 0.35
    },
    {
      type: 'logarithmic',
      sleepMin: 1,
      sleepMax: 14,
      cancelMin: 1,
      cancelMax: 7,
      parameter: 10.0,
      midpoint: 0.35
    },
    {
      type: 'sigmoid',
      sleepMin: 1,
      sleepMax: 14,
      cancelMin: 1,
      cancelMax: 7,
      parameter: 14.0,
      midpoint: 0.15
    }
  ].map((c) => ({
    ...c,
    expectedSleepReq: computeExpectedRequired(c.type, totalBots, totalMaxPlayers, c.sleepMin, c.sleepMax, c.parameter, c.midpoint),
    expectedCancelReq: computeExpectedRequired(c.type, totalBots, totalMaxPlayers, c.cancelMin, c.cancelMax, c.parameter, c.midpoint)
  }));

  const curveResults = [];

  for (const spec of curveMatrix) {
    console.log(`[leafsleep-scale] Testing curve '${spec.type}' across ${numShards} shards (expected sleepReq=${spec.expectedSleepReq}, cancelReq=${spec.expectedCancelReq} at ${totalBots}/${totalMaxPlayers} players)...`);
    writeCurveConfigForAllShards(spec);

    for (const lead of leadBots) {
      lead.clearMessages();
      lead.bot.chat('/sleep reload');
    }
    for (const lead of leadBots) {
      await lead.waitForMessage((m) => m.includes('reloaded successfully'), 8000, `${lead.shardId} reload (${spec.type})`);
    }

    // Set night on all shards
    for (const lead of leadBots) {
      lead.bot.chat('/time set 13000');
    }
    await sleep(1200);

    const preStatus = await queryShardStatus(leadA);
    const prevEpoch = preStatus.parsed.epoch;
    if (preStatus.parsed.policy !== `curve:${spec.type}`) {
      throw new Error(`Expected policy=curve:${spec.type}, got ${preStatus.parsed.policy}`);
    }
    if (preStatus.parsed.required !== spec.expectedSleepReq) {
      throw new Error(`Curve ${spec.type}: expected required=${spec.expectedSleepReq} at ${totalBots}/${totalMaxPlayers} players, got ${preStatus.parsed.required} (${preStatus.worldLine})`);
    }
    if (preStatus.parsed.cancelRequired !== spec.expectedCancelReq) {
      throw new Error(`Curve ${spec.type}: expected cancelRequired=${spec.expectedCancelReq} at ${totalBots}/${totalMaxPlayers} players, got ${preStatus.parsed.cancelRequired} (${preStatus.worldLine})`);
    }

    // Have (expectedSleepReq - 1) bots distributed across all shards vote /sleep sleep
    const subQuorumCount = spec.expectedSleepReq - 1;
    for (let i = 0; i < subQuorumCount; i++) {
      allBots[i].bot.chat('/sleep sleep');
      await sleep(120);
    }
    await sleep(600);

    // Verify quorum is NOT yet met (Need 1 more to skip)
    const midStatus = await queryShardStatus(leadB);
    if (midStatus.parsed.sleeping !== subQuorumCount || !midStatus.worldLine.includes('Need 1 more to skip')) {
      throw new Error(`Curve ${spec.type}: expected ${subQuorumCount}/${spec.expectedSleepReq} sleeping ('Need 1 more to skip'), got: ${midStatus.worldLine}`);
    }

    // Have 1 non-sleeping bot on the last shard vote /sleep cancel -> TUG_OF_WAR offsets 1 sleeper so required becomes expectedSleepReq + 1 ('Need 2 more to skip')
    const cancellerBot = allBots[allBots.length - 1];
    cancellerBot.bot.chat('/sleep cancel');
    await sleep(600);

    const tugStatus = await queryShardStatus(leadA);
    if (tugStatus.parsed.required !== spec.expectedSleepReq + 1 || !tugStatus.worldLine.includes('Need 2 more to skip')) {
      throw new Error(`Curve ${spec.type} TUG_OF_WAR: expected required=${spec.expectedSleepReq + 1} ('Need 2 more to skip') after 1 cancel vote, got: ${tugStatus.worldLine}`);
    }

    // Now have 2 more bots vote /sleep sleep to reach (expectedSleepReq + 1) and trigger dawn skip across all shards!
    for (const lead of leadBots) {
      lead.clearMessages();
    }
    allBots[subQuorumCount].bot.chat('/sleep sleep');
    await sleep(120);
    allBots[subQuorumCount + 1].bot.chat('/sleep sleep');

    for (const lead of leadBots) {
      await lead.waitForMessage(
        (m) => m.includes('epoch ') || m.includes('Good morning') || m.includes('Rise and shine') || m.includes('warm dawn') || m.includes('morning arrives') || m.includes('First light') || m.includes('Cock-a-doodle-doo'),
        12000,
        `${lead.shardId} dawn wakeup (${spec.type})`
      );
    }

    await sleep(500);
    const shardTelemetry = {};
    for (const lead of leadBots) {
      const post = await queryShardStatus(lead);
      if (post.parsed.epoch <= prevEpoch) {
        throw new Error(`Curve ${spec.type}: expected epoch > ${prevEpoch} on ${lead.shardId}, got ${post.parsed.epoch}`);
      }
      if (post.parsed.mspt > 35.0) {
        throw new Error(`Curve ${spec.type}: MSPT budget exceeded on ${lead.shardId} (${post.parsed.mspt}ms)`);
      }
      if (post.parsed.leafsleepAvgMs > 2.0) {
        throw new Error(`Curve ${spec.type}: LeafSleep coordinator avg tick exceeded 2.0ms on ${lead.shardId} (${post.parsed.leafsleepAvgMs}ms)`);
      }
      shardTelemetry[lead.shardId] = {
        tps: post.parsed.tps,
        mspt: post.parsed.mspt,
        leafsleepAvgMs: post.parsed.leafsleepAvgMs,
        leafsleepMaxMs: post.parsed.leafsleepMaxMs,
        epoch: post.parsed.epoch
      };
    }

    const row = {
      curve: spec.type,
      shards: numShards,
      eligible: totalBots,
      maxPlayers: totalMaxPlayers,
      baseSleepRequired: spec.expectedSleepReq,
      tugOfWarSleepRequired: spec.expectedSleepReq + 1,
      cancelRequired: spec.expectedCancelReq,
      committedEpoch: shardTelemetry[leadA.shardId].epoch,
      shardTelemetry
    };
    console.log(`[leafsleep-scale] Curve '${spec.type}' PASS:`, JSON.stringify(row));
    curveResults.push(row);
  }

  // High-concurrency burst stress test across all shards
  console.log(`[leafsleep-scale] Running ${totalBots}-bot concurrent vote/cancel/status burst stress test across ${numShards} servers...`);
  for (const lead of leadBots) {
    lead.bot.chat('/time set 13000');
  }
  await sleep(1000);

  for (let i = 0; i < allBots.length; i++) {
    if (i % 3 === 0) {
      allBots[i].bot.chat('/sleep status');
    } else if (i % 4 === 0) {
      allBots[i].bot.chat('/sleep cancel');
    } else {
      allBots[i].bot.chat('/sleep sleep');
    }
  }
  await sleep(2500);

  const burstStress = {};
  for (const lead of leadBots) {
    const stress = await queryShardStatus(lead);
    if (stress.parsed.tps < 18.0) {
      throw new Error(`Burst stress TPS dropped below 18.0 on ${lead.shardId} (${stress.parsed.tps})`);
    }
    if (stress.parsed.mspt > 35.0) {
      throw new Error(`Burst stress MSPT exceeded 35.0ms on ${lead.shardId} (${stress.parsed.mspt}ms)`);
    }
    burstStress[lead.shardId] = stress.parsed;
  }

  const report = {
    status: 'PASS',
    shards: serverList,
    totalBots,
    botsPerShard,
    baselines,
    curveResults,
    burstStress
  };
  console.log(JSON.stringify(report, null, 2));

  clearTimeout(globalTimer);
  for (const b of allBots) {
    try {
      b.bot.quit();
    } catch (_) {}
  }
  await sleep(500);
  process.exit(0);
}

runScaleSuite().catch((err) => {
  console.error(JSON.stringify({
    status: 'FAIL',
    reason: err.message || String(err)
  }));
  process.exit(1);
});
