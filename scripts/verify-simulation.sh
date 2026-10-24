#!/usr/bin/env bash
#
# 一键仿真验证脚本（PR5/PR6 / S6，资源运行时全量）。
#
# 从仓库根目录运行。任一步失败立即以非零退出，不输出 PASS，不吞结果。
#
# 步骤：
#   01 仓库根自检          02 JDK 21 自检            03 定向清理四个目录
#   04 源文件清单          05 编译 main              06 编译 test + major 65
#   07 RegistryChecks=279  08 ConcurrencyChecks=8    09 ReliabilityR1=14
#   10 30 个旧 case diff   11 Parser=16              12 Runtime=18
#   13 ResourceWorld=24    14 ResourceStorage=6      15 ResourceParser=7
#   16 ResourceScenario=10 17 ReplayEvidence=12      18 validate 合法场景×3
#   19 validate 非法场景×2 20 run 非法场景×1          21 run SIM-001
#   22 run SIM-002         23 run SIM-003            24 二次 run SIM-002
#   25 compare → MATCH     26 validate 资源场景×6    27 资源场景双跑×6 → 6 对 MATCH
#   28 资源非法场景×5（退出 1 + 稳定 code + 首错停）  29 22 个 manifest 校验
#   30 证据包 7 文件形状   31 21 字段顺序            32 Evidence 安全扫描
#   33 README/docs 安全扫描                          34 工作区+确定性
#   35 唯一终点
#
# 唯一终点：
#   SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=30 baseValidScenarios=3 baseInvalidScenarios=3 resourceValidScenarios=6 resourceInvalidScenarios=5 resourceReplay=6 realExternalWrites=0

set -eu
set -o pipefail

STEP_NO=0
STEP_TOTAL=35

step() {
    STEP_NO=$((STEP_NO + 1))
    printf '\n[STEP %02d/%d] %s\n' "$STEP_NO" "$STEP_TOTAL" "$1"
}

die() {
    printf 'ERR %s\n' "$1" >&2
    exit 1
}

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAR="lib/sqlite-jdbc-3.46.1.3.jar"
CP="out/main:$JAR"
TCP="out/main:out/test:$JAR"
CP_ABS="$ROOT/out/main:$ROOT/$JAR"

step "仓库根自检"
for required_file in README.md scripts/verify-simulation.sh; do
    [ -f "$required_file" ] || die "SELF_CHECK_MISSING_FILE $required_file"
done
for required_dir in src scenarios; do
    [ -d "$required_dir" ] || die "SELF_CHECK_MISSING_DIR $required_dir"
done
# 源码工作副本有 .git；从交付 ZIP 复验时没有 .git，改查 src/main/java。
if [ ! -d .git ] && [ ! -d src/main/java ]; then
    die "SELF_CHECK_MISSING .git|src/main/java"
fi
echo "ROOT_OK ROOT_KIND=$([ -d .git ] && echo GIT_CHECKOUT || echo SOURCE_PACKAGE)"

step "JDK 21 自检"
JAVA_VERSION="$(java -version 2>&1 | awk -F'"' 'NR == 1 { print $2 }')"
JAVAC_VERSION="$(javac -version 2>&1 | awk '{ print $2 }')"
[ -n "$JAVA_VERSION" ] && [ -n "$JAVAC_VERSION" ] || die "JDK_VERSION_UNREADABLE"
[ "${JAVA_VERSION%%.*}" = "21" ] || die "JDK_NOT_21 java=$JAVA_VERSION"
[ "${JAVAC_VERSION%%.*}" = "21" ] || die "JDK_NOT_21 javac=$JAVAC_VERSION"
[ -f "$JAR" ] || die "JAR_NOT_FOUND $JAR"
echo "JDK_OK java=$JAVA_VERSION javac=$JAVAC_VERSION"

step "定向清理四个目录（不宽泛删除）"
[ "$ROOT" != "/" ] || die "ROOT_IS_SLASH"
rm -rf "$ROOT/out" "$ROOT/build/runs" "$ROOT/build/case-runs" "$ROOT/build/test-temp"
mkdir -p "$ROOT/out/main" "$ROOT/out/test" \
         "$ROOT/build/runs" "$ROOT/build/case-runs" "$ROOT/build/test-temp"
echo "CLEAN_OK out build/runs build/case-runs build/test-temp"

step "生成源文件清单（LC_ALL=C sort）"
find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt
find src/test/java -type f -name '*.java' | LC_ALL=C sort > build/test-sources.txt
MAIN_SRC="$(awk 'END { print NR }' build/main-sources.txt)"
TEST_SRC="$(awk 'END { print NR }' build/test-sources.txt)"
[ "$MAIN_SRC" -gt 0 ] || die "MAIN_SOURCES_EMPTY"
[ "$TEST_SRC" -gt 0 ] || die "TEST_SOURCES_EMPTY"
echo "SOURCES_OK main=$MAIN_SRC test=$TEST_SRC"

step "编译 main → out/main"
if ! javac -cp "$JAR" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt \
        > build/test-temp/javac-main.txt 2>&1; then
    cat build/test-temp/javac-main.txt >&2
    die "COMPILE_MAIN_FAILED"
fi
echo "COMPILE_MAIN_OK"

step "编译 test → out/test，并核对 class major version=65"
if ! javac -cp "out/main:$JAR" --release 21 -encoding UTF-8 -d out/test @build/test-sources.txt \
        > build/test-temp/javac-test.txt 2>&1; then
    cat build/test-temp/javac-test.txt >&2
    die "COMPILE_TEST_FAILED"
fi
MAJOR="$(javap -verbose out/main/lab/ApplicationState.class | awk '/major version/ { print $3 }')"
[ "$MAJOR" = "65" ] || die "CLASS_MAJOR_NOT_65 ${MAJOR:-unknown}"
echo "COMPILE_TEST_OK major_version=$MAJOR"

step "RegistryChecks → CHECKS_PASSED=279"
if ! java -cp "$CP" lab.RegistryChecks > build/test-temp/registry.out.txt 2>&1; then
    cat build/test-temp/registry.out.txt >&2
    die "REGISTRY_CHECKS_EXIT_NONZERO"
fi
grep -qxF 'CHECKS_PASSED=279' build/test-temp/registry.out.txt || die "REGISTRY_CHECKS_LINE"
grep -xF 'CHECKS_PASSED=279' build/test-temp/registry.out.txt

step "ConcurrencyChecks → CONCURRENCY_CHECKS_PASSED=8"
if ! java -cp "$CP" lab.ConcurrencyChecks > build/test-temp/concurrency.out.txt 2>&1; then
    cat build/test-temp/concurrency.out.txt >&2
    die "CONCURRENCY_CHECKS_EXIT_NONZERO"
fi
grep -qxF 'CONCURRENCY_CHECKS_PASSED=8' build/test-temp/concurrency.out.txt \
    || die "CONCURRENCY_CHECKS_LINE"
grep -xF 'CONCURRENCY_CHECKS_PASSED=8' build/test-temp/concurrency.out.txt

step "ReliabilityCloseoutChecks → RELIABILITY_R1_CASES_PASSED=14"
if ! java -cp "$TCP" lab.ReliabilityCloseoutChecks > build/test-temp/reliability.out.txt 2>&1; then
    cat build/test-temp/reliability.out.txt >&2
    die "RELIABILITY_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^R1-REL-[0-9][0-9] PASS$' build/test-temp/reliability.out.txt || true)" -eq 14 ] \
    || die "RELIABILITY_CHECKS_COUNT"
grep -qxF 'RELIABILITY_R1_CASES_PASSED=14' build/test-temp/reliability.out.txt \
    || die "RELIABILITY_CHECKS_LINE"
grep -xF 'RELIABILITY_R1_CASES_PASSED=14' build/test-temp/reliability.out.txt

step "30 个旧 case 独立目录运行并逐字节 diff"
case_mode() {
    case "$1" in
        persistence-1 | persistence-2) echo "file" ;;
        sqlite-basic-1 | sqlite-basic-2) echo "sqlite fake-accept" ;;
        sqlite-reject) echo "sqlite fake-reject" ;;
        migration-basic | migration-persistence) echo "sqlite fake-accept" ;;
        readonly-basic | readonly-write-rejected) echo "readonly" ;;
        fake-accept | fake-reject | fake-timeout-created | fake-timeout-not-created) echo "$1" ;;
        outbox-basic | inbox-basic | inbox-duplicate | inbox-reject) echo "fake-accept" ;;
        workflow-basic | workflow-reject) echo "fake-accept" ;;
        stats-basic | stats-mixed) echo "fake-accept" ;;
        health-basic) echo "fake-accept" ;;
        health-warn) echo "fake-timeout-created" ;;
        outbox-process-1) echo "file fake-timeout-created" ;;
        outbox-process-2) echo "file fake-accept" ;;
        *) echo "" ;;
    esac
}
case_prereq() {
    case "$1" in
        persistence-2) echo "persistence-1" ;;
        sqlite-basic-2 | sqlite-reject) echo "sqlite-basic-1" ;;
        migration-persistence) echo "migration-basic" ;;
        outbox-process-2) echo "outbox-process-1" ;;
        *) echo "" ;;
    esac
}
CASES_TOTAL=0
CASES_PASSED=0
while IFS= read -r name; do
    CASES_TOTAL=$((CASES_TOTAL + 1))
    workdir="build/case-runs/$name"
    mkdir -p "$workdir"
    prereq="$(case_prereq "$name")"
    if [ -n "$prereq" ]; then
        # 有状态 case 的前置步骤在其独立目录内重放，保证该目录自洽。
        # shellcheck disable=SC2086
        if ! ( cd "$workdir" && rm -rf data && \
               java -cp "$CP_ABS" lab.Main $(case_mode "$prereq") \
                   < "$ROOT/cases/$prereq.in" > .prereq.txt 2>&1 ); then
            cat "$workdir/.prereq.txt" >&2 || true
            die "CASE_PREREQ_FAILED $name"
        fi
    fi
    # shellcheck disable=SC2086
    if ! ( cd "$workdir" && java -cp "$CP_ABS" lab.Main $(case_mode "$name") \
               < "$ROOT/cases/$name.in" > "$ROOT/build/case-runs/$name.actual.txt" 2>&1 ); then
        cat "build/case-runs/$name.actual.txt" >&2 || true
        die "CASE_EXIT_NONZERO $name"
    fi
    cmp -s "build/case-runs/$name.actual.txt" "cases/$name.expected.txt" \
        || die "CASE_DIFF_FAILED $name"
    CASES_PASSED=$((CASES_PASSED + 1))
done < <(printf '%s\n' cases/*.in | LC_ALL=C sort | sed 's#^cases/##; s#\.in$##')
[ "$CASES_TOTAL" -eq 30 ] || die "ORIGINAL_CASES_TOTAL $CASES_TOTAL"
[ "$CASES_PASSED" -eq 30 ] || die "ORIGINAL_CASES_PASSED $CASES_PASSED"
echo "ORIGINAL_CASES_OK total=$CASES_TOTAL passed=$CASES_PASSED"

step "ScenarioParserChecks → SCENARIO_PARSER_CASES_PASSED=16"
if ! java -cp "$TCP" lab.simulation.ScenarioParserChecks > build/test-temp/parser.out.txt 2>&1; then
    cat build/test-temp/parser.out.txt >&2
    die "PARSER_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^SCN-[0-9][0-9] PASS$' build/test-temp/parser.out.txt || true)" -eq 16 ] \
    || die "PARSER_CHECKS_COUNT"
grep -qxF 'SCENARIO_PARSER_CASES_PASSED=16' build/test-temp/parser.out.txt \
    || die "PARSER_CHECKS_LINE"
grep -xF 'SCENARIO_PARSER_CASES_PASSED=16' build/test-temp/parser.out.txt

step "SimulationRuntimeChecks → SIMULATION_RUNTIME_CASES_PASSED=18"
if ! java -cp "$TCP" lab.simulation.SimulationRuntimeChecks > build/test-temp/runtime.out.txt 2>&1; then
    cat build/test-temp/runtime.out.txt >&2
    die "RUNTIME_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^RUN-[0-9][0-9] PASS$' build/test-temp/runtime.out.txt || true)" -eq 18 ] \
    || die "RUNTIME_CHECKS_COUNT"
grep -qxF 'SIMULATION_RUNTIME_CASES_PASSED=18' build/test-temp/runtime.out.txt \
    || die "RUNTIME_CHECKS_LINE"
grep -xF 'SIMULATION_RUNTIME_CASES_PASSED=18' build/test-temp/runtime.out.txt

step "ResourceWorldChecks → RESOURCE_WORLD_CASES_PASSED=24"
if ! java -cp "$TCP" lab.simulation.resource.ResourceWorldChecks > build/test-temp/resource-world.out.txt 2>&1; then
    cat build/test-temp/resource-world.out.txt >&2
    die "RESOURCE_WORLD_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^RW-[0-9][0-9] PASS$' build/test-temp/resource-world.out.txt || true)" -eq 24 ] \
    || die "RESOURCE_WORLD_CHECKS_COUNT"
grep -qxF 'RESOURCE_WORLD_CASES_PASSED=24' build/test-temp/resource-world.out.txt \
    || die "RESOURCE_WORLD_CHECKS_LINE"
grep -xF 'RESOURCE_WORLD_CASES_PASSED=24' build/test-temp/resource-world.out.txt

step "ResourceStorageChecks → RESOURCE_STORAGE_CASES_PASSED=6"
if ! java -cp "$TCP" lab.simulation.resource.ResourceStorageChecks > build/test-temp/resource-storage.out.txt 2>&1; then
    cat build/test-temp/resource-storage.out.txt >&2
    die "RESOURCE_STORAGE_CHECKS_EXIT_NONZERO"
fi
grep -qxF 'RESOURCE_STORAGE_CASES_PASSED=6' build/test-temp/resource-storage.out.txt \
    || die "RESOURCE_STORAGE_CHECKS_LINE"
grep -xF 'RESOURCE_STORAGE_CASES_PASSED=6' build/test-temp/resource-storage.out.txt

step "ResourceParserChecks → RESOURCE_PARSER_CASES_PASSED=7"
if ! java -cp "$TCP" lab.simulation.resource.ResourceParserChecks > build/test-temp/resource-parser.out.txt 2>&1; then
    cat build/test-temp/resource-parser.out.txt >&2
    die "RESOURCE_PARSER_CHECKS_EXIT_NONZERO"
fi
grep -qxF 'RESOURCE_PARSER_CASES_PASSED=7' build/test-temp/resource-parser.out.txt \
    || die "RESOURCE_PARSER_CHECKS_LINE"
grep -xF 'RESOURCE_PARSER_CASES_PASSED=7' build/test-temp/resource-parser.out.txt

step "ResourceScenarioChecks → RESOURCE_SCENARIO_CASES_PASSED=10"
if ! java -cp "$TCP" lab.simulation.resource.ResourceScenarioChecks > build/test-temp/resource-scenario.out.txt 2>&1; then
    cat build/test-temp/resource-scenario.out.txt >&2
    die "RESOURCE_SCENARIO_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^RSC-[0-9][0-9] PASS$' build/test-temp/resource-scenario.out.txt || true)" -eq 10 ] \
    || die "RESOURCE_SCENARIO_CHECKS_COUNT"
grep -qxF 'RESOURCE_SCENARIO_CASES_PASSED=10' build/test-temp/resource-scenario.out.txt \
    || die "RESOURCE_SCENARIO_CHECKS_LINE"
grep -xF 'RESOURCE_SCENARIO_CASES_PASSED=10' build/test-temp/resource-scenario.out.txt

step "ReplayEvidenceChecks → REPLAY_EVIDENCE_R1_CASES_PASSED=12"
if ! java -cp "$TCP" lab.simulation.ReplayEvidenceChecks > build/test-temp/replay.out.txt 2>&1; then
    cat build/test-temp/replay.out.txt >&2
    die "REPLAY_CHECKS_EXIT_NONZERO"
fi
[ "$(grep -c '^RPL-[0-9][0-9] PASS$' build/test-temp/replay.out.txt || true)" -eq 12 ] \
    || die "REPLAY_CHECKS_COUNT"
grep -qxF 'REPLAY_EVIDENCE_R1_CASES_PASSED=12' build/test-temp/replay.out.txt \
    || die "REPLAY_CHECKS_LINE"
grep -xF 'REPLAY_EVIDENCE_R1_CASES_PASSED=12' build/test-temp/replay.out.txt

step "validate 三个合法场景（exit 0）"
VALID_SCENARIOS=0
for scenario in scenarios/sim-001-happy.scenario \
                scenarios/sim-002-timeout-created.scenario \
                scenarios/sim-003-timeout-not-created.scenario; do
    if ! java -cp "$CP" lab.simulation.SimulationMain validate "$scenario" \
            > build/test-temp/validate.out.txt 2>&1; then
        cat build/test-temp/validate.out.txt >&2
        die "VALID_SCENARIO_EXIT $scenario"
    fi
    grep -q '^SCENARIO_VALID ' build/test-temp/validate.out.txt \
        || die "VALID_SCENARIO_OUTPUT $scenario"
    VALID_SCENARIOS=$((VALID_SCENARIOS + 1))
done
[ "$VALID_SCENARIOS" -eq 3 ] || die "VALID_SCENARIO_COUNT $VALID_SCENARIOS"
echo "VALID_SCENARIOS=$VALID_SCENARIOS"

step "validate 两个 parse 非法场景（exit 2 + 稳定 code）"
INVALID_PARSE=0
check_invalid_parse() {
    local scenario="$1" expected_code="$2" rc
    if java -cp "$CP" lab.simulation.SimulationMain validate "$scenario" \
            > build/test-temp/validate-invalid.out.txt 2>&1; then
        rc=0
    else
        rc=$?
    fi
    [ "$rc" -eq 2 ] || die "INVALID_PARSE_EXIT $scenario rc=$rc"
    grep -q "^ERR $expected_code" build/test-temp/validate-invalid.out.txt \
        || die "INVALID_PARSE_CODE $scenario expect=$expected_code"
    head -n 1 build/test-temp/validate-invalid.out.txt
}
check_invalid_parse scenarios/invalid/invalid-backward-time.scenario SCENARIO_EVENT_ORDER
INVALID_PARSE=$((INVALID_PARSE + 1))
check_invalid_parse scenarios/invalid/invalid-duplicate-event.scenario SCENARIO_DUPLICATE_EVENT
INVALID_PARSE=$((INVALID_PARSE + 1))
[ "$INVALID_PARSE" -eq 2 ] || die "INVALID_PARSE_COUNT $INVALID_PARSE"

step "run 非法场景 invalid-unscripted-call（exit 1 + ORC-SYS-001=FAIL）"
INVALID_UNSCRIPTED_DIR="build/runs/invalid-unscripted"
INVALID_RUN_RC=0
if java -cp "$CP" lab.simulation.SimulationMain run \
        scenarios/invalid/invalid-unscripted-call.scenario "$INVALID_UNSCRIPTED_DIR" \
        > build/test-temp/run-invalid.out.txt 2>&1; then
    INVALID_RUN_RC=0
else
    INVALID_RUN_RC=$?
fi
[ "$INVALID_RUN_RC" -eq 1 ] || die "UNSCRIPTED_RUN_EXIT $INVALID_RUN_RC"
grep -qxF 'RUN_FAILED SIM-INVALID-03 UNSCRIPTED_EXTERNAL_CALL' build/test-temp/run-invalid.out.txt \
    || die "UNSCRIPTED_RUN_STATUS"
grep -qxF "EVIDENCE_WRITTEN $INVALID_UNSCRIPTED_DIR" build/test-temp/run-invalid.out.txt \
    || die "UNSCRIPTED_RUN_EVIDENCE"
awk -F'\t' '$1 == "ORC-SYS-001" { found = 1; if ($3 != "FAIL") { found = 0 } }
             END { exit (found ? 0 : 1) }' "$INVALID_UNSCRIPTED_DIR/oracle-results.tsv" \
    || die "UNSCRIPTED_ORC_SYS_001_NOT_FAIL"
echo "UNSCRIPTED_RUN_OK exit=$INVALID_RUN_RC ORC-SYS-001=FAIL"

step "run SIM-001 → build/runs/sim-001-a"
run_ok_scenario() {
    local scenario="$1" dir="$2" rc
    if java -cp "$CP" lab.simulation.SimulationMain run "$scenario" "$dir" \
            > build/test-temp/run.out.txt 2>&1; then
        rc=0
    else
        rc=$?
    fi
    [ "$rc" -eq 0 ] || die "RUN_FAILED $scenario rc=$rc"
    grep -q '^RUN_COMPLETED ' build/test-temp/run.out.txt || die "RUN_NOT_COMPLETED $scenario"
    grep -qxF "EVIDENCE_WRITTEN $dir" build/test-temp/run.out.txt \
        || die "RUN_EVIDENCE_MISSING $scenario"
    grep -E '^RUN_COMPLETED |^EVIDENCE_WRITTEN ' build/test-temp/run.out.txt
}
run_ok_scenario scenarios/sim-001-happy.scenario build/runs/sim-001-a

step "run SIM-002 → build/runs/sim-002-a"
run_ok_scenario scenarios/sim-002-timeout-created.scenario build/runs/sim-002-a

step "run SIM-003 → build/runs/sim-003-a"
run_ok_scenario scenarios/sim-003-timeout-not-created.scenario build/runs/sim-003-a

step "二次 run SIM-002 → build/runs/sim-002-b"
run_ok_scenario scenarios/sim-002-timeout-created.scenario build/runs/sim-002-b

step "compare 两次 SIM-002 → REPLAY_MATCH"
COMPARE_RC=0
if java -cp "$CP" lab.simulation.SimulationMain compare \
        build/runs/sim-002-a build/runs/sim-002-b > build/test-temp/compare.out.txt 2>&1; then
    COMPARE_RC=0
else
    COMPARE_RC=$?
fi
[ "$COMPARE_RC" -eq 0 ] || die "COMPARE_EXIT $COMPARE_RC"
grep -qxF 'REPLAY_MATCH SIM-002 version=1' build/test-temp/compare.out.txt \
    || die "COMPARE_NOT_MATCH"
grep -xF 'REPLAY_MATCH SIM-002 version=1' build/test-temp/compare.out.txt

step "validate 六个资源合法场景（exit 0）"
RESOURCE_VALID=0
for scenario in scenarios/resource/sim-101-single-hold-release.scenario \
                scenarios/resource/sim-102-queue-order.scenario \
                scenarios/resource/sim-103-queue-promotion.scenario \
                scenarios/resource/sim-104-idempotent-request.scenario \
                scenarios/resource/sim-105-disable-enable.scenario \
                scenarios/resource/sim-106-restart-recovery.scenario; do
    if ! java -cp "$CP" lab.simulation.SimulationMain validate "$scenario" \
            > build/test-temp/validate-resource.out.txt 2>&1; then
        cat build/test-temp/validate-resource.out.txt >&2
        die "RESOURCE_VALID_SCENARIO_EXIT $scenario"
    fi
    grep -q '^SCENARIO_VALID ' build/test-temp/validate-resource.out.txt \
        || die "RESOURCE_VALID_SCENARIO_OUTPUT $scenario"
    RESOURCE_VALID=$((RESOURCE_VALID + 1))
done
[ "$RESOURCE_VALID" -eq 6 ] || die "RESOURCE_VALID_SCENARIO_COUNT $RESOURCE_VALID"
echo "RESOURCE_VALID_SCENARIOS=$RESOURCE_VALID"

step "六个资源场景各跑两次 → compare 6 对 REPLAY_MATCH"
RESOURCE_REPLAY=0
RESOURCE_BUNDLES=""
run_resource_pair() {
    local scenario="$1" id="$2" dir_a="$3" dir_b="$4" rc=0 dir
    for dir in "$dir_a" "$dir_b"; do
        if java -cp "$CP" lab.simulation.SimulationMain run "$scenario" "$dir" \
                > build/test-temp/run-resource.out.txt 2>&1; then
            rc=0
        else
            rc=$?
        fi
        [ "$rc" -eq 0 ] || die "RESOURCE_RUN_FAILED $scenario rc=$rc"
        grep -q '^RUN_COMPLETED ' build/test-temp/run-resource.out.txt \
            || die "RESOURCE_RUN_NOT_COMPLETED $scenario"
        grep -qxF "EVIDENCE_WRITTEN $dir" build/test-temp/run-resource.out.txt \
            || die "RESOURCE_RUN_EVIDENCE_MISSING $scenario"
    done
    if java -cp "$CP" lab.simulation.SimulationMain compare "$dir_a" "$dir_b" \
            > build/test-temp/compare-resource.out.txt 2>&1; then
        rc=0
    else
        rc=$?
    fi
    [ "$rc" -eq 0 ] || die "RESOURCE_COMPARE_EXIT $id rc=$rc"
    grep -qxF "REPLAY_MATCH $id version=1" build/test-temp/compare-resource.out.txt \
        || die "RESOURCE_COMPARE_NOT_MATCH $id"
    grep -xF "REPLAY_MATCH $id version=1" build/test-temp/compare-resource.out.txt
    RESOURCE_REPLAY=$((RESOURCE_REPLAY + 1))
    RESOURCE_BUNDLES="$RESOURCE_BUNDLES $dir_a $dir_b"
}
run_resource_pair scenarios/resource/sim-101-single-hold-release.scenario SIM-101 \
    build/runs/res-101-a build/runs/res-101-b
run_resource_pair scenarios/resource/sim-102-queue-order.scenario SIM-102 \
    build/runs/res-102-a build/runs/res-102-b
run_resource_pair scenarios/resource/sim-103-queue-promotion.scenario SIM-103 \
    build/runs/res-103-a build/runs/res-103-b
run_resource_pair scenarios/resource/sim-104-idempotent-request.scenario SIM-104 \
    build/runs/res-104-a build/runs/res-104-b
run_resource_pair scenarios/resource/sim-105-disable-enable.scenario SIM-105 \
    build/runs/res-105-a build/runs/res-105-b
run_resource_pair scenarios/resource/sim-106-restart-recovery.scenario SIM-106 \
    build/runs/res-106-a build/runs/res-106-b
[ "$RESOURCE_REPLAY" -eq 6 ] || die "RESOURCE_REPLAY_COUNT $RESOURCE_REPLAY"
echo "RESOURCE_REPLAY=$RESOURCE_REPLAY"

step "五个资源非法场景（exit 1 + 稳定 code + 首错停止）"
RESOURCE_INVALID=0
RESOURCE_INVALID_BUNDLES=""
check_resource_invalid() {
    local scenario="$1" id="$2" code="$3" dir="$4" rc=0
    rm -rf "$dir"
    if java -cp "$CP" lab.simulation.SimulationMain run "$scenario" "$dir" \
            > build/test-temp/run-resource-invalid.out.txt 2>&1; then
        rc=0
    else
        rc=$?
    fi
    [ "$rc" -eq 1 ] || die "RESOURCE_INVALID_EXIT $id rc=$rc"
    grep -qxF "RUN_FAILED $id $code" build/test-temp/run-resource-invalid.out.txt \
        || die "RESOURCE_INVALID_CODE $id expect=$code"
    grep -qxF "EVIDENCE_WRITTEN $dir" build/test-temp/run-resource-invalid.out.txt \
        || die "RESOURCE_INVALID_EVIDENCE $id"
    [ -d "$dir" ] || die "RESOURCE_INVALID_DIR $id"
    if ! ( cd "$dir" && sha256sum -c manifest.sha256 > /dev/null 2>&1 ); then
        die "RESOURCE_INVALID_MANIFEST $id"
    fi
    awk -F'\t' -v code="$code" '
        NR == 1 { next }
        !seen && $5 == "EVENT_FAIL" { seen = 1; if (index($7, code) != 1) bad = 1; next }
        seen { if ($5 != "EVENT_SKIPPED") bad = 1; skipped++ }
        END { exit (!(seen && bad == 0 && skipped > 0)) }
    ' "$dir/event-timeline.tsv" || die "RESOURCE_INVALID_FIRST_FAIL_STOP $id"
    head -n 1 build/test-temp/run-resource-invalid.out.txt
    RESOURCE_INVALID=$((RESOURCE_INVALID + 1))
    RESOURCE_INVALID_BUNDLES="$RESOURCE_INVALID_BUNDLES $dir"
}
check_resource_invalid scenarios/resource/invalid/sim-invalid-101-missing-destination.scenario \
    SIM-INVALID-101 RESOURCE_TARGET_MISMATCH build/runs/res-invalid-101
check_resource_invalid scenarios/resource/invalid/sim-invalid-102-missing-point.scenario \
    SIM-INVALID-102 UNKNOWN_POINT build/runs/res-invalid-102
check_resource_invalid scenarios/resource/invalid/sim-invalid-103-conflict.scenario \
    SIM-INVALID-103 RESERVATION_CONFLICT build/runs/res-invalid-103
check_resource_invalid scenarios/resource/invalid/sim-invalid-104-terminal-reopen.scenario \
    SIM-INVALID-104 RESERVATION_TERMINAL build/runs/res-invalid-104
check_resource_invalid scenarios/resource/invalid/sim-invalid-105-reference-mismatch.scenario \
    SIM-INVALID-105 RESOURCE_TARGET_MISMATCH build/runs/res-invalid-105
[ "$RESOURCE_INVALID" -eq 5 ] || die "RESOURCE_INVALID_COUNT $RESOURCE_INVALID"
echo "RESOURCE_INVALID_SCENARIOS=$RESOURCE_INVALID"

step "22 个证据包逐一 sha256sum -c manifest.sha256"
BUNDLES="build/runs/invalid-unscripted build/runs/sim-001-a build/runs/sim-002-a build/runs/sim-003-a build/runs/sim-002-b$RESOURCE_BUNDLES$RESOURCE_INVALID_BUNDLES"
BUNDLE_COUNT=0
for bundle in $BUNDLES; do
    [ -d "$bundle" ] || die "BUNDLE_MISSING $bundle"
    if ! ( cd "$bundle" && sha256sum -c manifest.sha256 > "$ROOT/build/test-temp/manifest.out.txt" 2>&1 ); then
        cat "$ROOT/build/test-temp/manifest.out.txt" >&2
        die "MANIFEST_CHECK_FAILED $bundle"
    fi
    BUNDLE_COUNT=$((BUNDLE_COUNT + 1))
done
[ "$BUNDLE_COUNT" -eq 22 ] || die "BUNDLE_COUNT $BUNDLE_COUNT"
echo "BASE_EVIDENCE_BUNDLES=5 RESOURCE_EVIDENCE_BUNDLES=17 EVIDENCE_BUNDLES=$BUNDLE_COUNT"

step "证据包形状：恰好 7 个文件 / manifest 恰好 6 行"
EXPECTED_FILES="$(printf '%s\n' event-timeline.tsv external-interactions.tsv manifest.sha256 \
    oracle-results.tsv run-summary.txt scenario.scenario scenario.sha256 | LC_ALL=C sort)"
for bundle in $BUNDLES; do
    actual_files="$( cd "$bundle" && find . -maxdepth 1 -type f -printf '%f\n' | LC_ALL=C sort )"
    [ "$actual_files" = "$EXPECTED_FILES" ] || die "EVIDENCE_FILE_SET $bundle"
    manifest_lines="$(awk 'END { print NR }' "$bundle/manifest.sha256")"
    [ "$manifest_lines" -eq 6 ] || die "MANIFEST_LINE_COUNT $bundle $manifest_lines"
done
echo "EVIDENCE_SHAPE_OK dirs=$BUNDLE_COUNT"

step "run-summary.txt 21 字段逐字顺序核对"
SUMMARY_FIELDS="scenarioId scenarioVersion scenarioDigest rootSeed virtualStartAt \
schemaVersion runStatus plannedEventCount executedEventCount passCount failCount \
skippedCount firstFailureEventId firstFailureCode oraclePassCount oracleFailCount \
createCallCount queryCallCount validExternalOrderCount evidenceLevel realExternalWriteCount"
FIELD_TOTAL=0
for field in $SUMMARY_FIELDS; do
    FIELD_TOTAL=$((FIELD_TOTAL + 1))
done
[ "$FIELD_TOTAL" -eq 21 ] || die "SUMMARY_FIELD_TOTAL $FIELD_TOTAL"
for bundle in $BUNDLES; do
    summary="$bundle/run-summary.txt"
    summary_lines="$(awk 'END { print NR }' "$summary")"
    [ "$summary_lines" -eq 21 ] || die "SUMMARY_LINE_COUNT $bundle $summary_lines"
    index=0
    for field in $SUMMARY_FIELDS; do
        index=$((index + 1))
        line="$(sed -n "${index}p" "$summary")"
        case "$line" in
            "$field="*) ;;
            *) die "SUMMARY_FIELD_ORDER $bundle line=$index expect=$field" ;;
        esac
    done
done
# 文档冻结检查：docs/simulation-format.md 必须逐字列出同一组 21 字段（同一顺序）与三个 TSV 表头。
DOC_FIELDS="$(grep -E '^\| [0-9]+ \| `[A-Za-z]+` \|' docs/simulation-format.md \
    | sed -E 's/^\| [0-9]+ \| `([A-Za-z]+)` \|.*/\1/')"
[ "$(printf '%s\n' "$DOC_FIELDS" | awk 'END { print NR }')" -eq 21 ] \
    || die "FORMAT_DOC_FIELD_COUNT"
DOC_JOIN="$(printf '%s\n' "$DOC_FIELDS" | awk '{ printf "%s%s", sep, $0; sep = " " }')"
SUMMARY_JOIN="$(printf '%s\n' $SUMMARY_FIELDS | awk '{ printf "%s%s", sep, $0; sep = " " }')"
[ "$DOC_JOIN" = "$SUMMARY_JOIN" ] || die "FORMAT_DOC_FIELD_ORDER"
grep -qF "$(printf 'eventId\tplannedOffsetMillis\tlogicalSequence\taction\tstatus\tvirtualOccurredAt\tdetail')" \
    docs/simulation-format.md || die "FORMAT_DOC_TIMELINE_HEADER"
grep -qF "$(printf 'sequence\tvirtualOccurredAt\toperation\tordinal\tapplicationId\toutcome\tsideEffectCertainty\texternalOrderId\tscriptId')" \
    docs/simulation-format.md || die "FORMAT_DOC_INTERACTIONS_HEADER"
grep -qF "$(printf 'oracleId\truleType\tstatus\texpected\tactual\tdetail')" \
    docs/simulation-format.md || die "FORMAT_DOC_ORACLE_HEADER"
echo "SUMMARY_FIELDS_OK count=$FIELD_TOTAL"

step "Evidence 安全扫描（绝对路径 / 用户名 / 凭据 / 真实内网 IP / 越级声明）"
EVIDENCE_PATTERN='/(home|Users)/|C:\\|D:\\|tianya|192\.168\.|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.|[Tt]oken|[Pp]assword|[Ss]ecret|M4_CONTRACT_PASS|VERIFIED_LAB|SITE_PASS|PRODUCTION_READY|CAPACITY_PASS'
if grep -R -E -n "$EVIDENCE_PATTERN" build/runs > build/test-temp/evidence-scan.txt 2>&1; then
    cat build/test-temp/evidence-scan.txt >&2
    die "EVIDENCE_FORBIDDEN_TERM"
fi
echo "EVIDENCE_SCAN_OK"

step "README 与 docs 安全扫描（绝对路径 / 用户名 / 凭据值 / 真实内网 IP / 越级声明）"
ABS_PATH_PATTERN='/(home|Users)/|C:\\|D:\\'
if grep -R -E -n "$ABS_PATH_PATTERN" README.md docs > build/test-temp/docs-abs.txt 2>&1; then
    cat build/test-temp/docs-abs.txt >&2
    die "DOCS_ABSOLUTE_PATH"
fi
if grep -R -E -n 'tianya' README.md docs > build/test-temp/docs-user.txt 2>&1; then
    cat build/test-temp/docs-user.txt >&2
    die "DOCS_USERNAME"
fi
if grep -R -E -n '192\.168\.|10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.' \
        README.md docs > build/test-temp/docs-ip.txt 2>&1; then
    cat build/test-temp/docs-ip.txt >&2
    die "DOCS_REAL_IP"
fi
# 只拦“凭据值”形态（token=/password=/secret=…、Bearer …），不拦安全文档里对 Token/Secret 的否定式表述。
if grep -R -n -i -E '(token|password|secret)[[:space:]]*[:=][[:space:]]*[^[:space:]]+|Bearer[[:space:]]+[A-Za-z0-9._-]+' \
        README.md docs > build/test-temp/docs-secret.txt 2>&1; then
    cat build/test-temp/docs-secret.txt >&2
    die "DOCS_SECRET_VALUE"
fi
# 越级声明词只允许出现在“禁止/不得/不声称”或清单/表格的否定语境中。
if grep -R -n -E 'M4_CONTRACT_PASS|VERIFIED_LAB|SITE_PASS|PRODUCTION_READY|CAPACITY_PASS' \
        README.md docs \
        | grep -v -E ':[[:space:]]*(-|\|)|禁止|不得|不声称|OUT_OF_SCOPE' \
        > build/test-temp/docs-claims.txt 2>&1; then
    cat build/test-temp/docs-claims.txt >&2
    die "DOCS_FORBIDDEN_CLAIM"
fi
echo "DOCS_SCAN_OK"

step "工作区干净 + 确定性扫描"
LEFTOVER="$(find . -name '*.class' -not -path './out/*' -not -path './build/*' \
    | LC_ALL=C sort | awk 'NR <= 5')"
[ -z "$LEFTOVER" ] || die "LEFTOVER_CLASS $LEFTOVER"
LEFTOVER="$(find . \( -name '*.db' -o -name '*.db-wal' -o -name '*.db-shm' \
        -o -name '*-journal' -o -name '*.journal' \) -not -path './build/*' \
    | LC_ALL=C sort | awk 'NR <= 5')"
[ -z "$LEFTOVER" ] || die "LEFTOVER_DB $LEFTOVER"
if grep -R -n -E 'Thread\.sleep|Instant\.now|currentTimeMillis|nanoTime|LocalDate(Time)?\.now' src \
        > build/test-temp/nondeterminism.txt 2>&1; then
    cat build/test-temp/nondeterminism.txt >&2
    die "NONDETERMINISTIC_SOURCE"
fi
echo "WORKSPACE_OK DETERMINISM_OK"

step "汇总并输出唯一终点"
REAL_WRITES=0
for bundle in $BUNDLES; do
    writes="$(sed -n 's/^realExternalWriteCount=//p' "$bundle/run-summary.txt")"
    case "$writes" in
        ''|*[!0-9]*) die "REAL_WRITE_FIELD_INVALID $bundle" ;;
    esac
    REAL_WRITES=$((REAL_WRITES + writes))
    awk -F'\t' '$1 == "ORC-SYS-006" { found = 1; if ($3 != "PASS") { found = 0 } }
                 END { exit (found ? 0 : 1) }' "$bundle/oracle-results.tsv" \
        || die "ORC_SYS_006_NOT_PASS $bundle"
    awk -F'\t' '$1 == "ORC-SYS-005" { found = 1; if ($4 != "LOCAL_SIMULATION") { found = 0 } }
                 END { exit (found ? 0 : 1) }' "$bundle/oracle-results.tsv" \
        || die "ORC_SYS_005_NOT_LOCAL $bundle"
    awk -F'\t' '$1 == "ORC-RES-SYS-006" {
                     found = 1
                     if ($3 != "PASS") { found = 0 }
                     if ($5 !~ /^restartPairs=[0-9]+,mismatches=[0-9]+$/) { found = 0 }
                 }
                 END { exit (found ? 0 : 1) }' "$bundle/oracle-results.tsv" \
        || die "ORC_RES_SYS_006_NOT_PASS_OR_FORMAT $bundle"
done
[ "$REAL_WRITES" -eq 0 ] || die "REAL_EXTERNAL_WRITES $REAL_WRITES"
[ "$BUNDLE_COUNT" -eq 22 ] || die "EVIDENCE_BUNDLES $BUNDLE_COUNT"

printf 'SIMULATION_RESOURCE_VERIFICATION_PASS originalCases=%d baseValidScenarios=%d baseInvalidScenarios=%d resourceValidScenarios=%d resourceInvalidScenarios=%d resourceReplay=%d realExternalWrites=%d\n' \
    "$CASES_PASSED" "$VALID_SCENARIOS" $((INVALID_PARSE + 1)) "$RESOURCE_VALID" "$RESOURCE_INVALID" "$RESOURCE_REPLAY" "$REAL_WRITES"
