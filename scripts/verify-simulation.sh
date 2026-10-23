#!/usr/bin/env bash
#
# 一键仿真验证脚本。
#
# 从仓库根目录运行。任一步失败立即退出，不输出 PASS。
#
# 最终输出：
#   SIMULATION_VERIFICATION_PASS scenarios=3 replay=1 evidence=4 realExternalWrites=0

set -eu

# 1. 确认位置
if [ ! -f README.md ] || [ ! -d src ]; then
    echo "ERR NOT_IN_REPO_ROOT"
    exit 2
fi

JAR="lib/sqlite-jdbc-3.46.1.3.jar"
if [ ! -f "$JAR" ]; then
    echo "ERR JAR_NOT_FOUND $JAR"
    exit 2
fi

# 2. 清理并编译
rm -rf out build
mkdir -p out/main out/test build

find src/main/java -type f -name '*.java' | LC_ALL=C sort > build/main-sources.txt
find src/test/java -type f -name '*.java' | LC_ALL=C sort > build/test-sources.txt

echo "== compile main =="
javac -cp "$JAR" --release 21 -encoding UTF-8 -d out/main @build/main-sources.txt

echo "== compile test =="
javac -cp "out/main:$JAR" --release 21 -encoding UTF-8 -d out/test @build/test-sources.txt

CP="out/main:$JAR"
TCP="out/main:out/test:$JAR"

# 3. 运行各检查
echo "== RegistryChecks =="
java -cp "$CP" lab.RegistryChecks

echo "== ConcurrencyChecks =="
java -cp "$CP" lab.ConcurrencyChecks

echo "== ReliabilityCloseoutChecks =="
java -cp "$TCP" lab.ReliabilityCloseoutChecks

echo "== ScenarioParserChecks =="
java -cp "$TCP" lab.simulation.ScenarioParserChecks

echo "== SimulationRuntimeChecks =="
java -cp "$TCP" lab.simulation.SimulationRuntimeChecks

echo "== ReplayEvidenceChecks =="
java -cp "$TCP" lab.simulation.ReplayEvidenceChecks

# 4. validate 合法场景
echo "== validate valid scenarios =="
java -cp "$CP" lab.simulation.SimulationMain validate scenarios/sim-001-happy.scenario
java -cp "$CP" lab.simulation.SimulationMain validate scenarios/sim-002-timeout-created.scenario
java -cp "$CP" lab.simulation.SimulationMain validate scenarios/sim-003-timeout-not-created.scenario

# 5. validate 非法场景（期待非零退出）
echo "== validate invalid scenarios =="
for f in invalid-backward-time invalid-duplicate-event; do
    if java -cp "$CP" lab.simulation.SimulationMain validate "scenarios/invalid/${f}.scenario" 2>/dev/null; then
        echo "ERR INVALID_SCENARIO_SHOULD_FAIL $f"
        exit 1
    fi
done

# 6. run 三个合法场景
echo "== run valid scenarios =="
rm -rf build/runs
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-001-happy.scenario build/runs/sim-001-a
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-002-timeout-created.scenario build/runs/sim-002-a
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-003-timeout-not-created.scenario build/runs/sim-003-a

# 7. 第二次 run SIM-002 并 compare
echo "== replay SIM-002 =="
java -cp "$CP" lab.simulation.SimulationMain run scenarios/sim-002-timeout-created.scenario build/runs/sim-002-b
java -cp "$CP" lab.simulation.SimulationMain compare build/runs/sim-002-a build/runs/sim-002-b

# 8. 每个证据目录 sha256sum -c
echo "== verify manifests =="
for dir in build/runs/sim-001-a build/runs/sim-002-a build/runs/sim-003-a build/runs/sim-002-b; do
    (cd "$dir" && sha256sum -c manifest.sha256 >/dev/null)
done

# 9. 扫描 Evidence 不含绝对路径和禁止性结果词
echo "== scan evidence =="
if grep -R -E "/home/|/Users/|M4_CONTRACT_PASS|VERIFIED_LAB|SITE_PASS|PRODUCTION_READY" build/runs/ >/dev/null; then
    echo "ERR EVIDENCE_CONTAINS_FORBIDDEN_TERM"
    exit 1
fi

echo "SIMULATION_VERIFICATION_PASS scenarios=3 replay=1 evidence=4 realExternalWrites=0"
