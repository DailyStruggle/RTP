# BIT Code Catalog

Generated from the registered BIT catalog. IDs and keys are stable; retired codes are not reused.

## Namespaces

| Prefix |
|---|
| `ACTION` |
| `BIT` |
| `HEATMAP` |
| `PROBE` |
| `RTP` |
| `TEST` |

## `ACTION`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 2001 | `ACTION_UNKNOWN_LIFECYCLE_STAGE` | ERROR | PBIT | action | - | Rename the stage to one listed in expected; unknown stages never run. |
| 2002 | `ACTION_UNKNOWN_STEP_KEY` | ERROR | PBIT | action | - | Rename the step key to one listed in expected; unknown keys are dropped at load. |
| 2003 | `ACTION_UNKNOWN_ACTION_NAME` | ERROR | PBIT | action | - | Use an ACTION: name listed in expected; unknown names are ignored during loading. |
| 2004 | `ACTION_UNKNOWN_GATE_TYPE` | ERROR | PBIT | action | - | Use a gate type listed in expected; unknown gates always evaluate false. |
| 2005 | `ACTION_UNKNOWN_TRIGGER_TYPE` | ERROR | PBIT | action | - | Use a trigger type listed in expected; triggers with unknown types are skipped. |
| 2006 | `ACTION_PLACEHOLDER_OUT_OF_SCOPE` | ERROR | PBIT | action | - | Use this placeholder only in the lifecycle stage or action scope that provides it. |
| 2007 | `ACTION_PLACEHOLDER_UNKNOWN` | WARNING | PBIT | action | - | Check the placeholder spelling; metadata-defined tokens are not validated as known tokens. |
| 2008 | `ACTION_SCOREBOARD_MANAGED_TYPO` | ERROR | PBIT | action | - | Use the suggested RTP-managed scoreboard objective or choose an external objective. |
| 2009 | `ACTION_SCOREBOARD_OBJECTIVE_MISSING` | WARNING | PBIT | action | - | Create the scoreboard objective on the server or correct its name. |
| 2010 | `ACTION_GATE_EXPRESSION_INVALID` | ERROR | PBIT | action | - | Use a numeric comparison, exact number, or inclusive min..max range accepted by GateExpressionParser. |
| 2011 | `ACTION_UNKNOWN_GATE_SUBKEY` | ERROR | PBIT | action | - | Use a gate field listed in expected; gate field names are case-sensitive. |
| 2012 | `ACTION_MISSING_GATE_SUBKEY` | ERROR | PBIT | action | - | Add the required gate field or one of the listed alternatives. |
| 2013 | `ACTION_UNKNOWN_REGION` | ERROR | PBIT | action | - | Choose a region present in the loaded region configuration. |
| 2014 | `ACTION_SCHEMATIC_UNRESOLVED` | ERROR | PBIT | action | - | Place the schematic under advanced/schematics and reference an existing file. |
| 2015 | `ACTION_PAYLOAD_UNBALANCED` | ERROR | PBIT | action | - | Balance quotes, brackets and braces in the vanilla command payload. |
| 2016 | `ACTION_PAYLOAD_JSON_INVALID` | ERROR | PBIT | action | - | Provide a valid JSON text component where tellraw or title expects one. |
| 2101 | `ACTION_CBIT_OBJECTIVE_MISSING` | WARNING | CBIT | action | - | Create the external scoreboard objective or correct its name. |
| 2102 | `ACTION_CBIT_GATE_NEVER_PASSED` | WARNING | CBIT | action | - | Check the gate expression and the runtime values it reads. |
| 2103 | `ACTION_CBIT_GATE_ALWAYS_PASSED` | INFO | CBIT | action | - | Confirm that the gate is intended to pass for every observed evaluation. |
| 2104 | `ACTION_CBIT_PLACEHOLDER_UNRESOLVED` | WARNING | CBIT | action | - | Use a placeholder available at this dispatch stage or correct its spelling. |
| 2105 | `ACTION_CBIT_UNKNOWN_ACTION` | WARNING | CBIT | action | - | Use one of the registered ACTION names. |
| 2106 | `ACTION_CBIT_SESSION_ORPHANED` | WARNING | CBIT | action | - | Check action lifecycle and teardown behavior before the session reaper window expires. |
| 2107 | `ACTION_SELFTEST_MISMATCH` | ERROR | PBIT | action | - | Make the bundled expected commands match the real action parser, gates, and session output. |
| 2108 | `ACTION_SELFTEST_CASE_INVALID` | ERROR | PBIT | action | - | Repair the self-test case structure and use a supported deterministic simulation script. |

## `BIT`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 1 | `BIT_PBIT_FAILED` | ERROR | PBIT | bit | - | Inspect the component diagnostic message and fix the failing check. |
| 2 | `BIT_PBIT_THROWN` | ERROR | PBIT | bit | - | The component threw instead of reporting; capture failures in its PbitResult. |
| 3 | `BIT_HEALTH_FAILED` | ERROR | PBIT | bit | - | The component passed PBIT but reports FAILED health; check its runtime state. |
| 4 | `BIT_HEALTH_DEGRADED` | WARNING | PBIT | bit | - | The component reports DEGRADED health; check its warnings. |
| 5 | `BIT_COMPONENT_TIMEOUT` | ERROR | IBIT | bit | - | Increase the component timeout or make the check bounded. |
| 6 | `BIT_COMPONENT_THROWN` | ERROR | IBIT | bit | - | Fix the component failure and return a BitReport instead of throwing. |
| 7 | `BIT_COMPONENT_UNKNOWN` | ERROR | IBIT | bit | - | Select a registered component or a matching tier/subsystem. |
| 8 | `BIT_OPTIONAL_UNAVAILABLE` | INFO | PBIT | bit | - | Install zstd-jni to enable Zstandard-backed region-file support. |
| 9 | `BIT_NAMESPACE_CONFLICT` | ERROR | PBIT | bit | - | Two plugins reserved the same BIT code prefix; change one plugin's prefix or remove one of them. |

## `HEATMAP`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 4700 | `HEATMAP_MOCKED` | INFO | IBIT | test.heatmap | - | Heatmap results are generated by synthetic BIT players, not real accounts. |
| 4701 | `HEATMAP_UNAVAILABLE` | ERROR | IBIT | test.heatmap | - | Load RTP and select a region whose world is available. |
| 4702 | `HEATMAP_TELEPORT_FAILED` | ERROR | IBIT | test.heatmap | - | Inspect the RTP pipeline failure and retry the heatmap probe. |
| 4703 | `HEATMAP_LANDING_MISSING` | ERROR | IBIT | test.heatmap | - | Verify that successful mock landings are appended to BitTeleportLog. |
| 4704 | `HEATMAP_EXPORT_FAILED` | ERROR | IBIT | test.heatmap | - | Check the plugin data folder is writable and rerun the probe. |
| 4705 | `HEATMAP_CANCELLED` | WARNING | IBIT | test.heatmap | - | Rerun the named probe after the current test is no longer cancelled. |

## `PROBE`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 4101 | `PROBE_STRESS_MOCKED` | INFO | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4102 | `PROBE_STRESS_UNAVAILABLE` | ERROR | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4103 | `PROBE_STRESS_REJECTED` | ERROR | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4104 | `PROBE_STRESS_SET_LOCATION` | ERROR | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4105 | `PROBE_STRESS_PROCESSING_LEAK` | ERROR | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4106 | `PROBE_STRESS_PLAYER_FAILURES` | ERROR | IBIT | test.stress | - | Inspect the RTP stress pipeline result and rerun with bounded parameters. |
| 4107 | `PROBE_STRESS_PLAYER_CANCELLED` | WARNING | IBIT | test.stress-player | - | Allow the bounded player stress run to complete. |
| 4201 | `PROBE_QUEUE_STARVATION_FAILURES` | ERROR | IBIT | test.queue-starvation | - | Resolve the queue sampling failure and rerun the bounded probe. |
| 4202 | `PROBE_QUEUE_STARVATION_STALLS` | ERROR | IBIT | test.queue-starvation | - | Resolve the queue sampling failure and rerun the bounded probe. |
| 4203 | `PROBE_QUEUE_STARVATION_COMPUTE_THROWN` | ERROR | IBIT | test.queue-starvation | - | Resolve the queue sampling failure and rerun the bounded probe. |
| 4204 | `PROBE_QUEUE_STARVATION_REGION_UNAVAILABLE` | ERROR | IBIT | test.queue-starvation | - | Resolve the queue sampling failure and rerun the bounded probe. |
| 4205 | `PROBE_QUEUE_STARVATION_CANCELLED` | WARNING | IBIT | test.queue-starvation | - | Allow the bounded sampling run to complete. |
| 4301 | `PROBE_DISCONNECT_MIDFLIGHT_PROCESSING` | ERROR | IBIT | test.disconnect-midflight | - | Verify the disconnect cleanup releases this player state. |
| 4302 | `PROBE_DISCONNECT_MIDFLIGHT_INVULNERABLE` | ERROR | IBIT | test.disconnect-midflight | - | Verify the disconnect cleanup releases this player state. |
| 4303 | `PROBE_DISCONNECT_MIDFLIGHT_NEXT_TASK` | ERROR | IBIT | test.disconnect-midflight | - | Verify the disconnect cleanup releases this player state. |
| 4304 | `PROBE_DISCONNECT_MIDFLIGHT_TELEPORT_DATA` | ERROR | IBIT | test.disconnect-midflight | - | Verify the disconnect cleanup releases this player state. |
| 4305 | `PROBE_DISCONNECT_MIDFLIGHT_THREW` | ERROR | IBIT | test.disconnect-midflight | - | Verify the disconnect cleanup releases this player state. |
| 4306 | `PROBE_DISCONNECT_MIDFLIGHT_NO_WORLD` | INFO | IBIT | test.disconnect-midflight | - | Load the default region world to use a registered BIT mock player. |
| 4401 | `PROBE_FOLIA_OWNERSHIP` | ERROR | IBIT | test.folia-ownership | - | Verify the region scheduler owns the location before changing the mock location. |
| 4402 | `PROBE_FOLIA_OWNERSHIP_WORLD_UNAVAILABLE` | ERROR | IBIT | test.folia-ownership | - | Verify the region scheduler owns the location before changing the mock location. |
| 4403 | `PROBE_FOLIA_OWNERSHIP_UNSUPPORTED` | INFO | IBIT | test.folia-ownership | - | Run this probe on a Folia server to verify region ownership. |
| 4501 | `PROBE_ASYNC_REPLY_PIPELINE_THROWN` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4502 | `PROBE_ASYNC_REPLY_PIPELINE_NOT_REACHED` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4503 | `PROBE_ASYNC_REPLY_PROCESSING_LEAK` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4504 | `PROBE_ASYNC_REPLY_WORLD_UNAVAILABLE` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4505 | `PROBE_ASYNC_REPLY_PLAYER_UNKNOWN` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4506 | `PROBE_ASYNC_REPLY_THREAD_UNSAFE` | ERROR | IBIT | test.async-reply | - | Verify the asynchronous reply pipeline and mock teleport cleanup. |
| 4507 | `PROBE_ASYNC_REPLY_MOCKED_PIPELINE` | INFO | IBIT | test.async-reply | - | This result exercises the pipeline with a registered mock, not a real player. |
| 4601 | `PROBE_HOLOGRAM_PLAYER_UNKNOWN` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4602 | `PROBE_HOLOGRAM_LOCATION_UNAVAILABLE` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4603 | `PROBE_HOLOGRAM_SPAWN_FAILED` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4604 | `PROBE_HOLOGRAM_NULL_HANDLE` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4605 | `PROBE_HOLOGRAM_CLOSE_FAILED` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4606 | `PROBE_HOLOGRAM_SCHEDULER_UNAVAILABLE` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4607 | `PROBE_HOLOGRAM_UPDATE_FAILED` | ERROR | IBIT | test.hologram | - | Inspect the hologram provider and ensure its handle closes successfully. |
| 4608 | `PROBE_HOLOGRAM_CONSOLE_STATUS` | INFO | IBIT | test.hologram | - | Provide player=<name> to spawn the hologram in-world. |
| 4609 | `PROBE_HOLOGRAM_CANCELLED` | WARNING | IBIT | test.hologram | - | Allow the hologram countdown to complete or rerun it. |

## `RTP`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 1001 | `RTP_YAML_UNPARSABLE` | ERROR | PBIT | config | - | Fix the YAML syntax in source; a reload keeps the running config until the file parses. |
| 1002 | `RTP_YAML_UNSUPPORTED_UNOWNED` | WARNING | PBIT | config | - | RTP does not load this file, so it cannot break a reload; fix or remove it if it should be read. |
| 1100 | `RTP_DIRECTIVE_TYPE_MISMATCH` | ERROR | PBIT | config | - | Write a value of the type named by the key's @type directive in the bundled default file. |
| 1101 | `RTP_DIRECTIVE_OPTION_UNKNOWN` | ERROR | PBIT | config | - | Use one of the values listed by the key's @options directive (case does not matter). |
| 1102 | `RTP_DIRECTIVE_OUT_OF_RANGE` | ERROR | PBIT | config | - | Keep the value inside the key's @range [min, max] (inclusive; null is open-ended). |
| 1103 | `RTP_DIRECTIVE_UNKNOWN_KEY` | WARNING | PBIT | config | - | Remove or rename the key; it is not read. Compare with the bundled default file. |
| 1104 | `RTP_DIRECTIVE_DEFAULT_INVALID` | ERROR | PBIT | config | - | Shipping bug: the bundled default value or @default violates its own directives; fix the resource. |
| 1105 | `RTP_DIRECTIVE_MALFORMED` | ERROR | PBIT | config | - | Shipping bug: the bundled default does not parse or has an unreadable directive; fix the resource. |
| 1201 | `RTP_REFERENCE_UNKNOWN_FILE` | ERROR | PBIT | config | - | Use @config, @economy or @safety, or replace the token with a literal value. |
| 1202 | `RTP_REFERENCE_SETTING_MISSING` | ERROR | PBIT | config | - | Add the setting to the referenced file (config.yml: under defaults) or use a literal value. |
| 1301 | `RTP_REGION_UNKNOWN_WORLD` | ERROR | PBIT | config | - | Name a loaded world (or [index]); a region on an unloaded world stays dormant until it loads. |
| 1302 | `RTP_WORLD_UNKNOWN_REGION` | ERROR | PBIT | config | - | Name a region file in definitions/regions; an unknown region falls back to default. |
| 1303 | `RTP_REGION_UNKNOWN_OVERRIDE` | ERROR | PBIT | config | - | Name a region file in definitions/regions as the no-permission override. |
| 1304 | `RTP_WORLD_UNKNOWN_OVERRIDE` | ERROR | PBIT | config | - | Name a loaded world, a definitions/worlds file or [index] as the no-permission override. |
| 1305 | `RTP_SCHEMATIC_ORPHAN` | WARNING | PBIT | config | - | Rename the schematic to <region>.schem, reference it from an action placement.schematic, or remove it. |
| 1306 | `RTP_WORLD_REFERENCES_UNVERIFIED` | INFO | PBIT | config | - | Run the check on a server (or pass loaded world names) to verify world references. |
| 1401 | `RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER` | ERROR | PBIT | config | - | Set shape.radius greater than shape.centerRadius; an equal or larger inner hole leaves no landing area. |
| 1402 | `RTP_ACTION_RADIUS_NOT_ABOVE_CENTER` | ERROR | PBIT | config | - | Set radius greater than centerRadius in the action's placement or confinement block. |
| 1403 | `RTP_ACTION_SHRINK_ABOVE_INITIAL` | ERROR | PBIT | config | - | Set confinement.shrinkTo at most confinement.initialSize; the border only shrinks. |
| 1501 | `RTP_MESSAGES_BASELINE_UNMAPPED` | WARNING | PBIT | config | - | Add a row for the key to the baseline .<file>.lang.yml so locales can rename it. |
| 1502 | `RTP_MESSAGES_LOCALE_VALUES_MISSING` | WARNING | PBIT | config | - | Ship the locale's <file>.yml beside its .<file>.lang.yml, or remove the rename map. |
| 1503 | `RTP_MESSAGES_LOCALE_KEY_MISSING` | WARNING | PBIT | config | - | Mirror the baseline key into the locale value file under its translated name. |
| 1504 | `RTP_MESSAGES_LOCALE_STALE_ROW` | WARNING | PBIT | config | - | Remove the rename-map row; the baseline no longer ships that key. |
| 1505 | `RTP_MESSAGES_LOCALE_UNRESOLVED_ROW` | WARNING | PBIT | config | - | Make the rename-map target a top-level key of the sibling value file. |
| 1510 | `RTP_MESSAGES_KEY_MISSING` | WARNING | PBIT | config | - | Add the key back (or delete the file to regenerate it); until then the English default is used. |
| 1511 | `RTP_MESSAGES_KEY_UNKNOWN` | WARNING | PBIT | config | - | Rename the key to a known message key or remove it; unknown keys are never read. |
| 1600 | `RTP_EFFECTS_UNKNOWN_KEY` | ERROR | PBIT | config | - | Remove or rename the key; an effect group accepts only version, when, permission, players, inherit and effects. |
| 1601 | `RTP_EFFECTS_MISSING_STAGE` | ERROR | PBIT | config | - | Add when: <stage>; a group without a stage never fires. |
| 1602 | `RTP_EFFECTS_UNKNOWN_STAGE` | ERROR | PBIT | config | - | Set when: to one stage token, e.g. preteleport or postteleport. |
| 1603 | `RTP_EFFECTS_STAGE_NOT_DISPATCHED` | WARNING | PBIT | config | - | No platform fires this stage; bind the group to a dispatched stage or use an action instead. |
| 1604 | `RTP_EFFECTS_INHERIT_UNRESOLVED` | ERROR | PBIT | config | - | Name an existing group file in definitions/effects (without .yml) or remove the entry. |
| 1605 | `RTP_EFFECTS_INHERIT_CYCLE` | ERROR | PBIT | config | - | Break the inherit: loop; at runtime the walk is truncated and effects are lost. |
| 1606 | `RTP_EFFECTS_UNKNOWN_EFFECT` | ERROR | PBIT | config | - | Use a registered effect name (first token before '.') or install the addon that provides it. |
| 1607 | `RTP_EFFECTS_BAD_TYPE` | ERROR | PBIT | config | - | when and permission take one string; players, inherit and effects take a list of strings. |
| 1701 | `RTP_NETWORK_KEY_UNKNOWN` | WARNING | PBIT | config | - | Rename or remove the top-level key; network.yml readers ignore it. |
| 1702 | `RTP_NETWORK_SECTION_NOT_MAP` | WARNING | PBIT | config | - | Make the section a YAML mapping; a scalar is read as an absent section (all defaults). |
| 1703 | `RTP_NETWORK_SERVER_ID_MISSING` | ERROR | PBIT | config | - | Set network.serverId to this server's unique name; network mode is refused without it. |
| 1704 | `RTP_NETWORK_TRANSPORT_UNKNOWN` | ERROR | PBIT | config | - | Set transport.type to auto, plugin-message, proxy-cache, proxy-direct, in-memory, sql or redis. |
| 1705 | `RTP_NETWORK_REDIS_HOST_MISSING` | ERROR | PBIT | config | - | Set transport.redis.host (host or redis[s]:// URL) shared by every server. |
| 1706 | `RTP_NETWORK_PROXIES_MISSING` | ERROR | PBIT | config | - | List each proxy companion as host:port under transport.proxies. |
| 1707 | `RTP_NETWORK_PORT_RANGE` | ERROR | PBIT | config | - | Use a TCP port between 1 and 65535. |
| 1708 | `RTP_NETWORK_DURATION_INVALID` | WARNING | PBIT | config | - | Use a number of milliseconds or a duration with a unit (e.g. 500, 5s, 1m); invalid values fall back to the default. |
| 1709 | `RTP_NETWORK_STALE_BEFORE_INTERVAL` | WARNING | PBIT | config | - | Keep heartbeat.staleAfterMs above heartbeat.intervalMs, or live peers flap to stale between beats. |
| 1710 | `RTP_NETWORK_VALUE_UNKNOWN` | WARNING | PBIT | config | - | Use one of the listed values; unrecognised values silently fall back to the default. |

## `TEST`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 3001 | `TEST_ACCESSOR_UNAVAILABLE` | ERROR | IBIT | test.accessor | - | Initialize the server accessor before running the test. |
| 3002 | `TEST_ACCESSOR_MATERIALS` | ERROR | IBIT | test.accessor | - | Provide a non-empty material set containing common materials. |
| 3003 | `TEST_ACCESSOR_TAGS` | ERROR | IBIT | test.accessor | - | Return a non-null block tag snapshot. |
| 3004 | `TEST_ACCESSOR_SENDER` | ERROR | IBIT | test.accessor | - | Provide a valid console sender and return null for unknown players. |
| 3005 | `TEST_ACCESSOR_FORMAT` | ERROR | IBIT | test.accessor | - | Return non-null formatted and color-stripped messages. |
| 3006 | `TEST_ACCESSOR_THREAD` | ERROR | IBIT | test.accessor | - | Return a non-negative scheduler overtime measurement. |
| 3007 | `TEST_ACCESSOR_BIOME` | ERROR | IBIT | test.accessor | - | Return a biome sample for an available world. |
| 3008 | `TEST_ACCESSOR_MENU` | ERROR | IBIT | test.accessor | - | Provide the expected menu permission and locale queries. |
| 3009 | `TEST_ACCESSOR_VERSION` | ERROR | IBIT | test.accessor | - | Return complete platform and server version metadata. |
| 3010 | `TEST_ACCESSOR_WORLD` | ERROR | IBIT | test.accessor | - | Return consistent world lookups and world metadata. |
| 3011 | `TEST_ACCESSOR_MESSAGING` | ERROR | IBIT | test.accessor | - | Support the server accessor message and announcement overloads. |
| 3012 | `TEST_ACCESSOR_SUBSYSTEM` | ERROR | IBIT | test.accessor | - | Provide TPS, plugin directory, scheduler and location generator access. |
| 3013 | `TEST_ACCESSOR_WORLD_BORDER` | ERROR | IBIT | test.accessor | - | Support world-border updates and resets for online players. |
| 3101 | `TEST_CHUNK_TICKET_OPEN` | ERROR | IBIT | test.chunk-ticket | - | Ensure opening a chunk ticket increments the live count. |
| 3102 | `TEST_CHUNK_TICKET_RELEASE` | ERROR | IBIT | test.chunk-ticket | - | Release the chunk ticket and restore the baseline count. |
| 3103 | `TEST_CHUNK_TICKET_TRACKER_LEAK` | ERROR | IBIT | test.chunk-ticket | - | Untrack every ticket created by the probe. |
| 3104 | `TEST_CHUNK_TICKET_SENTINEL_UNTRACK` | ERROR | IBIT | test.chunk-ticket | - | Make both sentinel untrack operations return to the baseline. |
| 3105 | `TEST_CHUNK_TICKET_SENTINEL_DIAGNOSTICS` | ERROR | IBIT | test.chunk-ticket | - | Keep a live sentinel during diagnostics and release it afterward. |
| 3106 | `TEST_CHUNK_TICKET_STALE_SENTINEL` | WARNING | IBIT | test.chunk-ticket | - | Investigate the previous interrupted probe and clear its stale sentinels. |
| 3201 | `TEST_TELEPORT_MOCKED` | INFO | IBIT | test.teleport | - | Results come from synthetic rtp-bit-* players, not real accounts. |
| 3202 | `TEST_TELEPORT_UNAVAILABLE` | ERROR | IBIT | test.teleport | - | Load the core and name a region whose world is loaded. |
| 3203 | `TEST_TELEPORT_REJECTED` | ERROR | IBIT | test.teleport | - | Resolve the rejection reason (reload, cooldown, economy, permission) and rerun. |
| 3204 | `TEST_TELEPORT_SET_LOCATION_NOT_REACHED` | ERROR | IBIT | test.teleport | - | Check that the pipeline's teleport stage reaches the player. |
| 3205 | `TEST_TELEPORT_PROCESSING_LEAK` | ERROR | IBIT | test.teleport | - | Clear processingPlayers on every pipeline exit path. |
| 3206 | `TEST_TELEPORT_TRACKER_LEAK` | ERROR | IBIT | test.teleport | - | Untrack TeleportData on every pipeline exit path (REQ-RTP-S-002). |
| 3207 | `TEST_TELEPORT_TIMEOUT` | ERROR | IBIT | test.teleport | - | Investigate the stalled pipeline stage; the API future did not complete. |
| 3301 | `TEST_COMMANDS_MISSING_NAME` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3302 | `TEST_COMMANDS_MISSING_PERMISSION` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3303 | `TEST_COMMANDS_KEY_NAME_MISMATCH` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3304 | `TEST_COMMANDS_NULL_CHILD` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3305 | `TEST_COMMANDS_TRAVERSAL_ERROR` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3306 | `TEST_COMMANDS_NO_TREE` | ERROR | IBIT | test.commands | - | Repair the reported command-tree node or registration key. |
| 3401 | `TEST_SCHEDULER_UNAVAILABLE` | ERROR | IBIT | test.scheduler | - | Verify the platform scheduler can dispatch this execution tier. |
| 3402 | `TEST_SCHEDULER_ASYNC` | ERROR | IBIT | test.scheduler | - | Verify the platform scheduler can dispatch this execution tier. |
| 3403 | `TEST_SCHEDULER_PRIMARY` | ERROR | IBIT | test.scheduler | - | Verify the platform scheduler can dispatch this execution tier. |
| 3404 | `TEST_SCHEDULER_REGION` | ERROR | IBIT | test.scheduler | - | Verify the platform scheduler can dispatch this execution tier. |
| 3501 | `TEST_EVENTS_COMMAND_SUCCESS` | ERROR | IBIT | test.events | - | Verify the corresponding RTP event listener is registered and dispatched. |
| 3502 | `TEST_EVENTS_COMMAND_FAIL` | ERROR | IBIT | test.events | - | Verify the corresponding RTP event listener is registered and dispatched. |
| 3503 | `TEST_EVENTS_PREFAB` | ERROR | IBIT | test.events | - | Verify the corresponding RTP event listener is registered and dispatched. |
| 3504 | `TEST_EVENTS_PLAYER_MOVE` | ERROR | IBIT | test.events | - | Verify the corresponding RTP event listener is registered and dispatched. |
| 3505 | `TEST_EVENTS_THREW` | ERROR | IBIT | test.events | - | Verify the corresponding RTP event listener is registered and dispatched. |
| 3601 | `TEST_ECONOMY_ISOLATION_UNAVAILABLE` | ERROR | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3602 | `TEST_ECONOMY_ISOLATION_TIMEOUT` | ERROR | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3603 | `TEST_ECONOMY_ISOLATION_CALLER_THREAD` | ERROR | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3604 | `TEST_ECONOMY_ISOLATION_REGION_THREAD` | ERROR | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3605 | `TEST_ECONOMY_ISOLATION_FAILED` | ERROR | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3606 | `TEST_ECONOMY_ISOLATION_NOT_APPLICABLE` | INFO | IBIT | test.economy-isolation | - | Check the economy probe's platform scheduler availability and execution context. |
| 3701 | `TEST_DISCONNECT_JOB_TRY_FINALLY` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3702 | `TEST_DISCONNECT_JOB_RESERVATION` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3703 | `TEST_DISCONNECT_JOB_RESIDUAL_TICKETS` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3704 | `TEST_DISCONNECT_JOB_PROCESSING` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3705 | `TEST_DISCONNECT_JOB_TELEPORT_DATA` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3706 | `TEST_DISCONNECT_JOB_NEXT_TASK` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3707 | `TEST_DISCONNECT_JOB_THREW` | ERROR | IBIT | test.disconnect-job | - | Verify the disconnect cleanup releases this state exactly once. |
| 3801 | `TEST_NETWORK_NOT_CONFIGURED` | INFO | IBIT | test.network | - | Check network transport availability and the selected synthetic probe. |
| 3802 | `TEST_NETWORK_HEARTBEAT` | ERROR | IBIT | test.network | - | Check network transport availability and the selected synthetic probe. |
| 3803 | `TEST_NETWORK_TOKENS` | ERROR | IBIT | test.network | - | Check network transport availability and the selected synthetic probe. |
| 3804 | `TEST_NETWORK_UNAVAILABLE` | ERROR | IBIT | test.network | - | Check network transport availability and the selected synthetic probe. |
| 3901 | `TEST_SAFETY_VERIFIER_PRECONDITION` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 3902 | `TEST_SAFETY_VERIFIER_UNSAFE_CACHE` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 3903 | `TEST_SAFETY_VERIFIER_FUTURE_RETURNED` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 3904 | `TEST_SAFETY_VERIFIER_FUTURE_ASYNC` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 3905 | `TEST_SAFETY_VERIFIER_DISPATCH` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 3906 | `TEST_SAFETY_VERIFIER_TIMEOUT` | ERROR | IBIT | test.safety-verifier | - | Verify the teleport safety probe's preconditions, async verifier and strict timeout. |
| 4001 | `TEST_API_COMPAT_MISSING_METHOD` | ERROR | IBIT | test.api-compat | - | Check the platform method signature required by the runtime adapter. |
| 4002 | `TEST_API_COMPAT_RESOLUTION_ERROR` | ERROR | IBIT | test.api-compat | - | Resolve the reported class-loading or reflection error for this platform. |
| 4003 | `TEST_API_COMPAT_OPTIONAL_METHOD_MISSING` | WARNING | IBIT | test.api-compat | - | Pre-generate destination worlds or use Paper/Folia for asynchronous chunk loading. |
