package lab.simulation;

import lab.ApplicationState;
import lab.OutboxStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SimulationRuntime / InvariantOracle / SimulationMain 的 18 项具名检查：RUN-01 — RUN-18（PR2a/T2—T4）。
 *
 * <p>每个编号一个独立方法 {@code run01()} … {@code run18()}；只有该编号的全部断言都通过，
 * 才打印 {@code RUN-XX PASS} 并让 passed 加一。失败时打印 FAIL 与 INTENT，全部跑完以退出码 1 结束。
 */
public final class SimulationRuntimeChecks {

    private static final int CASE_TOTAL = 18;

    private static final String START = "2026-01-01T00:00:00Z";
    private static final Instant START_AT = Instant.parse(START);

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static final String INTENT_01 =
            "SIM-001 全绿：schemaVersion=2、planned/executed/pass/fail/skipped、evidenceLevel、realExternalWriteCount=0、无首错";
    private static final String INTENT_02 =
            "时间线字段固定为 eventId/plannedOffsetMillis/logicalSequence/action/status/virtualOccurredAt/detail，且虚拟时间=startAt+offset";
    private static final String INTENT_03 =
            "stateTransitions 逐事件记录申请状态与 Outbox 状态，SIM-001 上必须看到 CREATED→DISPATCHED→EXECUTING→COMPLETED";
    private static final String INTENT_04 =
            "每次外部调用尝试都被记录（scriptId/ordinal/scripted/outcome/外单号），与 interactions 一致";
    private static final String INTENT_05 =
            "SIM-002：最终 application=DISPATCHED、outbox=SENT（直接断言），create=1 query=1、状态 COMPLETED";
    private static final String INTENT_06 =
            "SIM-003：最终 application=UNKNOWN、outbox=UNCERTAIN（直接断言），有效外单=0、状态 COMPLETED";
    private static final String INTENT_07 =
            "未消费的外部脚本 → FAILED，firstFailureCode=UNCONSUMED_EXTERNAL_SCRIPT，且事件本身无失败";
    private static final String INTENT_08 =
            "单次运行：第二次调用 run() 必须抛 SIM_RUNTIME_ALREADY_USED";
    private static final String INTENT_09 =
            "首错即停：中间事件失败后，剩余事件全部 EVENT_SKIPPED，planned/executed/pass/fail/skipped 计数正确";
    private static final String INTENT_10 =
            "首错即停：失败后的 SKIPPED 事件不推进时钟（virtualOccurredAt 停在失败时刻）";
    private static final String INTENT_11 =
            "首错即停：失败后不再改状态（后续 APPLICATION_ADD 不得出现在最终状态里）";
    private static final String INTENT_12 =
            "首错即停：失败后不再调外部端口（create 计数与 attempts 不增加）";
    private static final String INTENT_13 =
            "invalid-unscripted-call：FAILED + ORC-SYS-001=FAIL + CLI 退出码 1，stdout 无堆栈";
    private static final String INTENT_14 =
            "Oracle FAIL 会让运行失败：EXPECT 不满足 → status=FAILED，firstFailureCode=该 oracleId";
    private static final String INTENT_15 =
            "连续多个尾随事件全部 SKIPPED，且状态序列为 PASS/FAIL/SKIPPED/SKIPPED…";
    private static final String INTENT_16 =
            "ORC-SYS-004 必须真算：进入 COMPLETED 后再变回非终态 → FAIL；单调终态 → PASS";
    private static final String INTENT_17 =
            "ORC-SYS-005/006 必须真算：evidenceLevel 越级或 Port/真实写不为零 → FAIL";
    private static final String INTENT_18 =
            "ORC-SYS-002/003 必须真算：同申请两个不同外单号 → FAIL；UNKNOWN 后紧跟 CREATE → FAIL；SIM-001 六条全 PASS";

    private SimulationRuntimeChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("sim-runtime-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("RUN-01", INTENT_01, SimulationRuntimeChecks::run01);
            run("RUN-02", INTENT_02, SimulationRuntimeChecks::run02);
            run("RUN-03", INTENT_03, SimulationRuntimeChecks::run03);
            run("RUN-04", INTENT_04, SimulationRuntimeChecks::run04);
            run("RUN-05", INTENT_05, SimulationRuntimeChecks::run05);
            run("RUN-06", INTENT_06, SimulationRuntimeChecks::run06);
            run("RUN-07", INTENT_07, SimulationRuntimeChecks::run07);
            run("RUN-08", INTENT_08, SimulationRuntimeChecks::run08);
            run("RUN-09", INTENT_09, SimulationRuntimeChecks::run09);
            run("RUN-10", INTENT_10, SimulationRuntimeChecks::run10);
            run("RUN-11", INTENT_11, SimulationRuntimeChecks::run11);
            run("RUN-12", INTENT_12, SimulationRuntimeChecks::run12);
            run("RUN-13", INTENT_13, SimulationRuntimeChecks::run13);
            run("RUN-14", INTENT_14, SimulationRuntimeChecks::run14);
            run("RUN-15", INTENT_15, SimulationRuntimeChecks::run15);
            run("RUN-16", INTENT_16, SimulationRuntimeChecks::run16);
            run("RUN-17", INTENT_17, SimulationRuntimeChecks::run17);
            run("RUN-18", INTENT_18, SimulationRuntimeChecks::run18);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("SIMULATION_RUNTIME_CASES_FAILED=" + failed);
            System.out.println("SIMULATION_RUNTIME_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("SIMULATION_RUNTIME_CASES_PASSED=" + passed);
    }

    // ================= RUN-01 =================

    private static void run01() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-001-happy.scenario"));

        require(r.schemaVersion() == 2, "schemaVersion=" + r.schemaVersion());
        require(r.runStatus().equals(SimulationRunResult.COMPLETED), "runStatus=" + r.runStatus());
        require(r.plannedCount() == 6, "planned=" + r.plannedCount());
        require(r.executedCount() == 6, "executed=" + r.executedCount());
        require(r.passCount() == 6, "pass=" + r.passCount());
        require(r.failCount() == 0, "fail=" + r.failCount());
        require(r.skippedCount() == 0, "skipped=" + r.skippedCount());
        require(r.firstFailureEventId() == null, "firstFailureEventId=" + r.firstFailureEventId());
        require(r.firstFailureCode() == null, "firstFailureCode=" + r.firstFailureCode());
        require("LOCAL_SIMULATION".equals(r.evidenceLevel()), "evidenceLevel=" + r.evidenceLevel());
        require(r.realExternalWriteCount() == 0, "realExternalWriteCount=" + r.realExternalWriteCount());
        require(r.oracleFailCount() == 0, "oracleFail=" + r.oracleFailCount());
        require(r.oraclePassCount() == 17, "oraclePass=" + r.oraclePassCount());
        require(r.createCallCount() == 1, "creates=" + r.createCallCount());
        require(r.queryCallCount() == 0, "queries=" + r.queryCallCount());
        require(r.validExternalOrderCount() == 1, "externalOrders=" + r.validExternalOrderCount());
        require(r.timeline().size() == 6, "timeline=" + r.timeline().size());
        require(r.interactions().size() == 1, "interactions=" + r.interactions().size());
    }

    // ================= RUN-02 =================

    private static void run02() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-001-happy.scenario"));
        List<SimulationRuntime.EventRecord> timeline = r.timeline();

        long[] offsets = {0L, 0L, 1000L, 2000L, 3000L, 4000L};
        long[] sequences = {10L, 20L, 30L, 40L, 50L, 60L};
        String[] ids = {"EVT-001", "EVT-002", "EVT-003", "EVT-004", "EVT-005", "EVT-006"};
        ScenarioEvent.Action[] actions = {
                ScenarioEvent.Action.POINT_ADD,
                ScenarioEvent.Action.POINT_ADD,
                ScenarioEvent.Action.APPLICATION_ADD,
                ScenarioEvent.Action.APPLICATION_DISPATCH,
                ScenarioEvent.Action.APPLICATION_CALLBACK,
                ScenarioEvent.Action.APPLICATION_CALLBACK};

        for (int i = 0; i < timeline.size(); i++) {
            SimulationRuntime.EventRecord rec = timeline.get(i);
            require(rec.eventId().equals(ids[i]), "第 " + i + " 条 eventId=" + rec.eventId());
            require(rec.plannedOffsetMillis() == offsets[i], "第 " + i + " 条 offset=" + rec.plannedOffsetMillis());
            require(rec.logicalSequence() == sequences[i], "第 " + i + " 条 seq=" + rec.logicalSequence());
            require(rec.action() == actions[i], "第 " + i + " 条 action=" + rec.action());
            require(rec.status().equals("EVENT_PASS"), "第 " + i + " 条 status=" + rec.status());
            require(rec.detail().isEmpty(), "第 " + i + " 条 detail=" + rec.detail());
            require(rec.virtualOccurredAt().equals(START_AT.plusMillis(offsets[i])),
                    "第 " + i + " 条 virtualOccurredAt=" + rec.virtualOccurredAt());
        }
        expectUnsupported(() -> timeline.add(null), "timeline 可修改");
    }

    // ================= RUN-03 =================

    private static void run03() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-001-happy.scenario"));
        require(r.runStatus().equals(SimulationRunResult.COMPLETED), "runStatus=" + r.runStatus());
        List<ApplicationState> states = new ArrayList<>();
        List<OutboxStatus> outbox = new ArrayList<>();
        for (StateTransition t : lastRuntime.stateTransitions()) {
            if (t.applicationId().equals("APP-001")) {
                states.add(t.applicationState());
                outbox.add(t.outboxStatus());
            }
        }
        require(states.equals(List.of(ApplicationState.CREATED, ApplicationState.DISPATCHED,
                        ApplicationState.EXECUTING, ApplicationState.COMPLETED)),
                "APP-001 状态轨迹=" + states);
        require(outbox.equals(java.util.Arrays.asList(null, OutboxStatus.SENT,
                        OutboxStatus.SENT, OutboxStatus.SENT)),
                "APP-001 Outbox 轨迹=" + outbox);

        List<Integer> sequences = lastRuntime.stateTransitions().stream().map(StateTransition::sequence).toList();
        for (int i = 1; i < sequences.size(); i++) {
            require(sequences.get(i) > sequences.get(i - 1), "transitions 序号必须严格递增：" + sequences);
        }
    }

    // ================= RUN-04 =================

    private static void run04() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-001-happy.scenario"));
        List<ExternalCallAttempt> attempts = lastRuntime.externalCallAttempts();

        require(attempts.size() == 1, "attempts=" + attempts.size());
        ExternalCallAttempt a = attempts.get(0);
        require(a.operation() == ExternalScript.Operation.CREATE, "operation=" + a.operation());
        require(a.ordinal() == 1, "ordinal=" + a.ordinal());
        require(a.applicationId().equals("APP-001"), "applicationId=" + a.applicationId());
        require(a.scripted(), "scripted=" + a.scripted());
        require(a.scriptId().equals("EXT-001"), "scriptId=" + a.scriptId());
        require(a.outcome().equals("ACCEPTED"), "outcome=" + a.outcome());
        require(a.externalOrderId().equals("EXT-APP-001"), "externalOrderId=" + a.externalOrderId());
        require(r.interactions().size() == 1, "interactions=" + r.interactions().size());
        require(r.interactions().get(0).externalOrderId().equals(a.externalOrderId()),
                "attempts 与 interactions 的外单号必须一致");
    }

    // ================= RUN-05 =================

    private static void run05() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-002-timeout-created.scenario"));

        require(r.runStatus().equals(SimulationRunResult.COMPLETED), "runStatus=" + r.runStatus());
        require(r.createCallCount() == 1, "creates=" + r.createCallCount());
        require(r.queryCallCount() == 1, "queries=" + r.queryCallCount());
        require(r.validExternalOrderCount() == 1, "externalOrders=" + r.validExternalOrderCount());
        require(lastRuntime.finalApplicationStates().get("APP-002") == ApplicationState.DISPATCHED,
                "最终 application=" + lastRuntime.finalApplicationStates());
        require(lastRuntime.finalOutboxStates().get("APP-002") == OutboxStatus.SENT,
                "最终 outbox=" + lastRuntime.finalOutboxStates());
        require(r.oracleFailCount() == 0, "oracleFail=" + r.oracleFailCount());
    }

    // ================= RUN-06 =================

    private static void run06() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-003-timeout-not-created.scenario"));

        require(r.runStatus().equals(SimulationRunResult.COMPLETED), "runStatus=" + r.runStatus());
        require(r.createCallCount() == 1, "creates=" + r.createCallCount());
        require(r.queryCallCount() == 1, "queries=" + r.queryCallCount());
        require(r.validExternalOrderCount() == 0, "externalOrders=" + r.validExternalOrderCount());
        require(lastRuntime.finalApplicationStates().get("APP-003") == ApplicationState.UNKNOWN,
                "最终 application=" + lastRuntime.finalApplicationStates());
        require(lastRuntime.finalOutboxStates().get("APP-003") == OutboxStatus.UNCERTAIN,
                "最终 outbox=" + lastRuntime.finalOutboxStates());
        require(r.oracleFailCount() == 0, "oracleFail=" + r.oracleFailCount());
    }

    // ================= RUN-07 =================

    private static void run07() {
        SimulationRunResult r = runScenario(writeScenario("run07", """
                SCENARIO SIM-RUN07 1
                START_AT 2026-01-01T00:00:00Z
                SEED 7007
                EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-1
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                END
                """));

        require(r.runStatus().equals(SimulationRunResult.FAILED), "runStatus=" + r.runStatus());
        require("UNCONSUMED_EXTERNAL_SCRIPT".equals(r.firstFailureCode()),
                "firstFailureCode=" + r.firstFailureCode());
        require(r.firstFailureEventId() == null, "事件本身没有失败，firstFailureEventId 应为 null");
        require(r.failCount() == 0, "fail=" + r.failCount());
        require(r.createCallCount() == 0, "creates=" + r.createCallCount());
    }

    // ================= RUN-08 =================

    private static void run08() {
        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/sim-001-happy.scenario"));
        SimulationRuntime runtime = new SimulationRuntime(pkg);
        runtime.run();
        try {
            runtime.run();
        } catch (ScenarioValidationException e) {
            require(e.code().equals("SIM_RUNTIME_ALREADY_USED"), "code=" + e.code());
            return;
        }
        throw new AssertionError("第二次 run() 未抛 SIM_RUNTIME_ALREADY_USED");
    }

    // ================= RUN-09 =================

    private static final String MID_FAIL = """
            SCENARIO SIM-RUN09 1
            START_AT 2026-01-01T00:00:00Z
            SEED 9009
            AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
            AT 0 20 EVT-002 POINT_ADD P02 装配线接收点
            AT 1000 30 EVT-003 APPLICATION_DISPATCH APP-404
            AT 2000 40 EVT-004 APPLICATION_ADD APP-002 P01 P02
            END
            """;

    private static void run09() {
        SimulationRunResult r = runScenario(writeScenario("run09", MID_FAIL));

        require(r.runStatus().equals(SimulationRunResult.FAILED), "runStatus=" + r.runStatus());
        require(r.plannedCount() == 4, "planned=" + r.plannedCount());
        require(r.passCount() == 2, "pass=" + r.passCount());
        require(r.failCount() == 1, "fail=" + r.failCount());
        require(r.skippedCount() == 1, "skipped=" + r.skippedCount());
        require(r.executedCount() == 3, "executed=" + r.executedCount());
        require("EVT-003".equals(r.firstFailureEventId()), "firstFailureEventId=" + r.firstFailureEventId());
        require("UNKNOWN_APPLICATION".equals(r.firstFailureCode()), "firstFailureCode=" + r.firstFailureCode());
        require(r.timeline().get(2).status().equals("EVENT_FAIL"), "第 3 条状态=" + r.timeline().get(2).status());
        require(r.timeline().get(3).status().equals("EVENT_SKIPPED"), "第 4 条状态=" + r.timeline().get(3).status());
    }

    // ================= RUN-10 =================

    private static void run10() {
        SimulationRunResult r = runScenario(writeScenario("run10", MID_FAIL));
        Instant failureMoment = START_AT.plusMillis(1000L);
        Instant skippedMoment = r.timeline().get(3).virtualOccurredAt();
        require(skippedMoment.equals(failureMoment),
                "SKIPPED 事件的 virtualOccurredAt=" + skippedMoment + "，失败时刻=" + failureMoment);
        require(r.timeline().get(2).virtualOccurredAt().equals(failureMoment),
                "失败事件的 virtualOccurredAt=" + r.timeline().get(2).virtualOccurredAt());
    }

    // ================= RUN-11 =================

    private static void run11() {
        SimulationRunResult r = runScenario(writeScenario("run11", MID_FAIL));
        Map<String, ApplicationState> apps = lastRuntime.finalApplicationStates();
        require(!apps.containsKey("APP-002"), "失败后仍写入了 APP-002：" + apps);
        require(r.timeline().get(3).status().equals("EVENT_SKIPPED"), "第 4 条必须 SKIPPED");
    }

    // ================= RUN-12 =================

    private static void run12() {
        SimulationRunResult r = runScenario(writeScenario("run12", """
                SCENARIO SIM-RUN12 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1212
                EXTERNAL EXT-001 CREATE 1 ACCEPTED EXT-1
                EXTERNAL EXT-002 CREATE 2 ACCEPTED EXT-2
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                AT 0 20 EVT-002 POINT_ADD P02 装配线接收点
                AT 1000 30 EVT-003 APPLICATION_ADD APP-001 P01 P02
                AT 2000 40 EVT-004 APPLICATION_DISPATCH APP-404
                AT 3000 50 EVT-005 APPLICATION_ADD APP-002 P01 P02
                AT 4000 60 EVT-006 APPLICATION_DISPATCH APP-002
                END
                """));

        require(r.runStatus().equals(SimulationRunResult.FAILED), "runStatus=" + r.runStatus());
        require(r.createCallCount() == 0, "失败后仍调用了外部 create：" + r.createCallCount());
        require(lastRuntime.externalCallAttempts().isEmpty(),
                "失败后仍记录了外部调用尝试：" + lastRuntime.externalCallAttempts().size());
        require(r.skippedCount() == 2, "skipped=" + r.skippedCount());
    }

    // ================= RUN-13 =================

    private static void run13() throws Exception {
        Path scenario = Path.of("scenarios/invalid/invalid-unscripted-call.scenario");
        SimulationRunResult r = runScenario(scenario);

        require(r.runStatus().equals(SimulationRunResult.FAILED), "runStatus=" + r.runStatus());
        require("UNSCRIPTED_EXTERNAL_CALL".equals(r.firstFailureCode()),
                "firstFailureCode=" + r.firstFailureCode());
        OracleResult sys001 = r.oracleResults().stream()
                .filter(x -> x.oracleId().equals("ORC-SYS-001")).findFirst()
                .orElseThrow(() -> new AssertionError("缺少 ORC-SYS-001 结果"));
        require(sys001.status() == OracleResult.Status.FAIL, "ORC-SYS-001=" + sys001.status());

        Path evidence = tmpDir.resolve("run13-evidence");
        CliResult cli = cli("run", scenario.toAbsolutePath().toString(), evidence.toString());
        require(cli.exitCode() == 1, "CLI 退出码=" + cli.exitCode() + "，输出=" + cli.output());
        require(cli.output().contains("RUN_FAILED"), "CLI 输出缺少 RUN_FAILED：" + cli.output());
        require(cli.output().contains("UNSCRIPTED_EXTERNAL_CALL"),
                "CLI 输出缺少错误码：" + cli.output());
        require(!cli.output().contains("\tat "), "stdout 出现了堆栈：" + cli.output());
        require(Files.exists(evidence.resolve("manifest.sha256")), "失败运行也必须写证据");
    }

    // ================= RUN-14 =================

    private static void run14() {
        SimulationRunResult r = runScenario(writeScenario("run14", """
                SCENARIO SIM-RUN14 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1414
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                AT 0 20 EVT-002 POINT_ADD P02 装配线接收点
                AT 1000 30 EVT-003 APPLICATION_ADD APP-001 P01 P02
                EXPECT ORC-001 APPLICATION_STATE APP-001 COMPLETED
                END
                """));

        require(r.runStatus().equals(SimulationRunResult.FAILED), "runStatus=" + r.runStatus());
        require(r.oracleFailCount() == 1, "oracleFail=" + r.oracleFailCount());
        require("ORC-001".equals(r.firstFailureCode()), "firstFailureCode=" + r.firstFailureCode());
        require(r.failCount() == 0, "事件本身没有失败，fail 应为 0");
    }

    // ================= RUN-15 =================

    private static void run15() {
        SimulationRunResult r = runScenario(writeScenario("run15", """
                SCENARIO SIM-RUN15 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1515
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                AT 0 20 EVT-002 POINT_ADD P02 装配线接收点
                AT 1000 30 EVT-003 APPLICATION_ADD APP-001 P01 P02
                AT 2000 40 EVT-004 APPLICATION_DISPATCH APP-404
                AT 3000 50 EVT-005 APPLICATION_ADD APP-002 P01 P02
                AT 4000 60 EVT-006 OUTBOX_PROCESS
                AT 5000 70 EVT-007 APPLICATION_ADD APP-003 P01 P02
                END
                """));

        List<String> statuses = r.timeline().stream().map(SimulationRuntime.EventRecord::status).toList();
        require(statuses.equals(List.of("EVENT_PASS", "EVENT_PASS", "EVENT_PASS",
                        "EVENT_FAIL", "EVENT_SKIPPED", "EVENT_SKIPPED", "EVENT_SKIPPED")),
                "状态序列=" + statuses);
        require(r.skippedCount() == 3, "skipped=" + r.skippedCount());
        require(r.passCount() == 3, "pass=" + r.passCount());
        require(r.failCount() == 1, "fail=" + r.failCount());
        Instant failureMoment = START_AT.plusMillis(2000L);
        for (int i = 4; i < 7; i++) {
            require(r.timeline().get(i).virtualOccurredAt().equals(failureMoment),
                    "第 " + (i + 1) + " 条 SKIPPED 的时钟被推进了：" + r.timeline().get(i).virtualOccurredAt());
        }
    }

    // ================= RUN-16 =================

    private static void run16() {
        SimulationRuntime runtime = runWithRuntime(writeScenario("run16", """
                SCENARIO SIM-RUN16 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1616
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                AT 0 20 EVT-002 POINT_ADD P02 装配线接收点
                AT 1000 30 EVT-003 APPLICATION_ADD APP-001 P01 P02
                END
                """));
        ScenarioPackage pkg = ScenarioParser.parse(tmpDir.resolve("run16.scenario"));

        // 正例：把真实运行的状态轨迹交给 Oracle，ORC-SYS-004 必须 PASS
        OracleContext ok = context(runtime, null);
        require(oracleStatus(new InvariantOracle(pkg).check(ok), "ORC-SYS-004") == OracleResult.Status.PASS,
                "单调终态必须 PASS");

        // 反例：COMPLETED 之后又出现 EXECUTING → FAIL
        List<StateTransition> transitions = new ArrayList<>(runtime.stateTransitions());
        transitions.add(new StateTransition(999, START_AT, "EVT-X", "APP-001",
                ApplicationState.COMPLETED, OutboxStatus.SENT));
        transitions.add(new StateTransition(1000, START_AT, "EVT-Y", "APP-001",
                ApplicationState.EXECUTING, OutboxStatus.SENT));
        OracleContext bad = new OracleContext(ok.applications(), ok.outbox(), ok.interactions(),
                ok.externalCallAttempts(), transitions, ok.runFailures(), ok.plannedCount(),
                ok.executedCount(), ok.skippedCount(), ok.evidenceLevel(),
                ok.executionPortType(), ok.realExternalWriteCount());
        require(oracleStatus(new InvariantOracle(pkg).check(bad), "ORC-SYS-004") == OracleResult.Status.FAIL,
                "终态倒退必须 FAIL");
    }

    // ================= RUN-17 =================

    private static void run17() {
        SimulationRuntime runtime = runWithRuntime(writeScenario("run17", """
                SCENARIO SIM-RUN17 1
                START_AT 2026-01-01T00:00:00Z
                SEED 1717
                AT 0 10 EVT-001 POINT_ADD P01 原料暂存点
                END
                """));
        ScenarioPackage pkg = ScenarioParser.parse(tmpDir.resolve("run17.scenario"));
        OracleContext ok = context(runtime, null);

        require(oracleStatus(new InvariantOracle(pkg).check(ok), "ORC-SYS-006") == OracleResult.Status.PASS,
                "ScriptedFakeRcsAdapter + 0 次真实写必须 PASS");

        OracleContext badPort = new OracleContext(ok.applications(), ok.outbox(), ok.interactions(),
                ok.externalCallAttempts(), ok.stateTransitions(), ok.runFailures(), ok.plannedCount(),
                ok.executedCount(), ok.skippedCount(), ok.evidenceLevel(),
                "RealHttpRcsAdapter", 0);
        require(oracleStatus(new InvariantOracle(pkg).check(badPort), "ORC-SYS-006") == OracleResult.Status.FAIL,
                "非 ScriptedFakeRcsAdapter 必须 FAIL");

        OracleContext badWrite = new OracleContext(ok.applications(), ok.outbox(), ok.interactions(),
                ok.externalCallAttempts(), ok.stateTransitions(), ok.runFailures(), ok.plannedCount(),
                ok.executedCount(), ok.skippedCount(), ok.evidenceLevel(),
                "ScriptedFakeRcsAdapter", 1);
        require(oracleStatus(new InvariantOracle(pkg).check(badWrite), "ORC-SYS-006") == OracleResult.Status.FAIL,
                "真实写次数非零必须 FAIL");

        OracleContext badLevel = new OracleContext(ok.applications(), ok.outbox(), ok.interactions(),
                ok.externalCallAttempts(), ok.stateTransitions(), ok.runFailures(), ok.plannedCount(),
                ok.executedCount(), ok.skippedCount(), "VERIFIED_LAB",
                ok.executionPortType(), 0);
        require(oracleStatus(new InvariantOracle(pkg).check(badLevel), "ORC-SYS-005") == OracleResult.Status.FAIL,
                "evidenceLevel 越级必须 FAIL");
    }

    // ================= RUN-18 =================

    private static void run18() {
        SimulationRunResult r = runScenario(Path.of("scenarios/sim-001-happy.scenario"));
        for (String id : List.of("ORC-SYS-001", "ORC-SYS-002", "ORC-SYS-003",
                "ORC-SYS-004", "ORC-SYS-005", "ORC-SYS-006")) {
            require(oracleStatus(r.oracleResults(), id) == OracleResult.Status.PASS,
                    id + " 在 SIM-001 上必须 PASS");
        }

        ScenarioPackage pkg = ScenarioParser.parse(Path.of("scenarios/sim-001-happy.scenario"));
        List<ExternalInteraction> dup = List.of(
                new ExternalInteraction(1, START_AT, ExternalScript.Operation.CREATE, 1, "APP-001",
                        "ACCEPTED", ExternalInteraction.CONFIRMED_CREATED, "EXT-A", "S-1"),
                new ExternalInteraction(2, START_AT, ExternalScript.Operation.CREATE, 2, "APP-001",
                        "ACCEPTED", ExternalInteraction.CONFIRMED_CREATED, "EXT-B", "S-2"));
        OracleContext dupCtx = new OracleContext(List.of(), List.of(), dup, List.of(), List.of(),
                List.of(), 2, 2, 0, "LOCAL_SIMULATION", "ScriptedFakeRcsAdapter", 0);
        require(oracleStatus(new InvariantOracle(pkg).check(dupCtx), "ORC-SYS-002") == OracleResult.Status.FAIL,
                "同一申请两个不同外单号必须 FAIL");

        List<StateTransition> unknown = List.of(
                new StateTransition(1, START_AT, "EVT-1", "APP-001", ApplicationState.UNKNOWN, OutboxStatus.UNCERTAIN));
        List<ExternalCallAttempt> createAgain = List.of(
                new ExternalCallAttempt(2, START_AT, ExternalScript.Operation.CREATE, 1, "APP-001",
                        true, "S-1", "ACCEPTED", "EXT-A"));
        OracleContext retryCtx = new OracleContext(List.of(), List.of(), List.of(), createAgain, unknown,
                List.of(), 2, 2, 0, "LOCAL_SIMULATION", "ScriptedFakeRcsAdapter", 0);
        require(oracleStatus(new InvariantOracle(pkg).check(retryCtx), "ORC-SYS-003") == OracleResult.Status.FAIL,
                "UNKNOWN 之后先 CREATE 必须 FAIL");

        List<ExternalCallAttempt> queryFirst = List.of(
                new ExternalCallAttempt(2, START_AT, ExternalScript.Operation.QUERY, 1, "APP-001",
                        true, "S-2", "ACCEPTED", "EXT-A"));
        OracleContext queryCtx = new OracleContext(List.of(), List.of(), List.of(), queryFirst, unknown,
                List.of(), 2, 2, 0, "LOCAL_SIMULATION", "ScriptedFakeRcsAdapter", 0);
        require(oracleStatus(new InvariantOracle(pkg).check(queryCtx), "ORC-SYS-003") == OracleResult.Status.PASS,
                "UNKNOWN 之后先 QUERY 必须 PASS");
    }

    // ================= 基础设施 =================

    private static SimulationRuntime lastRuntime;

    private interface CaseBody {
        void run() throws Exception;
    }

    private static void run(String id, String intent, CaseBody body) {
        try {
            body.run();
        } catch (Throwable failure) {
            failed++;
            System.out.println(id + " FAIL: " + failureText(failure));
            System.out.println(id + " INTENT: " + intent);
            return;
        }
        passed++;
        System.out.println(id + " PASS");
    }

    private static String failureText(Throwable failure) {
        if (failure instanceof AssertionError) {
            return failure.getMessage() == null ? "断言失败（无详细信息）" : failure.getMessage();
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private interface Action {
        void run();
    }

    private static void expectUnsupported(Action action, String label) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError(label + "：未抛 UnsupportedOperationException");
    }

    private static Path writeScenario(String name, String content) {
        try {
            Path file = tmpDir.resolve(name + ".scenario");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            throw new IllegalStateException("无法写临时场景 " + name, e);
        }
    }

    private static SimulationRunResult runScenario(Path scenario) {
        runWithRuntime(scenario);
        return lastRuntime.run();
    }

    private static SimulationRuntime runWithRuntime(Path scenario) {
        lastRuntime = new SimulationRuntime(ScenarioParser.parse(scenario));
        return lastRuntime;
    }

    /**
     * 用真实运行产生的快照构造 OracleContext。
     */
    private static OracleContext context(SimulationRuntime runtime, List<RunFailure> failures) {
        List<lab.TransferApplication> apps = new ArrayList<>();
        for (Map.Entry<String, ApplicationState> e : runtime.finalApplicationStates().entrySet()) {
            apps.add(new lab.TransferApplication(e.getKey(), "P01", "P02", e.getValue(), null));
        }
        List<lab.OutboxEntry> outbox = new ArrayList<>();
        for (Map.Entry<String, OutboxStatus> e : runtime.finalOutboxStates().entrySet()) {
            outbox.add(new lab.OutboxEntry("OBX-" + e.getKey(), e.getKey(), "P01", "P02",
                    e.getValue(), null, 0));
        }
        return new OracleContext(apps, outbox, runtime.interactions(),
                runtime.externalCallAttempts(), runtime.stateTransitions(),
                failures == null ? List.of() : failures,
                runtime.plannedCount(), runtime.executedCount(), runtime.skippedCount(),
                "LOCAL_SIMULATION", "ScriptedFakeRcsAdapter", 0);
    }

    private static OracleResult.Status oracleStatus(List<OracleResult> results, String oracleId) {
        for (OracleResult result : results) {
            if (result.oracleId().equals(oracleId)) {
                return result.status();
            }
        }
        throw new AssertionError("缺少 Oracle 结果：" + oracleId);
    }

    private record CliResult(int exitCode, String output) {
    }

    private static CliResult cli(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("java.home") + "/bin/java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("lab.simulation.SimulationMain");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        return new CliResult(exit, output);
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (Exception ignored) {
            // 清理失败不影响结果
        }
    }
}
