#!/usr/bin/env bash
#
# Eureka Virtual Threads Benchmark Runner
#
# Builds the project and runs JMH benchmarks comparing platform threads
# vs virtual threads across multiple dimensions.
#
# Usage:
#   ./scripts/run-benchmarks.sh [--quick]
#
# Options:
#   --quick   Run with fewer iterations for a fast sanity check
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RESULTS_DIR="${PROJECT_ROOT}/benchmark-results"

mkdir -p "${RESULTS_DIR}"

QUICK_MODE=false
if [[ "${1:-}" == "--quick" ]]; then
    QUICK_MODE=true
fi

echo "========================================"
echo " Eureka Virtual Threads Benchmark Suite"
echo "========================================"
echo ""
echo "Project root: ${PROJECT_ROOT}"
echo "Results dir:  ${RESULTS_DIR}"
echo "Quick mode:   ${QUICK_MODE}"
echo ""

# Step 1: Build the project
echo "[1/4] Building project..."
cd "${PROJECT_ROOT}"
./gradlew :eureka-core:compileTestJava --no-daemon -q

# Step 2: Common JVM flags
JVM_FLAGS=(
    "-Djdk.tracePinnedThreads=full"
    "--add-opens" "java.base/java.lang=ALL-UNNAMED"
    "--add-opens" "java.base/java.lang.reflect=ALL-UNNAMED"
    "--add-opens" "java.base/java.util=ALL-UNNAMED"
    "--add-opens" "java.base/java.util.concurrent=ALL-UNNAMED"
)

if [[ "${QUICK_MODE}" == "true" ]]; then
    JMH_OPTS="-wi 1 -i 1 -f 1 -t 1"
else
    JMH_OPTS="-wi 2 -i 3 -f 1"
fi

# Step 3: Run benchmarks with virtual threads enabled
echo ""
echo "[2/4] Running benchmarks with VIRTUAL THREADS enabled..."
./gradlew :eureka-core:jmh \
    --no-daemon \
    -Djmh.args="${JMH_OPTS} -Deureka.virtualThreads.enabled=true" \
    2>&1 | tee "${RESULTS_DIR}/virtual-threads-output.log" || true

# Copy results
if [[ -f "${PROJECT_ROOT}/eureka-core/benchmark-results/results.json" ]]; then
    cp "${PROJECT_ROOT}/eureka-core/benchmark-results/results.json" \
       "${RESULTS_DIR}/virtual-threads-results.json"
fi

# Step 4: Run benchmarks with platform threads (baseline)
echo ""
echo "[3/4] Running benchmarks with PLATFORM THREADS (baseline)..."
./gradlew :eureka-core:jmh \
    --no-daemon \
    -Djmh.args="${JMH_OPTS} -Deureka.virtualThreads.enabled=false" \
    2>&1 | tee "${RESULTS_DIR}/platform-threads-output.log" || true

# Copy results
if [[ -f "${PROJECT_ROOT}/eureka-core/benchmark-results/results.json" ]]; then
    cp "${PROJECT_ROOT}/eureka-core/benchmark-results/results.json" \
       "${RESULTS_DIR}/platform-threads-results.json"
fi

echo ""
echo "[4/4] Benchmark run complete!"
echo ""
echo "Results saved to: ${RESULTS_DIR}/"
echo ""
echo "Files:"
ls -la "${RESULTS_DIR}/" 2>/dev/null || echo "  (no results files generated)"
echo ""
echo "To view results, check the JSON files in ${RESULTS_DIR}/"
echo "For pinning analysis, grep for 'VirtualThreadPinned' in the log files."
