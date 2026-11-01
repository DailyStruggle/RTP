#!/usr/bin/env bash
# Multi-server devstack acceptance test for LeafSleep across Velocity (proxy-a),
# Paper 1.21.11 (backend-a), Folia 1.21.11 (backend-b), and Redis 7 (redis).
#
# Tests both REDIS_PUBSUB and Velocity PLUGIN_MESSAGING cross-shard sleep
# coordination using two live Mineflayer bots (SleepBotA & SleepBotB).

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$DEVSTACK_DIR")"
COMPOSE_FILE="$DEVSTACK_DIR/docker-compose.leafsleep.yml"
STACK_DIR="$DEVSTACK_DIR/leafsleep-stack"

SKIP_BUILD=0
KEEP=0
DOWN=0
SCALE_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    --keep|--keep-running|--no-teardown) KEEP=1 ;;
    --down) DOWN=1 ;;
    --scale-only) SCALE_ONLY=1 ;;
  esac
done

if [ "$DOWN" -eq 1 ]; then
  cd "$DEVSTACK_DIR"
  docker compose -f "$COMPOSE_FILE" down >/dev/null 2>&1 || true
  rm -rf "$STACK_DIR" 2>/dev/null || true
  exit 0
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "[leafsleep-devstack] Building LeafSleep-1.0.0.jar..."
  (cd "$REPO_ROOT" && ./gradlew :resource:LeafSleep:leafsleep-plugin:assemble --console=plain)
fi

LEAFSLEEP_JAR="$REPO_ROOT/resource/LeafSleep/leafsleep-plugin/build/libs/LeafSleep-1.0.0.jar"
if [ ! -f "$LEAFSLEEP_JAR" ]; then
  echo "[leafsleep-devstack] ERROR: $LEAFSLEEP_JAR not found." >&2
  exit 1
fi

write_shard_config() {
  local dir="$1"
  local shardId="$2"
  local transport="$3"
  local bindHost="redis"
  local bindPort=6379
  if [ "$transport" = "direct socket" ]; then
    bindHost="0.0.0.0"
    bindPort=25621
  fi
  mkdir -p "$dir"
  cat > "$dir/config.yml" <<EOF
version: "1.0"
serverShardId: "$shardId"
defaultQuorumPolicy: "curve"
defaultQuorumValue: 25
defaultCancelPolicy: "tug of war"
sleepCurve:
  type: "logarithmic"
  minRequired: 1
  maxRequired: 8
  parameter: 10.0
  midpoint: 0.35
cancelCurve:
  type: "logarithmic"
  minRequired: 1
  maxRequired: 5
  parameter: 10.0
  midpoint: 0.35
warmupDelayTicks: 40
sleepCooldownSeconds: 2
cancelCooldownSeconds: 2
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
budgetMaxFramesPerTick: 32
sharding:
  enabled: true
  transport: "$transport"
  channel: "leafsleep:sync"
  bindHost: "$bindHost"
  bindPort: $bindPort
  peers:
    - "backend-a:25621"
    - "backend-b:25621"
    - "backend-c:25621"
    - "backend-d:25621"
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
EOF
}

rm -rf "$STACK_DIR" 2>/dev/null || podman unshare rm -rf "$STACK_DIR" 2>/dev/null || true
mkdir -p "$STACK_DIR/shared" "$STACK_DIR/proxy/plugins" \
         "$STACK_DIR/backend-a/plugins" "$STACK_DIR/backend-b/plugins" \
         "$STACK_DIR/backend-c/plugins" "$STACK_DIR/backend-d/plugins"

if [ -s "$DEVSTACK_DIR/shared/forwarding.secret" ]; then
  cp -f "$DEVSTACK_DIR/shared/forwarding.secret" "$STACK_DIR/shared/forwarding.secret"
else
  head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=' > "$STACK_DIR/shared/forwarding.secret"
fi

python3 - <<'PY' > "$STACK_DIR/shared/ops.json"
import hashlib, json, uuid

def offline_uuid(name: str) -> str:
    md5 = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8")).digest())
    md5[6] = (md5[6] & 0x0F) | 0x30
    md5[8] = (md5[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(md5)))

ops = [
    {"uuid": offline_uuid(n), "name": n, "level": 4, "bypassesPlayerLimit": True}
    for n in (
        "SleepBotA", "SleepBotB", "SleepBotC", "SleepBotD",
        "SleepBotA1", "SleepBotB1", "SleepBotC1", "SleepBotD1"
    )
]
print(json.dumps(ops, indent=2))
PY

cat > "$STACK_DIR/proxy/velocity.toml" <<'EOF'
config-version = "2.7"
bind = "0.0.0.0:25577"
motd = "LeafSleep Devstack Proxy"
show-max-players = 80
online-mode = false
force-key-authentication = false
prevent-client-proxy-connections = false
player-info-forwarding-mode = "MODERN"
forwarding-secret-file = "forwarding.secret"
announce-forge = false
kick-existing-players = false
enable-player-address-logging = true

[servers]
backend-a = "backend-a:25565"
backend-b = "backend-b:25565"
backend-c = "backend-c:25565"
backend-d = "backend-d:25565"
try = ["backend-a", "backend-b", "backend-c", "backend-d"]

[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 0
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false

[query]
enabled = false
port = 25577
map = "Velocity"
show-plugins = false
EOF

cp -f "$LEAFSLEEP_JAR" "$STACK_DIR/proxy/plugins/LeafSleep-1.0.0.jar"
for shard in backend-a backend-b backend-c backend-d; do
  cp -f "$LEAFSLEEP_JAR" "$STACK_DIR/$shard/plugins/LeafSleep-1.0.0.jar"
  write_shard_config "$STACK_DIR/$shard/plugins/LeafSleep" "$shard" "redis pubsub"
done

# Ensure external cache volume exists
docker volume create devstack_mc-image-cache >/dev/null 2>&1 || true

cleanup_on_exit() {
  if [ "$KEEP" -eq 0 ]; then
    echo "[leafsleep-devstack] Stopping devstack containers..."
    (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" down >/dev/null 2>&1 || true)
    rm -rf "$STACK_DIR" 2>/dev/null || podman unshare rm -rf "$STACK_DIR" 2>/dev/null || true
  fi
}
trap cleanup_on_exit EXIT

FWD_SECRET="$(tr -d '[:space:]' < "$STACK_DIR/shared/forwarding.secret")"
export CFG_VELOCITY_FORWARDING_SECRET="$FWD_SECRET"

echo "[leafsleep-devstack] Starting redis, proxy-a (Velocity), backend-a (Paper), backend-b (Folia), backend-c (Paper), and backend-d (Folia)..."
(cd "$DEVSTACK_DIR" && CFG_VELOCITY_FORWARDING_SECRET="$FWD_SECRET" docker compose -f "$COMPOSE_FILE" up -d)

echo "[leafsleep-devstack] Waiting for all 4 backend servers (backend-a, backend-b, backend-c, backend-d) to finish booting..."
TIMEOUT=240
ELAPSED=0
READY_A=0
READY_B=0
READY_C=0
READY_D=0
while [ "$ELAPSED" -lt "$TIMEOUT" ]; do
  sleep 4
  ELAPSED=$((ELAPSED + 4))
  if [ "$READY_A" -eq 0 ] && (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=80 backend-a 2>&1 | grep -q 'LeafSleep (Paper/Folia) enabled'); then
    echo "[leafsleep-devstack] backend-a (Paper) enabled LeafSleep (${ELAPSED}s)."
    READY_A=1
  fi
  if [ "$READY_B" -eq 0 ] && (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=80 backend-b 2>&1 | grep -q 'LeafSleep (Paper/Folia) enabled'); then
    echo "[leafsleep-devstack] backend-b (Folia) enabled LeafSleep (${ELAPSED}s)."
    READY_B=1
  fi
  if [ "$READY_C" -eq 0 ] && (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=80 backend-c 2>&1 | grep -q 'LeafSleep (Paper/Folia) enabled'); then
    echo "[leafsleep-devstack] backend-c (Paper) enabled LeafSleep (${ELAPSED}s)."
    READY_C=1
  fi
  if [ "$READY_D" -eq 0 ] && (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=80 backend-d 2>&1 | grep -q 'LeafSleep (Paper/Folia) enabled'); then
    echo "[leafsleep-devstack] backend-d (Folia) enabled LeafSleep (${ELAPSED}s)."
    READY_D=1
  fi
  if [ "$READY_A" -eq 1 ] && [ "$READY_B" -eq 1 ] && [ "$READY_C" -eq 1 ] && [ "$READY_D" -eq 1 ]; then
    break
  fi
done

if [ "$READY_A" -ne 1 ] || [ "$READY_B" -ne 1 ] || [ "$READY_C" -ne 1 ] || [ "$READY_D" -ne 1 ]; then
  echo "[leafsleep-devstack] ERROR: Servers did not finish booting within ${TIMEOUT}s (READY_A=$READY_A, READY_B=$READY_B, READY_C=$READY_C, READY_D=$READY_D)" >&2
  (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=60 backend-a backend-b backend-c backend-d proxy-a)
  exit 1
fi

# Wait until Done (...)! appears on all 4 backends so they accept player logins
ELAPSED_DONE=0
while [ "$ELAPSED_DONE" -lt 120 ]; do
  if (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=60 backend-a 2>&1 | grep -q 'Done (') && \
     (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=60 backend-b 2>&1 | grep -q 'Done (') && \
     (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=60 backend-c 2>&1 | grep -q 'Done (') && \
     (cd "$DEVSTACK_DIR" && docker compose -f "$COMPOSE_FILE" logs --tail=60 backend-d 2>&1 | grep -q 'Done ('); then
    break
  fi
  sleep 2
  ELAPSED_DONE=$((ELAPSED_DONE + 2))
done

SHARDS_CSV="backend-a,backend-b,backend-c,backend-d"

if [ "$SCALE_ONLY" -eq 0 ]; then
  echo "[leafsleep-devstack] Running 4-server Mineflayer multi-bot acceptance + full feature audit over REDIS_PUBSUB..."
  node "$DEVSTACK_DIR/clients/leafsleep-bot.js" --host 127.0.0.1 --port 25577 --servers "$SHARDS_CSV" --expect-transport REDIS_PUBSUB --full-feature-audit --timeout 180

  echo "[leafsleep-devstack] Switching all 4 shards to Velocity PLUGIN_MESSAGING transport and testing hot-reload..."
  for shard in backend-a backend-b backend-c backend-d; do
    write_shard_config "$STACK_DIR/$shard/plugins/LeafSleep" "$shard" "plugin messaging"
  done
  node "$DEVSTACK_DIR/clients/leafsleep-bot.js" --host 127.0.0.1 --port 25577 --servers "$SHARDS_CSV" --reload-on-start --expect-transport PLUGIN_MESSAGING

  echo "[leafsleep-devstack] Switching all 4 shards to HMAC-SHA256 UDP DIRECT_SOCKET transport and testing hot-reload..."
  for shard in backend-a backend-b backend-c backend-d; do
    write_shard_config "$STACK_DIR/$shard/plugins/LeafSleep" "$shard" "direct socket"
  done
  node "$DEVSTACK_DIR/clients/leafsleep-bot.js" --host 127.0.0.1 --port 25577 --servers "$SHARDS_CSV" --reload-on-start --expect-transport DIRECT_SOCKET
fi

echo "[leafsleep-devstack] Running 20-bot 4-server curve & MSPT scale suite across 2 Paper + 2 Folia servers..."
node "$DEVSTACK_DIR/clients/leafsleep-scale-bot.js" --host 127.0.0.1 --port 25577 --servers "$SHARDS_CSV" --bots-per-shard 5 --stack-dir "$STACK_DIR"

echo "[leafsleep-devstack] ALL 4-SERVER LEAFSLEEP DEVSTACK ACCEPTANCE & SCALE SUITES PASSED!"
