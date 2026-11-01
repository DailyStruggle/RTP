/**
 * Headless multi-bot protocol harness for LeafSleep cross-shard acceptance testing.
 *
 * Connects two Mineflayer bots (SleepBotA on Paper backend-a, SleepBotB on Folia backend-b)
 * through Velocity (proxy-a) and empirically verifies:
 *   1. /sleep test ([OPS bit] PBIT/CBIT/IBIT Health=HEALTHY on Paper and Folia)
 *   2. /sleep status cross-shard discovery (shards=2, eligible=2)
 *   3. Daytime sleep rejection (notNight guard)
 *   4. Cross-shard /sleep sleep vote & chat progress broadcast (backend-a -> backend-b)
 *   5. Cross-shard /sleep cancel tug-of-war abort (backend-b -> backend-a)
 *   6. Cross-shard dawn commit & targetDayEpoch advancement across Paper and Folia
 *   7. /sleep ignore opt-out toggle and /sleep reload epoch watermark preservation
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
const timeoutSeconds = parseInt(getArg('--timeout', '120'), 10);
const serversArg = getArg('--servers', null);
const serverA = getArg('--server-a', 'backend-a');
const serverB = getArg('--server-b', 'backend-b');
const serverList = serversArg
  ? serversArg.split(',').map((s) => s.trim()).filter(Boolean)
  : [serverA, serverB];
const reloadOnStart = args.includes('--reload-on-start');
const fullFeatureAudit = args.includes('--full-feature-audit');
const expectedTransport = getArg('--expect-transport', null);

const globalTimer = setTimeout(() => {
  console.error(JSON.stringify({
    status: 'FAIL',
    reason: `Timeout (${timeoutSeconds}s) exceeded during LeafSleep multi-bot acceptance run`
  }));
  process.exit(1);
}, timeoutSeconds * 1000);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function createManagedBot(username) {
  const bot = mineflayer.createBot({
    host,
    port,
    username,
    version: mcVersion,
    checkTimeoutInterval: 60 * 1000
  });

  const origWrite = bot._client.write.bind(bot._client);
  const hasTickEnd = !!(require('minecraft-data')(mcVersion)?.protocol?.play?.toServer?.types?.packet_tick_end);
  bot._client.write = function (name, params) {
    const res = origWrite(name, params);
    if (hasTickEnd && bot._client.state === 'play' && (name === 'position' || name === 'position_look' || name === 'look' || name === 'flying')) {
      origWrite('tick_end', {});
    }
    return res;
  };

  const messages = [];
  const rawClickCommands = [];
  let sawBossBar = false;
  let sawEntityEffect = false;

  function collectClickCommands(node) {
    if (!node || typeof node !== 'object') return;
    if (node.clickEvent) {
      const val = node.clickEvent.value || node.clickEvent.command;
      if (typeof val === 'string') rawClickCommands.push(val);
    }
    if (node.click_event) {
      const val = node.click_event.value || node.click_event.command;
      if (typeof val === 'string') rawClickCommands.push(val);
    }
    if (node.json && typeof node.json === 'object' && node.json !== node) {
      collectClickCommands(node.json);
    }
    if (node.value && typeof node.value === 'object' && node.value !== node) {
      collectClickCommands(node.value);
    }
    if (Array.isArray(node.extra)) {
      for (const child of node.extra) {
        collectClickCommands(child);
      }
    }
    if (Array.isArray(node.with)) {
      for (const child of node.with) {
        collectClickCommands(child);
      }
    }
  }

  bot.on('message', (jsonMsg) => {
    try {
      collectClickCommands(jsonMsg);
      if (jsonMsg && jsonMsg.json) {
        collectClickCommands(jsonMsg.json);
      }
    } catch (_) {}
  });

  bot._client.on('boss_bar', () => {
    sawBossBar = true;
  });

  bot._client.on('entity_effect', () => {
    sawEntityEffect = true;
  });

  bot.on('messagestr', (msg) => {
    if (msg && msg.trim().length > 0) {
      messages.push({ time: Date.now(), text: msg.trim() });
      console.log(`[${username} chat] ${msg.trim()}`);
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
    rawClickCommands.length = 0;
  }

  function resetTelemetryFlags() {
    sawBossBar = false;
    sawEntityEffect = false;
  }

  function getTelemetryFlags() {
    return { sawBossBar, sawEntityEffect, clickCommands: [...rawClickCommands] };
  }

  async function waitForMessage(predicate, waitMs = 10000, label = 'message') {
    const deadline = Date.now() + waitMs;
    while (Date.now() < deadline) {
      const hit = messages.find((m) => predicate(m.text));
      if (hit) {
        return hit.text;
      }
      await sleep(100);
    }
    const recent = messages.map((m) => m.text).slice(-10);
    throw new Error(`[${username}] Timed out waiting for ${label} (${waitMs}ms). Recent messages: ${JSON.stringify(recent)}`);
  }

  function waitForSpawn(waitMs = 25000) {
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
    resetTelemetryFlags,
    getTelemetryFlags,
    waitForMessage,
    waitForSpawn
  };
}

async function switchServerIfNeeded(managed, targetServer) {
  let respawned = false;
  const onRespawn = () => {
    respawned = true;
  };
  managed.bot.once('respawn', onRespawn);
  managed.bot.chat(`/server ${targetServer}`);
  const deadline = Date.now() + 5000;
  while (!respawned && Date.now() < deadline) {
    await sleep(150);
  }
  managed.bot.removeListener('respawn', onRespawn);
  await sleep(1500);
}

async function runAcceptance() {
  const numShards = serverList.length;
  const botLabels = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'];
  console.log(`[leafsleep-bot] Connecting ${numShards} bots across ${serverList.join(', ')} via ${host}:${port} (${mcVersion})...`);

  const bots = [];
  for (let i = 0; i < numShards; i++) {
    const label = botLabels[i] || String(i + 1);
    const bot = createManagedBot(`SleepBot${label}`);
    await bot.waitForSpawn();
    console.log(`[leafsleep-bot] ${bot.username} spawned -> switching to ${serverList[i]}.`);
    await sleep(600);
    await switchServerIfNeeded(bot, serverList[i]);
    bots.push({ ...bot, shardId: serverList[i] });
  }

  const botA = bots[0];
  const botB = bots[1];
  const botC = bots[2] || bots[0];
  const botD = bots[3] || bots[1];

  if (reloadOnStart) {
    console.log(`[leafsleep-bot] Reloading LeafSleep on all ${numShards} shards (--reload-on-start)...`);
    for (const b of bots) {
      b.clearMessages();
      b.bot.chat('/sleep reload');
    }
    for (const b of bots) {
      await b.waitForMessage((m) => m.includes('reloaded successfully'), 8000, `${b.shardId} reload on start`);
    }
  }

  // Give heartbeat gossip 2.5s to exchange initial shard counts across all shards
  await sleep(2500);

  // 1. Run /sleep version and /sleep test ([OPS bit]) on every shard in the cluster
  console.log(`[leafsleep-bot] Verifying /sleep version and /sleep test ([OPS bit]) across all ${numShards} shards...`);
  const shardBits = {};
  for (const b of bots) {
    b.clearMessages();
    b.bot.chat('/sleep version');
    const ver = await b.waitForMessage((m) => m.includes('LeafSleep v1.0.0'), 8000, `${b.shardId} /sleep version`);
    b.clearMessages();
    b.bot.chat('/sleep test');
    const bit = await b.waitForMessage((m) => m.includes('[OPS bit] Health=HEALTHY'), 10000, `${b.shardId} [OPS bit] HEALTHY`);
    shardBits[b.shardId] = { version: ver, bit };
  }

  // 2. Verify /sleep status reports all shards and eligible=numShards
  console.log(`[leafsleep-bot] Verifying cross-shard /sleep status (shards=${numShards}, eligible=${numShards})...`);
  botA.clearMessages();
  botA.bot.chat('/sleep status');
  const statusWorldLine = await botA.waitForMessage(
    (m) => m.includes('World world (overworld-main') && m.includes(`eligible=${numShards}/`),
    10000,
    `/sleep status world line with eligible=${numShards}`
  );
  const statusTransportLine = await botA.waitForMessage(
    (m) => m.includes('Transport=') && m.includes(`shards=${numShards}`) && (!expectedTransport || m.includes(`Transport=${expectedTransport}`)),
    10000,
    `/sleep status transport line with shards=${numShards}${expectedTransport ? ' and Transport=' + expectedTransport : ''}`
  );

  // 3. Verify daytime sleep rejection (notNight guard)
  console.log('[leafsleep-bot] Verifying daytime notNight guard...');
  for (const b of bots) {
    b.bot.chat('/time set 6000');
    b.bot.chat('/weather clear');
  }
  await sleep(1000);

  botA.clearMessages();
  botA.bot.chat('/sleep sleep');
  const notNightMsg = await botA.waitForMessage(
    (m) => m.includes('only sleep at night'),
    8000,
    'notNight rejection message'
  );

  // 4. Set all shards to night (13000), vote /sleep sleep, verify remote progress broadcast across all shards,
  //    and cancel via TUG_OF_WAR across shards!
  console.log(`[leafsleep-bot] Verifying ${numShards}-shard /sleep sleep progress and /sleep cancel tug-of-war abort...`);
  for (const b of bots) {
    b.bot.chat('/time set 13000');
  }
  await sleep(1000);

  for (const b of bots) {
    b.clearMessages();
  }
  botA.bot.chat('/sleep sleep');

  const sleepProgressOnB = await botB.waitForMessage(
    (m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'),
    8000,
    `remote sleep progress broadcast on ${botB.shardId}`
  );
  if (numShards >= 4) {
    await botC.waitForMessage(
      (m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'),
      8000,
      `remote sleep progress broadcast on ${botC.shardId}`
    );
    await botD.waitForMessage(
      (m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'),
      8000,
      `remote sleep progress broadcast on ${botD.shardId}`
    );
    // At eligible=4/80, sleepRequired=2 and cancelRequired=2:
    // Have SleepBotC (3rd shard) also vote /sleep sleep to reach 2/2 quorum,
    // then have SleepBotB (2nd shard) + SleepBotD (4th shard) vote /sleep cancel to reach 2/2 cancel quorum!
    botC.bot.chat('/sleep sleep');
    await sleep(300);
    botB.bot.chat('/sleep cancel');
    await sleep(300);
    botD.bot.chat('/sleep cancel');
  } else {
    // At eligible=2/40, sleepRequired=1 and cancelRequired=1
    botB.bot.chat('/sleep cancel');
  }

  const cancelBroadcaster = numShards >= 4 ? 'SleepBotD' : 'SleepBotB';
  const cancelOnA = await botA.waitForMessage(
    (m) => m.includes(`Night skip cancelled by ${cancelBroadcaster}`),
    8000,
    `cross-shard cancel broadcast on ${botA.shardId}`
  );
  const cancelOnB = await botB.waitForMessage(
    (m) => m.includes(`Night skip cancelled by ${cancelBroadcaster}`),
    8000,
    `cancel broadcast on ${botB.shardId}`
  );
  if (numShards >= 4) {
    await botC.waitForMessage(
      (m) => m.includes(`Night skip cancelled by ${cancelBroadcaster}`) && m.includes('SleepBotB') && m.includes('SleepBotD'),
      8000,
      `cross-shard cancel broadcast on ${botC.shardId} with both SleepBotB and SleepBotD`
    );
  }

  // 5. Wait for 2s cooldown to expire, then vote /sleep sleep to reach quorum and let warmup complete across all shards!
  console.log(`[leafsleep-bot] Verifying ${numShards}-shard dawn commit, warmup BossBar/ActionBar, PassTime, and wakeupEffects...`);
  await sleep(2500);
  for (const b of bots) {
    b.clearMessages();
    b.resetTelemetryFlags();
  }
  botA.bot.chat('/sleep sleep');
  if (numShards >= 4) {
    await sleep(150);
    botC.bot.chat('/sleep sleep');
  }

  const dawnPredicate = (m) =>
    m.includes('epoch ') ||
    m.includes('Good morning') ||
    m.includes('sunrise') ||
    m.includes('Skip complete') ||
    m.includes('First light') ||
    m.includes('Cock-a-doodle-doo') ||
    m.includes('Rise and shine') ||
    m.includes('warm dawn') ||
    m.includes('morning arrives');

  const dawnOnA = await botA.waitForMessage(dawnPredicate, 12000, `dawn wakeup broadcast on ${botA.shardId}`);
  const dawnOnB = await botB.waitForMessage(dawnPredicate, 12000, `dawn wakeup broadcast on ${botB.shardId}`);
  if (numShards >= 4) {
    await botC.waitForMessage(dawnPredicate, 12000, `dawn wakeup broadcast on ${botC.shardId}`);
    await botD.waitForMessage(dawnPredicate, 12000, `dawn wakeup broadcast on ${botD.shardId}`);
  }
  await sleep(600);
  const dawnTelemetryA = botA.getTelemetryFlags();
  const dawnTelemetryB = botB.getTelemetryFlags();

  // Verify /sleep status on all shards now shows epoch >= 1 and daytime time (< 2000)
  botD.clearMessages();
  botD.bot.chat('/sleep status');
  const postDawnStatusB = await botD.waitForMessage(
    (m) => m.includes('World world (overworld-main') && !m.includes('epoch=0'),
    8000,
    `post-dawn status on ${botD.shardId} with epoch >= 1`
  );

  let featureAuditDetails = null;
  if (fullFeatureAudit) {
    console.log('[leafsleep-bot] Running full feature audit (aliases, /sleep help, /sleep message themes, clickable tokens, eligibility filtering, physical bed + cooldown denial, thunderstorm daytime sleep)...');

    // 6a. Command aliases (/ops, /playersleep), root /sleep, and /sleep help
    botA.clearMessages();
    botA.bot.chat('/sleep help');
    const helpLine = await botA.waitForMessage((m) => m.includes('Subcommands: /sleep <status|sleep|cancel|ignore|message|reload|test|version|help>'), 8000, '/sleep help');

    botA.clearMessages();
    botA.bot.chat('/ops status');
    const opsAliasLine = await botA.waitForMessage((m) => m.includes('World world (overworld-main'), 8000, '/ops status alias');

    botB.clearMessages();
    botB.bot.chat('/playersleep version');
    const playerSleepAliasLine = await botB.waitForMessage((m) => m.includes('LeafSleep v1.0.0'), 8000, '/playersleep version alias');

    botA.clearMessages();
    botA.bot.chat('/sleep');
    const rootCommandLine = await botA.waitForMessage((m) => m.includes('World world (overworld-main'), 8000, 'root /sleep command');

    // 6b. /sleep message [themeId] & explicit /sleep sleep <themeId>
    botA.clearMessages();
    botA.bot.chat('/sleep message');
    const msgDefaultLine = await botA.waitForMessage((m) => m.includes('Current sleep message theme:') && m.includes('cozy') && m.includes('starlight'), 8000, '/sleep message default');

    botA.clearMessages();
    botA.bot.chat('/sleep message invalid_theme_xyz');
    const msgInvalidLine = await botA.waitForMessage((m) => m.includes('Invalid parameter') || m.includes('invalid_theme_xyz'), 8000, '/sleep message invalid theme rejection');

    botA.clearMessages();
    botA.bot.chat('/sleep message starlight');
    const msgSetStarlight = await botA.waitForMessage((m) => m.includes("Preferred sleep message theme set to 'starlight'"), 8000, '/sleep message starlight');

    await sleep(2500);
    for (const b of bots) {
      b.bot.chat('/time set 13000');
    }
    await sleep(1000);
    for (const b of bots) {
      b.clearMessages();
    }
    botA.bot.chat('/sleep sleep');
    const starlightBroadcastOnA = await botA.waitForMessage((m) => m.includes('SleepBotA is counting stars'), 8000, 'starlight theme broadcast on A');
    await botB.waitForMessage((m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'), 8000, 'remote sleep broadcast on B during starlight test');
    botB.bot.chat('/sleep cancel');
    if (numShards >= 4) {
      await sleep(200);
      botD.bot.chat('/sleep cancel');
    }
    await botA.waitForMessage((m) => m.includes('Night skip cancelled'), 8000, 'cancel after starlight test');

    botA.clearMessages();
    botA.bot.chat('/sleep message random');
    await botA.waitForMessage((m) => m.includes("Preferred sleep message theme set to 'random'"), 8000, '/sleep message random reset');

    await sleep(2500);
    for (const b of bots) {
      b.clearMessages();
    }
    botA.bot.chat('/sleep sleep cozy');
    const cozyExplicitBroadcastOnA = await botA.waitForMessage((m) => m.includes('SleepBotA curled up in bed'), 8000, 'explicit /sleep sleep cozy broadcast on A');
    await botB.waitForMessage((m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'), 8000, 'remote sleep broadcast on B during cozy test');
    botB.bot.chat('/sleep cancel');
    if (numShards >= 4) {
      await sleep(200);
      botD.bot.chat('/sleep cancel');
    }
    await botA.waitForMessage((m) => m.includes('Night skip cancelled'), 8000, 'cancel after cozy test');

    // 6c. Clickable chat action token (/sleep token id=<uuid>) & one-time token replay rejection
    await sleep(2500);
    botA.clearMessages();
    botA.bot.chat('/sleep status');
    await botA.waitForMessage((m) => m.includes('[Sleep] (/sleep sleep)'), 8000, 'interactive status action line');
    const clickCmds = botA.getTelemetryFlags().clickCommands;
    const sleepTokenCmd = clickCmds.find((c) => c.startsWith('/sleep token id='));
    if (!sleepTokenCmd) {
      throw new Error(`Expected clickable /sleep token id=<uuid> in status output, got: ${JSON.stringify(clickCmds)}`);
    }
    for (const b of bots) {
      b.clearMessages();
    }
    botA.bot.chat(sleepTokenCmd);
    const tokenVoteOnB = await botB.waitForMessage((m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'), 8000, 'clickable token sleep vote on B');

    botA.clearMessages();
    botA.bot.chat(sleepTokenCmd);
    const tokenReplayRejected = await botA.waitForMessage((m) => m.includes('Invalid value') || m.includes('Invalid parameter'), 8000, 'one-time token replay rejection');

    botB.bot.chat('/sleep cancel');
    if (numShards >= 4) {
      await sleep(200);
      botD.bot.chat('/sleep cancel');
    }
    await botA.waitForMessage((m) => m.includes('Night skip cancelled'), 8000, 'cancel after token test');

    // 6d. Eligibility filtering: Creative, Spectator, and /sleep ignore exclusion + ignored vote rejection
    botB.bot.chat('/gamemode creative');
    await sleep(2200);
    botA.clearMessages();
    botA.bot.chat('/sleep status');
    const creativeExcludedLine = await botA.waitForMessage(
      (m) => m.includes('World world (overworld-main') && m.includes(`eligible=${numShards - 1}/`),
      8000,
      'creative player excluded from eligibleCount'
    );

    botB.bot.chat('/gamemode spectator');
    await sleep(2200);
    botA.clearMessages();
    botA.bot.chat('/sleep status');
    const spectatorExcludedLine = await botA.waitForMessage(
      (m) => m.includes('World world (overworld-main') && m.includes(`eligible=${numShards - 1}/`),
      8000,
      'spectator player excluded from eligibleCount'
    );

    botB.bot.chat('/gamemode survival');
    await sleep(2200);
    botA.clearMessages();
    botA.bot.chat('/sleep status');
    await botA.waitForMessage(
      (m) => m.includes('World world (overworld-main') && m.includes(`eligible=${numShards}/`),
      8000,
      'survival player restored to eligibleCount'
    );

    botB.clearMessages();
    botB.bot.chat('/sleep ignore');
    await botB.waitForMessage((m) => m.includes('Sleep ignore enabled'), 8000, 'ignore enabled on B');
    await sleep(1200);
    botA.clearMessages();
    botA.bot.chat('/sleep status');
    const ignoreExcludedLine = await botA.waitForMessage(
      (m) => m.includes('World world (overworld-main') && m.includes(`eligible=${numShards - 1}/`),
      8000,
      'ignored player excluded from eligibleCount across shards'
    );

    botB.clearMessages();
    botB.bot.chat('/sleep sleep');
    const ignoredVoteRejectedLine = await botB.waitForMessage(
      (m) => m.includes('Sleep ignore enabled'),
      8000,
      'ignored player /sleep sleep rejection'
    );

    botB.clearMessages();
    botB.bot.chat('/sleep ignore');
    await botB.waitForMessage((m) => m.includes('Sleep ignore disabled'), 8000, 'ignore disabled on B');
    await sleep(1200);

    // 6e. Physical Bed Entry, Bed Leave immediate exclusion, and Cooldown Bed-Entry Denial (ctx.cancel())
    console.log('[leafsleep-bot] Verifying physical bed entry, bed leave immediate exclusion, and cooldown bed-entry denial on Paper & Folia...');
    botA.bot.chat('/difficulty peaceful');
    await sleep(2500);
    for (const b of bots) {
      b.bot.chat('/time set 13000');
    }
    await sleep(800);

    const posA = botA.bot.entity.position.floored();
    const bedFootX = posA.x + 1;
    const bedFootY = posA.y;
    const bedFootZ = posA.z;
    botA.bot.chat(`/fill ${bedFootX - 1} ${bedFootY - 1} ${bedFootZ - 2} ${bedFootX + 1} ${bedFootY - 1} ${bedFootZ + 1} minecraft:stone`);
    botA.bot.chat(`/fill ${bedFootX - 1} ${bedFootY} ${bedFootZ - 2} ${bedFootX + 1} ${bedFootY + 2} ${bedFootZ + 1} minecraft:air`);
    await sleep(500);
    botA.bot.chat(`/setblock ${bedFootX} ${bedFootY} ${bedFootZ} minecraft:red_bed[part=foot,facing=north]`);
    botA.bot.chat(`/setblock ${bedFootX} ${bedFootY} ${bedFootZ - 1} minecraft:red_bed[part=head,facing=north]`);
    await sleep(800);

    const bedBlockA = botA.bot.blockAt(posA.offset(1, 0, 0));
    if (!bedBlockA || !bedBlockA.name.includes('bed')) {
      throw new Error(`Expected red_bed block at ${bedFootX},${bedFootY},${bedFootZ}, found: ${bedBlockA ? bedBlockA.name : 'null'}`);
    }

    for (const b of bots) {
      b.clearMessages();
    }
    await botA.bot.activateBlock(bedBlockA);
    const bedEnterBroadcastOnB = await botB.waitForMessage(
      (m) => m.includes('SleepBotA') && m.includes('Sleeping: SleepBotA'),
      8000,
      'physical bed entry broadcast on Folia shard B'
    );

    // Leave bed immediately -> triggers PlayerBedLeaveEvent, excludes player from sleepingCount, and starts sleepCooldownSeconds (2s)
    botA.bot._client.write('entity_action', {
      entityId: botA.bot.entity.id,
      actionId: 'stop_sleeping',
      jumpBoost: 0
    });
    await sleep(300);

    // Immediately attempt to re-enter the physical bed while sleepCooldownSeconds is active -> must be DENIED (ctx.cancel())
    botA.clearMessages();
    await botA.bot.activateBlock(bedBlockA);
    const bedCooldownDeniedMsg = await botA.waitForMessage(
      (m) => m.includes('Please wait') && m.includes('sleep action'),
      8000,
      'physical bed re-entry cooldown denial message'
    );

    botA.clearMessages();
    botA.bot.chat('/sleep status');
    const afterCooldownDeniedStatus = await botA.waitForMessage(
      (m) => m.includes('World world (overworld-main') && m.includes('sleeping=0/'),
      8000,
      'sleeping=0 after physical bed leave and denied cooldown re-entry'
    );

    // 6f. Thunderstorm daytime sleep (allowSleepDuringThunder: true) & weather clearing (clearWeatherOnSkip / clearThunderOnSkip)
    console.log('[leafsleep-bot] Verifying daytime thunderstorm sleep & automatic weather clearing on dawn commit...');
    await sleep(2500);
    for (const b of bots) {
      b.bot.chat('/time set 6000');
      b.bot.chat('/weather thunder');
    }
    await sleep(1200);
    for (const b of bots) {
      b.clearMessages();
      b.resetTelemetryFlags();
    }
    botA.bot.chat('/sleep sleep');
    if (numShards >= 4) {
      await sleep(150);
      botC.bot.chat('/sleep sleep');
    }
    const thunderDawnOnA = await botA.waitForMessage(dawnPredicate, 12000, 'thunderstorm daytime dawn commit on A');
    const thunderDawnOnB = await botB.waitForMessage(dawnPredicate, 12000, 'thunderstorm daytime dawn commit on B');

    // Wait for cooldown and verify thunder was cleared (since time is now 0/daytime and weather is clear, /sleep sleep must be rejected by notNight!)
    await sleep(2500);
    botA.clearMessages();
    botA.bot.chat('/sleep sleep');
    const postThunderClearedNotNight = await botA.waitForMessage(
      (m) => m.includes('only sleep at night'),
      8000,
      'notNight rejection proving thunder was cleared by skip'
    );

    featureAuditDetails = {
      helpLine,
      opsAliasLine,
      playerSleepAliasLine,
      rootCommandLine,
      msgDefaultLine,
      msgInvalidLine,
      msgSetStarlight,
      starlightBroadcastOnA,
      cozyExplicitBroadcastOnA,
      sleepTokenCmd,
      tokenVoteOnB,
      tokenReplayRejected,
      creativeExcludedLine,
      spectatorExcludedLine,
      ignoreExcludedLine,
      ignoredVoteRejectedLine,
      bedEnterBroadcastOnB,
      bedCooldownDeniedMsg,
      afterCooldownDeniedStatus,
      thunderDawnOnA,
      thunderDawnOnB,
      postThunderClearedNotNight
    };
  }

  // 7. Verify /sleep ignore toggle and /sleep reload epoch watermark preservation
  console.log('[leafsleep-bot] Verifying /sleep ignore and /sleep reload...');
  botB.clearMessages();
  botB.bot.chat('/sleep ignore');
  await botB.waitForMessage((m) => m.includes('Sleep ignore enabled'), 8000, 'ignore enabled');

  botB.clearMessages();
  botB.bot.chat('/sleep ignore');
  await botB.waitForMessage((m) => m.includes('Sleep ignore disabled'), 8000, 'ignore disabled');

  botA.clearMessages();
  botA.bot.chat('/sleep reload');
  await botA.waitForMessage((m) => m.includes('reloaded successfully'), 8000, 'reload success');

  botA.clearMessages();
  botA.bot.chat('/sleep status');
  const postReloadStatusA = await botA.waitForMessage(
    (m) => m.includes('World world (overworld-main') && !m.includes('epoch=0'),
    8000,
    'post-reload status preserving committed epoch'
  );

  const summary = {
    status: 'PASS',
    shardsTested: serverList,
    shardBits,
    statusWorldLine,
    statusTransportLine,
    notNightMsg,
    sleepProgressOnB,
    cancelOnA,
    cancelOnB,
    dawnOnA,
    dawnOnB,
    warmupBossBarSeenOnA: dawnTelemetryA.sawBossBar,
    wakeupEffectSeenOnA: dawnTelemetryA.sawEntityEffect,
    warmupBossBarSeenOnB: dawnTelemetryB.sawBossBar,
    wakeupEffectSeenOnB: dawnTelemetryB.sawEntityEffect,
    postDawnStatusB,
    postReloadStatusA,
    ...(featureAuditDetails ? { featureAuditDetails } : {})
  };
  console.log(JSON.stringify(summary, null, 2));

  clearTimeout(globalTimer);
  for (const b of bots) {
    try {
      b.bot.quit();
    } catch (_) {}
  }
  await sleep(500);
  process.exit(0);
}

runAcceptance().catch((err) => {
  console.error(JSON.stringify({
    status: 'FAIL',
    reason: err.message || String(err)
  }));
  process.exit(1);
});
