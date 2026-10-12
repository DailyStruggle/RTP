#!/usr/bin/env bash
# Relaunch loop for a benchmark server that has no /restart (Folia).
# bench_matrix.py sends `stop` over RCON; this loop starts the JVM again.
# Run it once on the server host, e.g. in tmux:
#   tmux new -s folia 'cd ~/threadripper-dir/folia/26.2 && ./start-loop.sh'
# Detach with Ctrl-b d, reattach with `tmux attach -t folia`.
# To really stop: Ctrl-C (or `tmux kill-session -t folia`), or `touch .no-relaunch`
# and then send `stop`.
cd "$(dirname "$0")" || exit 1

JAVA_BIN="java"
# JAVA_BIN="/usr/lib/jvm/jdk-25.0.2/bin/java"

trap 'echo "[start-loop] interrupted; not relaunching"; exit 130' INT TERM

while true; do
  rm -f .no-relaunch
  "$JAVA_BIN" -Xms16G -Xmx16G \
    -XX:+UseG1GC \
    -XX:+ParallelRefProcEnabled \
    -XX:MaxGCPauseMillis=200 \
    -XX:+UnlockExperimentalVMOptions \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:G1NewSizePercent=40 \
    -XX:G1MaxNewSizePercent=50 \
    -XX:G1HeapRegionSize=16M \
    -XX:G1ReservePercent=15 \
    -XX:G1HeapWastePercent=5 \
    -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=20 \
    -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:G1RSetUpdatingPauseTimePercent=5 \
    -XX:SurvivorRatio=32 \
    -XX:+PerfDisableSharedMem \
    -XX:NativeMemoryTracking=summary \
    -XX:MaxTenuringThreshold=1 \
    -jar folia-26.2.jar nogui
  code=$?
  echo "[start-loop] server exited with code $code at $(date -Is)"
  if [ -e .no-relaunch ]; then
    echo "[start-loop] .no-relaunch present; leaving"
    rm -f .no-relaunch
    break
  fi
  sleep 5
done
