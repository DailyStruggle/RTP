#!/usr/bin/env bash
# Single-server Paper devstack verification for third-party item compatibility
# (ItemsAdder, Oraxen, Nexo, HeadDatabase, CustomModelData).

set -euo pipefail

DEVSTACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$DEVSTACK_DIR")"
COMPOSE_FILE="$DEVSTACK_DIR/docker-compose.compat.yml"
COMPAT_PLUGINS="$DEVSTACK_DIR/compat/plugins"

SKIP_BUILD=0
DOWN=0

for arg in "$@"; do
    case "$arg" in
        --down|-Down|-down) DOWN=1 ;;
        --skip-build|-SkipBuild|-skip-build) SKIP_BUILD=1 ;;
    esac
done

if [ "$DOWN" -eq 1 ]; then
    echo "Tearing down single-server item compatibility devstack..."
    docker compose -f "$COMPOSE_FILE" down -v
    echo "Teardown complete."
    exit 0
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
    echo "Building mock stub plugins and LeafRTP jars..."
    cd "$REPO_ROOT"
    ./gradlew ':helpers:MockItemPlugins:buildAllStubs' --console=plain
    ./gradlew ':rtp-plugin:remapJar' --console=plain
    ./gradlew ':addons:LeafRTPGuiAddon:rtp-gui:shadowJar' --console=plain
fi

mkdir -p "$COMPAT_PLUGINS"
rm -f "$COMPAT_PLUGINS"/*.jar

MOCK_LIBS="$REPO_ROOT/helpers/MockItemPlugins/build/libs"
for stub in MockItemsAdder.jar MockOraxen.jar MockNexo.jar MockHeadDatabase.jar; do
    if [ -f "$MOCK_LIBS/$stub" ]; then
        cp -f "$MOCK_LIBS/$stub" "$COMPAT_PLUGINS/$stub"
        echo "Staged stub: $stub"
    fi
done

RTP_JAR="$(ls -t "$REPO_ROOT/rtp-plugin/build/libs"/LeafRTP*.jar 2>/dev/null | head -n 1 || true)"
if [ -n "$RTP_JAR" ]; then
    cp -f "$RTP_JAR" "$COMPAT_PLUGINS/LeafRTP.jar"
    echo "Staged LeafRTP: $(basename "$RTP_JAR")"
fi

GUI_JAR="$(ls -t "$REPO_ROOT/addons/LeafRTPGuiAddon/rtp-gui/build/libs"/LeafRTPGuiAddon*.jar 2>/dev/null | head -n 1 || true)"
if [ -n "$GUI_JAR" ]; then
    cp -f "$GUI_JAR" "$COMPAT_PLUGINS/LeafRTPGuiAddon.jar"
    echo "Staged LeafRTPGuiAddon: $(basename "$GUI_JAR")"
fi

if ! docker ps >/dev/null 2>&1; then
    echo ""
    echo "=========================================================================="
    echo "Docker daemon is not running on this host."
    echo "All compatibility stub JARs and configs have been staged into devstack/compat/plugins/."
    echo "Run when Docker is active:"
    echo "  cd devstack && docker compose -f docker-compose.compat.yml up -d"
    echo "=========================================================================="
    exit 0
fi

echo "Launching single-server Paper devstack..."
docker compose -f "$COMPOSE_FILE" up -d

echo "Waiting for Paper server to boot..."
TIMEOUT=180
ELAPSED=0
BOOTED=0

while [ "$ELAPSED" -lt "$TIMEOUT" ]; do
    sleep 5
    ELAPSED=$((ELAPSED + 5))
    if docker compose -f "$COMPOSE_FILE" logs backend-compat 2>&1 | grep -q 'Done (.*s)! For help, type "help"'; then
        BOOTED=1
        break
    fi
done

if [ "$BOOTED" -ne 1 ]; then
    echo "Server failed to boot within $TIMEOUT seconds."
    exit 1
fi

echo "Server successfully booted!"
for plugin in MockItemsAdder MockOraxen MockNexo MockHeadDatabase LeafRTP; do
    if docker compose -f "$COMPOSE_FILE" logs backend-compat 2>&1 | grep -q "$plugin"; then
        echo "  [OK] Found plugin: $plugin"
    else
        echo "  [WARNING] Plugin $plugin not found in logs"
    fi
done

echo "Single-server devstack verification passed successfully."
