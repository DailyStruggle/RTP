#!/usr/bin/env bash
# Relaunch loop for a benchmark server that has no /restart (Folia).
# bench_matrix.py sends `stop` over RCON; this loop starts the JVM again.
# Run it once on the server host, e.g. in tmux:
#   tmux new -s folia 'cd ~/threadripper-dir/folia/26.2 && ./start-loop.sh'
# Detach with Ctrl-b d, reattach with `tmux attach -t folia`.
# To really stop: Ctrl-C (or `tmux kill-session -t folia`), or `touch .no-relaunch`
# and then send `stop`.
cd "$(dirname "$0")" || exit 1
mkdir -p logs

# GC and safepoint pauses go to logs/gc-<start time>.log, one file per JVM start (one per bench_matrix
# round), to compare multi-second stalls against pause times. Read with: grep -E 'Pause|Total time' logs/gc-*.log

# Pin the JVM to the cores the bot swarm (run-swarm.sh, default 40-63) does not use. FOLIA_CPUS=all disables it.
# The JVM sizes its GC and compiler thread pools and availableProcessors() from the affinity mask.
FOLIA_CPUS="${FOLIA_CPUS:-0-39}"
PIN=()
if [ "$FOLIA_CPUS" != "all" ] && command -v taskset >/dev/null 2>&1; then
  if taskset -c "$FOLIA_CPUS" true 2>/dev/null; then
    PIN=(taskset -c "$FOLIA_CPUS")
  else
    echo "[start-loop] cpu list '$FOLIA_CPUS' is not valid on this host ($(nproc) CPUs); not pinning"
  fi
fi

# Optional real-time scheduling: FOLIA_RT=<priority> (1-99) runs the JVM under SCHED_FIFO at that priority;
# FOLIA_RT_POLICY=rr selects SCHED_RR. Needs CAP_SYS_NICE for the server user, e.g. an rtprio limit
# (/etc/security/limits.conf: "<user> - rtprio 99"). tmux keeps the limits of the shell that started the tmux
# server, so log in again and `tmux kill-server` first. Not run through sudo: the JVM would then run as root
# and write root-owned files. Unset = normal scheduling; a refused chrt is reported and the JVM starts normally.
# Keep the priority low (10-20): kernel threads run above that.
RT=()
if [ -n "${FOLIA_RT:-}" ] && command -v chrt >/dev/null 2>&1; then
  RT_FLAG="-f"; [ "${FOLIA_RT_POLICY:-fifo}" = "rr" ] && RT_FLAG="-r"
  if chrt "$RT_FLAG" "$FOLIA_RT" true 2>/dev/null; then
    RT=(chrt "$RT_FLAG" "$FOLIA_RT")
  else
    echo "[start-loop] chrt $RT_FLAG $FOLIA_RT refused (no CAP_SYS_NICE / rtprio limit); running with normal scheduling"
  fi
elif [ -n "${FOLIA_RT:-}" ]; then
  echo "[start-loop] FOLIA_RT set but chrt is not installed; running with normal scheduling"
fi

# GC selection: FOLIA_GC=g1 (default) or zgc. zgc is generational ZGC (JDK 25: -XX:+UseZGC), which avoids the
# multi-hundred-ms G1 Full/young pauses seen at 192 players; it needs spare heap headroom and some extra CPU
# for concurrent threads. Compare logs/gc-*.log between runs.
case "${FOLIA_GC:-g1}" in
  zgc)
    GC_FLAGS=(-XX:+UseZGC -XX:+ParallelRefProcEnabled)
    ;;
  *)
    GC_FLAGS=(
      -XX:+UseG1GC
      -XX:+ParallelRefProcEnabled
      -XX:MaxGCPauseMillis=200
      -XX:+UnlockExperimentalVMOptions
      -XX:G1NewSizePercent=40
      -XX:G1MaxNewSizePercent=50
      -XX:G1HeapRegionSize=16M
      -XX:G1ReservePercent=15
      -XX:G1HeapWastePercent=5
      -XX:G1MixedGCCountTarget=4
      -XX:InitiatingHeapOccupancyPercent=20
      -XX:G1MixedGCLiveThresholdPercent=90
      -XX:G1RSetUpdatingPauseTimePercent=5
      -XX:SurvivorRatio=32
      -XX:MaxTenuringThreshold=1
    )
    ;;
esac

JAVA_BIN="java"
# JAVA_BIN="/usr/lib/jvm/jdk-25.0.2/bin/java"

trap 'echo "[start-loop] interrupted; not relaunching"; exit 130' INT TERM

while true; do
  rm -f .no-relaunch
  "${PIN[@]}" "${RT[@]}" "$JAVA_BIN" -Xms32G -Xmx32G \
    "${GC_FLAGS[@]}" \
    -XX:+DisableExplicitGC \
    -XX:+AlwaysPreTouch \
    -XX:+PerfDisableSharedMem \
    -XX:NativeMemoryTracking=summary \
    -Xlog:gc*,safepoint:file=logs/gc-%t.log:time,uptime,level,tags:filecount=0 \
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
