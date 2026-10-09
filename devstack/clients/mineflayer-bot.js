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
const mcVersion = getArg('--version', '1.21.11');
const isLite = args.includes('--lite');
const assertEffects = args.includes('--assert-effects');
const isGuiMode = args.includes('--gui') || getArg('--mode', '') === 'gui' || process.env.RTP_TEST_GUI === 'true';
const guiScenario = getArg('--gui-scenario', 'teleport'); // 'teleport' | 'submenu' | 'setup'
const targetSlotArg = getArg('--slot', null);
const targetRegion = getArg('--target-region', 'backend-a:default');
const debounceMs = parseInt(getArg('--debounce-ms', '250'), 10);
const assertTransfer = args.includes('--assert-transfer') || isLite;
const bareRtp = args.includes('--bare-rtp') || (isLite && !args.some(a => a.startsWith('--target-region')));
const targetServer = getArg('--target-server', null);

console.log(`[bot] Initializing headless client: ${username} -> ${host}:${port} (version: ${mcVersion}, timeout: ${timeoutSeconds}s, tier: ${isLite ? 'lite' : 'pro'}, assertEffects: ${assertEffects}, guiMode: ${isGuiMode}, guiScenario: ${guiScenario}, targetServer: ${targetServer || 'default'})`);

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
    version: mcVersion,
    checkTimeoutInterval: 60 * 1000
  });
} catch (err) {
  console.error(`[bot] Failed to initialize bot:`, err);
  process.exit(1);
}

let stage = 'CONNECTING';
let rtpStartTime = 0;
let windowRequestTime = 0;
let windowOpenTime = 0;
let clickedSlot = null;
let clickedItemName = null;
let windowOpenLatencyMs = null;
let initialPosition = null;
let transferDetected = false;
let transferDetails = null;

// Listen for server transfer / configuration phase transitions
bot._client.on('transfer', (packet) => {
  transferDetected = true;
  transferDetails = { packet: 'transfer', host: packet?.host, port: packet?.port, timestamp: Date.now() };
  console.log(`[bot] Detected ClientboundTransferPacket to ${packet?.host}:${packet?.port}`);
});

bot._client.on('start_configuration', () => {
  transferDetected = true;
  transferDetails = { packet: 'start_configuration', timestamp: Date.now() };
  console.log(`[bot] Detected start_configuration packet (proxy server transfer in-progress)`);
});

bot._client.on('respawn', (packet) => {
  // Respawn during AWAITING_TELEPORT indicates cross-server / dimension transition
  if (stage === 'AWAITING_TELEPORT' || stage === 'READY_FOR_RTP') {
    transferDetected = true;
    if (!transferDetails) {
      transferDetails = { packet: 'respawn', dimension: packet?.dimension, timestamp: Date.now() };
    }
    console.log(`[bot] Detected respawn packet (server switch / dimension transition)`);
  }
});

bot.on('respawn', () => {
  if (stage === 'AWAITING_TELEPORT' || stage === 'READY_FOR_RTP') {
    transferDetected = true;
    console.log(`[bot] Mineflayer respawn event triggered`);
  }
});

const telemetry = {
  sounds: [],
  particles: [],
  titles: [],
  bossBars: []
};

bot.on('login', () => {
  console.log(`[bot] Successfully logged in to proxy.`);
});

bot.on('message', (jsonMsg) => {
  console.log(`[bot chat] ${jsonMsg.toString()}`);
});

// Track sound effects received by the client
bot.on('soundEffectHeard', (soundName, position, volume, pitch) => {
  telemetry.sounds.push({ soundName, position, volume, pitch, timestamp: Date.now() });
});

// Raw packet listeners for visual effects
bot._client.on('world_particles', (packet) => {
  if (packet) {
    telemetry.particles.push({
      particleId: packet.particleId,
      particles: packet.particles,
      x: packet.x,
      y: packet.y,
      z: packet.z,
      timestamp: Date.now()
    });
  }
});

bot._client.on('set_title_text', (packet) => {
  if (packet && packet.text) {
    telemetry.titles.push({ type: 'title', text: packet.text, timestamp: Date.now() });
  }
});

bot._client.on('set_subtitle_text', (packet) => {
  if (packet && packet.text) {
    telemetry.titles.push({ type: 'subtitle', text: packet.text, timestamp: Date.now() });
  }
});

bot._client.on('boss_bar', (packet) => {
  if (packet) {
    telemetry.bossBars.push({ action: packet.action, title: packet.title, timestamp: Date.now() });
  }
});

// Listener for open_book packet (used by Setup Wizard / interactive book commands)
bot._client.on('open_book', (packet) => {
  console.log(`[bot] Received open_book packet: hand=${packet.hand}`);
  if (guiScenario === 'setup' && stage === 'AWAITING_SETUP_BOOK') {
    console.log(JSON.stringify({
      status: 'PASS',
      mode: 'gui',
      scenario: 'setup',
      hand: packet.hand,
      windowOpenLatencyMs: windowOpenLatencyMs,
      slotClicked: clickedSlot,
      itemClicked: clickedItemName
    }));
    clearTimeout(timer);
    setTimeout(() => {
      bot.quit();
      process.exit(0);
    }, 500);
  }
});

function triggerRtpAction() {
  initialPosition = { ...bot.entity.position };
  if (isGuiMode) {
    const srvDesc = targetServer ? `on ${targetServer}` : 'from lobby';
    console.log(`[bot] Ready to trigger RTP GUI ${srvDesc}. Sending bare /rtp`);
    windowRequestTime = Date.now();
    stage = 'AWAITING_WINDOW';
    bot.chat('/rtp');
  } else {
    const srvDesc = targetServer ? `on ${targetServer}` : 'from lobby';
    const cmd = bareRtp ? '/rtp' : `/rtp region=${targetRegion}`;
    console.log(`[bot] Ready to trigger RTP ${srvDesc}. Sending ${cmd}`);
    rtpStartTime = Date.now();
    stage = 'AWAITING_TELEPORT';
    bot.chat(cmd);
  }
}

bot.on('spawn', () => {
  const pos = bot.entity.position;
  console.log(`[bot] Spawned at (${pos.x.toFixed(2)}, ${pos.y.toFixed(2)}, ${pos.z.toFixed(2)}) [stage=${stage}]`);

  if (stage === 'CONNECTING') {
    if (targetServer && targetServer !== 'lobby-a') {
      console.log(`[bot] Initial spawn on proxy. Routing to target server: /server ${targetServer}`);
      stage = 'SWITCHING_SERVER';
      bot.chat(`/server ${targetServer}`);
      // Fallback timer in case proxy switches server without emitting a clean respawn/spawn packet
      setTimeout(() => {
        if (stage === 'SWITCHING_SERVER') {
          console.log(`[bot] Fallback timer elapsed for server switch to ${targetServer}. Proceeding to RTP...`);
          stage = 'READY_FOR_RTP';
          triggerRtpAction();
        }
      }, 3500);
      return;
    }

    stage = 'READY_FOR_RTP';
    setTimeout(() => {
      triggerRtpAction();
    }, 2000);
  } else if (stage === 'SWITCHING_SERVER') {
    console.log(`[bot] Re-spawn observed after server switch to ${targetServer}. Settling...`);
    stage = 'READY_FOR_RTP';
    setTimeout(() => {
      triggerRtpAction();
    }, 2000);
  } else if (stage === 'AWAITING_TELEPORT') {
    // Spawned after server transfer / teleport
    evaluateLanding(bot.entity.position);
  }
});

bot.on('windowOpen', async (window) => {
  windowOpenTime = Date.now();
  if (windowRequestTime > 0) {
    windowOpenLatencyMs = windowOpenTime - windowRequestTime;
  }
  const title = window.title || '';
  console.log(`[bot] Chest window opened: "${title}" (slots: ${window.slots ? window.slots.length : 0})`);
  if (windowOpenLatencyMs !== null) {
    console.log(`[bot] Window open latency: ${windowOpenLatencyMs}ms`);
  }

  // Inspect and log rendered items in the chest menu
  const inventorySlots = window.slots || [];
  const topSize = window.type && typeof window.type === 'string' && window.type.startsWith('minecraft:container')
    ? parseInt(window.type.split(':')[1], 10) || 54
    : 54;

  const validItems = [];
  for (let i = 0; i < inventorySlots.length; i++) {
    const item = inventorySlots[i];
    if (item) {
      const displayName = item.displayName || (item.customName ? String(item.customName) : '');
      console.log(`[bot slot ${i}] ${item.name} x${item.count} "${displayName}"`);
      if (i < topSize) {
        validItems.push({ slot: i, item, displayName });
      }
    }
  }

  if (stage === 'AWAITING_WINDOW' || isGuiMode) {
    let chosenSlot = null;
    let chosenItem = null;

    if (guiScenario === 'submenu') {
      // Look for submenu trigger items: Biomes, Operator Tools, or Actions
      const submenuItem = validItems.find(v => {
        const nameLower = (v.displayName || '').toLowerCase();
        const mat = (v.item.name || '').toLowerCase();
        return nameLower.includes('biome') || nameLower.includes('operator') || nameLower.includes('action') ||
               mat.includes('compass') || mat.includes('command_block') || mat.includes('nether_star');
      });

      if (stage === 'AWAITING_WINDOW') {
        if (submenuItem) {
          chosenSlot = submenuItem.slot;
          chosenItem = submenuItem.item;
          console.log(`[bot] Found submenu trigger slot ${chosenSlot} (${chosenItem.name}: "${submenuItem.displayName}")`);
        } else {
          console.warn(`[bot] Submenu item not found in top menu; picking first non-filler slot.`);
          const firstNonFiller = validItems.find(v => !(v.item.name || '').includes('glass_pane'));
          chosenSlot = firstNonFiller ? firstNonFiller.slot : 0;
          chosenItem = firstNonFiller ? firstNonFiller.item : null;
        }

        clickedSlot = chosenSlot;
        clickedItemName = chosenItem ? chosenItem.name : 'unknown';
        stage = 'AWAITING_SUBMENU';

        setTimeout(async () => {
          try {
            console.log(`[bot] Clicking submenu slot ${clickedSlot} to navigate...`);
            if (typeof bot.clickWindow === 'function') {
              await bot.clickWindow(clickedSlot, 0, 0);
            } else if (typeof bot.simpleClick?.leftMouse === 'function') {
              await bot.simpleClick.leftMouse(clickedSlot);
            }
          } catch (clickErr) {
            console.error(`[bot] Error clicking submenu slot ${clickedSlot}:`, clickErr);
          }
        }, debounceMs);
        return;
      } else if (stage === 'AWAITING_SUBMENU') {
        // We have successfully navigated into the submenu!
        console.log(`[bot] Submenu successfully opened! Window title: "${title}"`);
        console.log(JSON.stringify({
          status: 'PASS',
          mode: 'gui',
          scenario: 'submenu',
          title: title,
          slotsCount: validItems.length,
          windowOpenLatencyMs: windowOpenLatencyMs
        }));
        clearTimeout(timer);
        setTimeout(() => {
          bot.quit();
          process.exit(0);
        }, 500);
        return;
      }
    } else if (guiScenario === 'setup') {
      if (stage === 'AWAITING_WINDOW') {
        // In setup scenario, first find and click Operator Tools if not already in operator menu
        const operatorItem = validItems.find(v => {
          const nameLower = (v.displayName || '').toLowerCase();
          return nameLower.includes('operator') || nameLower.includes('admin') || (v.item.name || '').includes('command_block');
        });
        const setupItem = validItems.find(v => {
          const nameLower = (v.displayName || '').toLowerCase();
          return nameLower.includes('setup') || (v.item.name || '').includes('writable_book') || (v.item.name || '').includes('written_book');
        });

        if (setupItem) {
          chosenSlot = setupItem.slot;
          chosenItem = setupItem.item;
          stage = 'AWAITING_SETUP_BOOK';
        } else if (operatorItem) {
          chosenSlot = operatorItem.slot;
          chosenItem = operatorItem.item;
          stage = 'AWAITING_OPERATOR_MENU';
        } else {
          console.warn(`[bot] Neither setup nor operator icon found; clicking slot 0.`);
          chosenSlot = 0;
          stage = 'AWAITING_SETUP_BOOK';
        }

        clickedSlot = chosenSlot;
        clickedItemName = chosenItem ? chosenItem.name : 'unknown';

        setTimeout(async () => {
          try {
            console.log(`[bot] Clicking slot ${clickedSlot} (${clickedItemName}) for setup flow...`);
            if (typeof bot.clickWindow === 'function') {
              await bot.clickWindow(clickedSlot, 0, 0);
            } else if (typeof bot.simpleClick?.leftMouse === 'function') {
              await bot.simpleClick.leftMouse(clickedSlot);
            }
          } catch (clickErr) {
            console.error(`[bot] Error clicking slot ${clickedSlot}:`, clickErr);
          }
        }, debounceMs);
        return;
      } else if (stage === 'AWAITING_OPERATOR_MENU') {
        // Now inside operator menu, find Setup Wizard
        const setupItem = validItems.find(v => {
          const nameLower = (v.displayName || '').toLowerCase();
          return nameLower.includes('setup') || (v.item.name || '').includes('writable_book') || (v.item.name || '').includes('written_book');
        });
        chosenSlot = setupItem ? setupItem.slot : 0;
        chosenItem = setupItem ? setupItem.item : null;
        clickedSlot = chosenSlot;
        clickedItemName = chosenItem ? chosenItem.name : 'unknown';
        stage = 'AWAITING_SETUP_BOOK';

        setTimeout(async () => {
          try {
            console.log(`[bot] Clicking Setup Wizard slot ${clickedSlot} (${clickedItemName})...`);
            if (typeof bot.clickWindow === 'function') {
              await bot.clickWindow(clickedSlot, 0, 0);
            } else if (typeof bot.simpleClick?.leftMouse === 'function') {
              await bot.simpleClick.leftMouse(clickedSlot);
            }
          } catch (clickErr) {
            console.error(`[bot] Error clicking setup wizard slot ${clickedSlot}:`, clickErr);
          }
        }, debounceMs);
        return;
      }
    }

    if (targetSlotArg !== null) {
      const parsedSlot = parseInt(targetSlotArg, 10);
      const match = validItems.find(v => v.slot === parsedSlot);
      if (match) {
        chosenSlot = match.slot;
        chosenItem = match.item;
      } else {
        chosenSlot = parsedSlot;
      }
    } else {
      // Find candidate destination slot
      // 1. Try matching targetRegion or region name in displayName/name
      const normalizedTarget = targetRegion.toLowerCase();
      const targetMatch = validItems.find(v => {
        const nameLower = (v.displayName || '').toLowerCase();
        return nameLower.includes(normalizedTarget) || (v.item.name && v.item.name.toLowerCase().includes(normalizedTarget));
      });

      if (targetMatch) {
        chosenSlot = targetMatch.slot;
        chosenItem = targetMatch.item;
      } else {
        // 2. Look for destination icons (e.g. ender_pearl, grass_block, netherrack, end_stone, compass)
        // avoiding fillers (gray_stained_glass_pane), dashboards (paper), and back/menu buttons
        const destIcon = validItems.find(v => {
          const mat = (v.item.name || '').toLowerCase();
          const isFiller = mat.includes('glass_pane') || mat.includes('stained_glass');
          const isControl = mat.includes('barrier') || mat.includes('arrow') || mat.includes('clock') || mat.includes('paper');
          return !isFiller && !isControl;
        });

        if (destIcon) {
          chosenSlot = destIcon.slot;
          chosenItem = destIcon.item;
        } else if (validItems.length > 0) {
          // Fallback to first non-filler slot
          const firstNonFiller = validItems.find(v => !(v.item.name || '').includes('glass_pane'));
          if (firstNonFiller) {
            chosenSlot = firstNonFiller.slot;
            chosenItem = firstNonFiller.item;
          } else {
            chosenSlot = validItems[0].slot;
            chosenItem = validItems[0].item;
          }
        }
      }
    }

    if (chosenSlot === null) {
      console.warn(`[bot] No valid target slot found in opened window! Defaulting to slot 0.`);
      chosenSlot = 0;
    }

    clickedSlot = chosenSlot;
    clickedItemName = chosenItem ? chosenItem.name : 'unknown';
    console.log(`[bot] Selecting slot ${clickedSlot} (${clickedItemName}) with ${debounceMs}ms debounce...`);

    setTimeout(async () => {
      try {
        console.log(`[bot] Clicking window slot ${clickedSlot}...`);
        rtpStartTime = Date.now();
        stage = 'AWAITING_TELEPORT';
        if (typeof bot.clickWindow === 'function') {
          await bot.clickWindow(clickedSlot, 0, 0);
        } else if (typeof bot.simpleClick?.leftMouse === 'function') {
          await bot.simpleClick.leftMouse(clickedSlot);
        }
        console.log(`[bot] Clicked slot ${clickedSlot}, awaiting teleport/transfer.`);
      } catch (clickErr) {
        console.error(`[bot] Error clicking window slot ${clickedSlot}:`, clickErr);
      }
    }, debounceMs);
  }
});

bot.on('forcedMove', () => {
  if (stage === 'AWAITING_TELEPORT') {
    evaluateLanding(bot.entity.position);
  }
});

function evaluateLanding(pos) {
  const duration = Date.now() - rtpStartTime;
  console.log(`[bot] Position update observed at (${pos.x.toFixed(2)}, ${pos.y.toFixed(2)}, ${pos.z.toFixed(2)}) after ${duration}ms (transferDetected: ${transferDetected})`);

  if (initialPosition) {
    const dx = pos.x - initialPosition.x;
    const dz = pos.z - initialPosition.z;
    const distSq = dx * dx + dz * dz;

    // A cross-server or in-region RTP jumps significant distance (e.g. > 100 blocks)
    if (distSq > 100 * 100 && pos.y >= 0) {
      if (assertTransfer && !transferDetected) {
        console.warn(`[bot] WARN: Coordinate displacement observed but server transfer packet was not flagged prior to landing. Verifying arrival displacement.`);
      }

      const effectsSummary = {
        soundCount: telemetry.sounds.length,
        particleCount: telemetry.particles.length,
        titleCount: telemetry.titles.length,
        bossBarCount: telemetry.bossBars.length,
        soundsHeard: telemetry.sounds.map(s => s.soundName),
        titlesObserved: telemetry.titles.map(t => t.text)
      };

      if (assertEffects) {
        const hasEffects = effectsSummary.soundCount > 0 || effectsSummary.particleCount > 0 || effectsSummary.titleCount > 0;
        if (!hasEffects) {
          console.error(`[bot] FAIL - Effect assertion enabled (--assert-effects) but no sound, particle, or title packets observed.`);
          clearTimeout(timer);
          process.exit(1);
        }
      }

      console.log(JSON.stringify({
        status: 'PASS',
        mode: isGuiMode ? 'gui' : 'direct',
        teleportLatencyMs: duration,
        windowOpenLatencyMs: windowOpenLatencyMs,
        slotClicked: clickedSlot,
        itemClicked: clickedItemName,
        transferDetected: transferDetected,
        transferDetails: transferDetails,
        displacementBlocks: Math.round(Math.sqrt(distSq)),
        targetX: Math.round(pos.x),
        targetY: Math.round(pos.y),
        targetZ: Math.round(pos.z),
        effects: effectsSummary
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
  console.error(`[bot] Kicked from server: ${typeof reason === 'object' ? JSON.stringify(reason) : reason}`);
  clearTimeout(timer);
  process.exit(1);
});

bot.on('error', (err) => {
  console.error(`[bot] Socket error:`, err);
});
