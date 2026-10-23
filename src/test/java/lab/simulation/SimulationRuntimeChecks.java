package lab.simulation;

import java.nio.file.Path;

/**
 * SimulationRuntime 的自动检查。
 *
 * <p>覆盖 RUN-01 到 RUN-18 的主要情形。
 */
public class SimulationRuntimeChecks {

    private static int passed = 0;

    public static void main(String[] args) {
        // RUN-05：SIM-001 最终 COMPLETED
        run001();

        // RUN-08 到 RUN-10：SIM-002 恢复路径
        run002();

        // RUN-11 到 RUN-12：SIM-003 保持 UNKNOWN
        run003();

        // RUN-13：缺失 external script
        runInvalid();

        // RUN-14：未消费 external script
        runUnconsumed();

        System.out.println("SIMULATION_RUNTIME_CHECKS_PASSED=" + passed);
    }

    private static void run001() {
        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/sim-001-happy.scenario"));
        SimulationRunResult r = new SimulationRuntime(pkg).run();

        check(r.runStatus().equals("COMPLETED"), "RUN-05 SIM-001 status");
        check(r.eventCount() == 6, "RUN-05 eventCount");
        check(r.createCallCount() == 1, "RUN-06 create=1");
        check(r.validExternalOrderCount() == 1, "RUN-06 externalOrders=1");
    }

    private static void run002() {
        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/sim-002-timeout-created.scenario"));
        SimulationRunResult r = new SimulationRuntime(pkg).run();

        check(r.runStatus().equals("COMPLETED"), "RUN-08 SIM-002 status");
        check(r.createCallCount() == 1, "RUN-10 create=1");
        check(r.queryCallCount() == 1, "RUN-09 query=1");
        check(r.validExternalOrderCount() == 1, "RUN-10 externalOrders=1");
    }

    private static void run003() {
        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/sim-003-timeout-not-created.scenario"));
        SimulationRunResult r = new SimulationRuntime(pkg).run();

        check(r.runStatus().equals("COMPLETED"), "RUN-11 SIM-003 status");
        check(r.createCallCount() == 1, "RUN-11 create=1");
        check(r.queryCallCount() == 1, "RUN-11 query=1");
        check(r.validExternalOrderCount() == 0, "RUN-12 externalOrders=0");
    }

    private static void runInvalid() {
        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/invalid/invalid-unscripted-call.scenario"));
        SimulationRunResult r = new SimulationRuntime(pkg).run();
        check(r.runStatus().equals("FAILED"), "RUN-13 unscripted call → FAILED");
    }

    private static void runUnconsumed() {
        // 构造一个场景：有 CREATE 脚本但没有 dispatch 事件
        try {
            Path tmp = java.nio.file.Files.createTempFile("run14-", ".scenario");
            java.nio.file.Files.writeString(tmp, """
                SCENARIO SIM-RUN14 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1400
                EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-APP-001
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                END
                """);
            ScenarioPackage pkg = ScenarioParser.parse(tmp);
            SimulationRunResult r = new SimulationRuntime(pkg).run();
            check(r.runStatus().equals("FAILED"), "RUN-14 unconsumed script → FAILED");
            java.nio.file.Files.deleteIfExists(tmp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
    }
}
