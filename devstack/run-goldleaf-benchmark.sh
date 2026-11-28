#!/usr/bin/env bash
# GoldLeaf Multi-Fork Benchmark Harness & Automated Vanilla-Parity Gate (goldleaf-ADR-019)
#
# Usage:
#   ./devstack/run-goldleaf-benchmark.sh --suite parity
#   ./devstack/run-goldleaf-benchmark.sh --suite matrix [--goldleaf-jar <jar>] [--folia-jar <jar>] [--paper-jar <jar>] [--leafmc-jar <jar>]
#   ./devstack/run-goldleaf-benchmark.sh --suite e2e [--mc-version 26.3] [--seed <int>]
#   ./devstack/run-goldleaf-benchmark.sh --suite all
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
GOLDLEAF_DIR="${GOLDLEAF_DIR:-${REPO_ROOT}/GoldLeaf}"
MC_VERSION="${GOLDLEAF_BENCH_MC_VERSION:-26.3}"
CACHE_DIR="${GOLDLEAF_BENCH_CACHE_DIR:-/home/leaf26/minecraft/${MC_VERSION}-goldleaf-bench/server-cache}"
SUITE="parity"
OUT_DIR="${SCRIPT_DIR}/logs/goldleaf-bench-${MC_VERSION}-$(date -u +%Y%m%dT%H%M%SZ)"
SEED="${GOLDLEAF_BENCH_SEED:-42424242}"
HEAP_GB="${GOLDLEAF_BENCH_HEAP_GB:-4}"
ALLOW_PARTIAL=0
PARITY_WORLD_A=""
PARITY_WORLD_B=""

GOLDLEAF_JAR="${GOLDLEAF_JAR:-${GOLDLEAF_DIR}/folia-server/build/libs/folia-bundler-${MC_VERSION}.local-SNAPSHOT.jar}"
FOLIA_JAR="${FOLIA_JAR:-${CACHE_DIR}/folia-${MC_VERSION}.jar}"
PAPER_JAR="${PAPER_JAR:-${CACHE_DIR}/paper-${MC_VERSION}.jar}"
LEAFMC_JAR="${LEAFMC_JAR:-${CACHE_DIR}/leaf-${MC_VERSION}.jar}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suite)
      SUITE="$2"
      shift 2
      ;;
    --mc-version)
      MC_VERSION="$2"
      CACHE_DIR="${GOLDLEAF_BENCH_CACHE_DIR:-/home/leaf26/minecraft/${MC_VERSION}-goldleaf-bench/server-cache}"
      GOLDLEAF_JAR="${GOLDLEAF_DIR}/folia-server/build/libs/folia-bundler-${MC_VERSION}.local-SNAPSHOT.jar"
      FOLIA_JAR="${CACHE_DIR}/folia-${MC_VERSION}.jar"
      PAPER_JAR="${CACHE_DIR}/paper-${MC_VERSION}.jar"
      LEAFMC_JAR="${CACHE_DIR}/leaf-${MC_VERSION}.jar"
      shift 2
      ;;
    --out)
      OUT_DIR="$2"
      shift 2
      ;;
    --seed)
      SEED="$2"
      shift 2
      ;;
    --goldleaf-jar)
      GOLDLEAF_JAR="$2"
      shift 2
      ;;
    --folia-jar)
      FOLIA_JAR="$2"
      shift 2
      ;;
    --paper-jar)
      PAPER_JAR="$2"
      shift 2
      ;;
    --leafmc-jar)
      LEAFMC_JAR="$2"
      shift 2
      ;;
    --allow-partial)
      ALLOW_PARTIAL=1
      shift
      ;;
    --verify-parity)
      PARITY_WORLD_A="$2"
      PARITY_WORLD_B="$3"
      shift 3
      ;;
    -h|--help)
      echo "Usage: $0 --suite <parity|matrix|e2e|all> [--mc-version 26.3] [--out <dir>] [--seed <int>] [--goldleaf-jar <jar>] [--folia-jar <jar>] [--paper-jar <jar>] [--leafmc-jar <jar>] [--allow-partial] [--verify-parity <worldA> <worldB>]"
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

mkdir -p "${OUT_DIR}"
SUMMARY_JSON="${OUT_DIR}/summary.json"
PARITY_STATUS="skipped"
MATRIX_STATUS="skipped"
FRESHNESS_VERIFIED="true"

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

mtime_epoch() {
  stat -c %Y "$1"
}

run_suite_b_parity() {
  echo "[ADR-019 Suite B] Running GoldLeaf automated vanilla-parity & regression gate..."
  local start_s
  start_s="$(date +%s)"

  (
    cd "${GOLDLEAF_DIR}"
    ./gradlew :folia-server:test --tests "dev.leafcraft.goldleaf.GoldLeafTestSuite" --console=plain -q
  ) | tee "${OUT_DIR}/suite-b-junit.log"

  if [[ -f "${GOLDLEAF_DIR}/scripts/test_goldleaf_launch.py" ]]; then
    echo "[ADR-019 Suite B] Running Leyden AOT launcher unit tests (ADR-016)..."
    python3 -m unittest discover -s "${GOLDLEAF_DIR}/scripts" -p "test_goldleaf_launch.py" -v \
      | tee "${OUT_DIR}/suite-b-launcher.log"
  fi

  if [[ -n "${PARITY_WORLD_A}" && -n "${PARITY_WORLD_B}" ]]; then
    echo "[ADR-019 Suite B] Running world parity verification (${PARITY_WORLD_A} vs ${PARITY_WORLD_B})..."
    python3 "${REPO_ROOT}/scripts/verify_goldleaf_world_parity.py" \
      "${PARITY_WORLD_A}" "${PARITY_WORLD_B}" \
      | tee "${OUT_DIR}/suite-b-world-parity.log"
  fi

  local end_s elapsed_s
  end_s="$(date +%s)"
  elapsed_s=$((end_s - start_s))
  PARITY_STATUS="pass"
  echo "[ADR-019 Suite B] PASS (${elapsed_s}s)"
}

bench_single_fork_boot() {
  local name="$1"
  local jar="$2"
  local work_dir="${OUT_DIR}/fork-${name}"
  mkdir -p "${work_dir}"
  echo "eula=true" > "${work_dir}/eula.txt"
  cat > "${work_dir}/server.properties" <<EOF
level-seed=${SEED}
online-mode=false
server-port=0
spawn-protection=0
view-distance=10
simulation-distance=8
EOF

  echo "[ADR-019 Suite A] Benchmarking ${name} (${jar})..."
  local jar_sha jar_mtime log_mtime t0 t1 elapsed_ms
  jar_sha="$(sha256_file "${jar}")"
  jar_mtime="$(mtime_epoch "${jar}")"
  t0="$(date +%s%3N)"
  if timeout 120 java -Xms"${HEAP_GB}G" -Xmx"${HEAP_GB}G" -XX:+UseG1GC -jar "${jar}" --nogui --initSettings \
      > "${work_dir}/boot.log" 2>&1; then
    t1="$(date +%s%3N)"
    elapsed_ms=$((t1 - t0))
    log_mtime="$(mtime_epoch "${work_dir}/boot.log")"
    if [[ "${log_mtime}" -lt "${jar_mtime}" ]]; then
      FRESHNESS_VERIFIED="false"
      echo "${name},stale_log,${elapsed_ms},${jar_sha},${jar_mtime},${log_mtime}" >> "${OUT_DIR}/matrix.csv"
      echo "[ADR-019 Suite A] ${name}: STALE LOG REJECTED (log_mtime=${log_mtime} < jar_mtime=${jar_mtime})" >&2
      return 1
    fi
    echo "${name},ok,${elapsed_ms},${jar_sha},${jar_mtime},${log_mtime}" >> "${OUT_DIR}/matrix.csv"
    echo "[ADR-019 Suite A] ${name}: initSettings completed in ${elapsed_ms} ms (sha256=${jar_sha:0:12})"
  else
    t1="$(date +%s%3N)"
    elapsed_ms=$((t1 - t0))
    log_mtime="$(mtime_epoch "${work_dir}/boot.log" 2>/dev/null || echo 0)"
    echo "${name},fail,${elapsed_ms},${jar_sha},${jar_mtime},${log_mtime}" >> "${OUT_DIR}/matrix.csv"
    echo "[ADR-019 Suite A] ${name}: FAILED after ${elapsed_ms} ms (see ${work_dir}/boot.log)" >&2
    return 1
  fi
}

run_suite_a_matrix() {
  echo "fork,status,boot_ms,jar_sha256,jar_mtime,log_mtime" > "${OUT_DIR}/matrix.csv"
  local ran=0
  local failed=0
  local missing=0

  for pair in "goldleaf:${GOLDLEAF_JAR}" "folia:${FOLIA_JAR}" "paper:${PAPER_JAR}" "leafmc:${LEAFMC_JAR}"; do
    local name="${pair%%:*}"
    local jar="${pair#*:}"
    if [[ -n "${jar}" && -f "${jar}" ]]; then
      ran=$((ran + 1))
      if ! bench_single_fork_boot "${name}" "${jar}"; then
        failed=$((failed + 1))
      fi
    else
      missing=$((missing + 1))
      echo "[ADR-019 Suite A] Missing fork JAR for ${name}: ${jar}" >&2
    fi
  done

  if [[ "${ran}" -eq 0 ]]; then
    echo "[ADR-019 Suite A] FAIL-CLOSED: Zero fork JARs available for matrix comparison (ADR-019, S-004)." >&2
    MATRIX_STATUS="fail_no_jars"
    return 1
  fi

  if [[ "${missing}" -gt 0 && "${ALLOW_PARTIAL}" -ne 1 ]]; then
    echo "[ADR-019 Suite A] FAIL-CLOSED: ${missing} fork JAR(s) missing; pass --allow-partial to explicitly permit a partial matrix." >&2
    MATRIX_STATUS="fail_missing_forks"
    return 1
  fi

  if [[ "${failed}" -gt 0 ]]; then
    MATRIX_STATUS="fail"
    return 1
  fi
  MATRIX_STATUS="pass"
}

run_suite_e2e() {
  echo "[ADR-019 Suite E2E] Running live EMPTY -> FULL multi-fork benchmark (MC ${MC_VERSION})..."
  python3 "${REPO_ROOT}/scripts/bench_goldleaf_empty_to_full.py" \
    --mc-version "${MC_VERSION}" \
    --cache-dir "${CACHE_DIR}" \
    --goldleaf-jar "${GOLDLEAF_JAR}" \
    --seed "${SEED}" \
    --heap-gb "${HEAP_GB}" \
    --out "${OUT_DIR}/e2e"
}

case "${SUITE}" in
  parity)
    run_suite_b_parity
    ;;
  matrix)
    run_suite_a_matrix
    ;;
  e2e)
    run_suite_e2e
    ;;
  all)
    run_suite_b_parity
    run_suite_a_matrix
    ;;
  *)
    echo "Invalid --suite '${SUITE}' (expected parity, matrix, e2e, or all)" >&2
    exit 2
    ;;
esac

GOLDLEAF_COMMIT="$(git -C "${GOLDLEAF_DIR}" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
JDK_VERSION="$(java -version 2>&1 | head -n 1 || echo unknown)"
CPU_CORES="$(nproc 2>/dev/null || echo 0)"
TIMESTAMP_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
PAPER_PATCH_COUNT="$(find "${GOLDLEAF_DIR}/folia-server/paper-patches/features" -maxdepth 1 -name '*.patch' 2>/dev/null | wc -l | tr -d ' ')"
MC_PATCH_COUNT="$(find "${GOLDLEAF_DIR}/folia-server/minecraft-patches/features" -maxdepth 1 -name '*.patch' 2>/dev/null | wc -l | tr -d ' ')"
TOTAL_PATCH_LINES="$(cat "${GOLDLEAF_DIR}"/folia-server/paper-patches/features/*.patch "${GOLDLEAF_DIR}"/folia-server/minecraft-patches/features/*.patch 2>/dev/null | wc -l | tr -d ' ')"

python3 - "${SUMMARY_JSON}" "${OUT_DIR}/matrix.csv" <<PY
import csv, json, os, sys
summary_path, csv_path = sys.argv[1], sys.argv[2]
forks = {}
if os.path.isfile(csv_path):
    with open(csv_path, newline="", encoding="utf-8") as fh:
        for row in csv.DictReader(fh):
            forks[row["fork"]] = {
                "status": row["status"],
                "bootMs": int(row["boot_ms"]) if row["boot_ms"].isdigit() else None,
                "jarSha256": row.get("jar_sha256", ""),
                "jarMtimeEpoch": int(row["jar_mtime"]) if row.get("jar_mtime", "").isdigit() else None,
                "logMtimeEpoch": int(row["log_mtime"]) if row.get("log_mtime", "").isdigit() else None,
            }
payload = {
    "adr": "goldleaf-ADR-019",
    "suite": os.environ.get("SUITE", "${SUITE}"),
    "mcVersion": "${MC_VERSION}",
    "seed": "${SEED}",
    "heapGb": int("${HEAP_GB}"),
    "cpuCores": int("${CPU_CORES}"),
    "gitCommit": "${GOLDLEAF_COMMIT}",
    "jdkVersion": "${JDK_VERSION}",
    "timestampUtc": "${TIMESTAMP_UTC}",
    "partialMatrix": bool(int("${ALLOW_PARTIAL}")),
    "freshnessVerified": "${FRESHNESS_VERIFIED}" == "true",
    "parityStatus": "${PARITY_STATUS}",
    "matrixStatus": "${MATRIX_STATUS}",
    "patchCensus": {
        "paperPatches": int("${PAPER_PATCH_COUNT}"),
        "minecraftPatches": int("${MC_PATCH_COUNT}"),
        "totalPatchLines": int("${TOTAL_PATCH_LINES}"),
    },
    "forks": forks,
    "outputDir": "${OUT_DIR}",
}
with open(summary_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2)
    fh.write("\n")
PY

echo "[ADR-019] Summary written to ${SUMMARY_JSON}"
