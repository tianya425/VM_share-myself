package lab.simulation.resource;

import lab.MemoryStorage;
import lab.Registry;
import lab.RegistryException;
import lab.simulation.EvidenceBundleValidator;
import lab.simulation.ReplayResult;
import lab.simulation.ReplayComparator;
import lab.simulation.ResourceInvariantOracle;
import lab.simulation.ResourceStep;
import lab.simulation.RestartPair;
import lab.simulation.ScenarioEvent;
import lab.simulation.ScenarioPackage;
import lab.simulation.ScenarioParser;
import lab.simulation.SimulationRunResult;
import lab.simulation.SimulationRuntime;
import lab.simulation.EvidenceBundleWriter;
import lab.simulation.OracleResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Part B / PR5—PR6：资源运行时与资源场景的 10 项具名检查 RSC-01 — RSC-10。
 *
 * <p>每个编号一个独立方法 {@code rsc01()} … {@code rsc10()}；只有该编号全部断言通过，
 * 才打印 {@code RSC-XX PASS} 并让 passed 加一。失败时打印 {@code RSC-XX FAIL} 与 INTENT，
 * 全部跑完后只要有失败就以退出码 1 结束；10 项全过时最后一行是
 * {@code RESOURCE_SCENARIO_CASES_PASSED=10} 且退出码为 0。
 */
public final class ResourceScenarioChecks {

    private static final int CASE_TOTAL = 10;

    private static final List<String> VALID_SCENARIOS = List.of(
            "scenarios/resource/sim-101-single-hold-release.scenario",
            "scenarios/resource/sim-102-queue-order.scenario",
            "scenarios/resource/sim-103-queue-promotion.scenario",
            "scenarios/resource/sim-104-idempotent-request.scenario",
            "scenarios/resource/sim-105-disable-enable.scenario",
            "scenarios/resource/sim-106-restart-recovery.scenario");

    private static final List<String> RESOURCE_INVARIANTS = List.of(
            "ORC-RES-SYS-001", "ORC-RES-SYS-002", "ORC-RES-SYS-003",
            "ORC-RES-SYS-004", "ORC-RES-SYS-005", "ORC-RES-SYS-006");

    private static final String[] BASE_SCENARIOS = {
            "scenarios/sim-001-happy.scenario",
            "scenarios/sim-002-timeout-created.scenario",
            "scenarios/sim-003-timeout-not-created.scenario"};

    private static int passed = 0;
    private static int failed = 0;
    private static Path tmpDir;

    private static final String INTENT_01 =
            "六个资源合法场景全部 COMPLETED、六条 ORC-RES-SYS 与全部资源 EXPECT 都 PASS";
    private static final String INTENT_02 =
            "稳定晋升：release 后只晋升队首，队首由 offset→logicalSequence→applicationId 决定";
    private static final String INTENT_03 =
            "完全相同重复 request → IDEMPOTENT，条目不增、原排序键与队列顺序不变";
    private static final String INTENT_04 =
            "同申请不同目标 → RESERVATION_CONFLICT，状态与规范快照逐字不变";
    private static final String INTENT_05 =
            "disable 不驱逐 holder；disable 期间新请求 WAITING；enable 不越过 holder；release 后晋升";
    private static final String INTENT_06 =
            "SIM-106 两次 WORLD_RESTART 前后摘要一致（restartPairs=2, mismatches=0），且不触发外部调用";
    private static final String INTENT_07 =
            "五个资源非法场景：退出码 1 + 稳定 code + 首错即停（后续 EVENT_SKIPPED）";
    private static final String INTENT_08 =
            "六个合法场景各跑两次，证据 6 个内容文件逐字节一致（REPLAY_MATCH）";
    private static final String INTENT_09 =
            "ORC-RES-SYS-001..006 每条都有反例能 FAIL；-006 实际值必须是 restartPairs=<n>,mismatches=<m>";
    private static final String INTENT_10 =
            "Part A 三个合法场景 + 三个非法场景无退化";

    private ResourceScenarioChecks() {
    }

    public static void main(String[] args) {
        try {
            try {
                tmpDir = Files.createTempDirectory("resource-scenario-checks-");
            } catch (Exception e) {
                throw new IllegalStateException("无法创建临时目录", e);
            }
            run("RSC-01", INTENT_01, ResourceScenarioChecks::rsc01);
            run("RSC-02", INTENT_02, ResourceScenarioChecks::rsc02);
            run("RSC-03", INTENT_03, ResourceScenarioChecks::rsc03);
            run("RSC-04", INTENT_04, ResourceScenarioChecks::rsc04);
            run("RSC-05", INTENT_05, ResourceScenarioChecks::rsc05);
            run("RSC-06", INTENT_06, ResourceScenarioChecks::rsc06);
            run("RSC-07", INTENT_07, ResourceScenarioChecks::rsc07);
            run("RSC-08", INTENT_08, ResourceScenarioChecks::rsc08);
            run("RSC-09", INTENT_09, ResourceScenarioChecks::rsc09);
            run("RSC-10", INTENT_10, ResourceScenarioChecks::rsc10);
        } finally {
            deleteRecursively(tmpDir);
        }

        if (passed + failed != CASE_TOTAL) {
            throw new AssertionError("case 数量不符：passed+failed=" + (passed + failed)
                    + " 期望=" + CASE_TOTAL);
        }
        if (failed > 0) {
            System.out.println("RESOURCE_SCENARIO_CASES_FAILED=" + failed);
            System.out.println("RESOURCE_SCENARIO_CASES_PASSED=" + passed);
            System.exit(1);
        }
        System.out.println("RESOURCE_SCENARIO_CASES_PASSED=" + passed);
    }

    // ================= RSC-01 =================

    private static void rsc01() {
        require(VALID_SCENARIOS.size() == 6, "合法场景数=" + VALID_SCENARIOS.size());
        for (String scenario : VALID_SCENARIOS) {
            RuntimeRun run = runScenario(scenario);
            require("COMPLETED".equals(run.result().runStatus()),
                    scenario + " runStatus=" + run.result().runStatus()
                            + " firstFailure=" + run.result().firstFailureCode());
            require(run.result().oracleFailCount() == 0,
                    scenario + " oracleFail=" + run.result().oracleFailCount());
            for (String oracleId : RESOURCE_INVARIANTS) {
                require(statusOf(run.result().oracleResults(), oracleId) == OracleResult.Status.PASS,
                        scenario + " " + oracleId + "=" + statusOf(run.result().oracleResults(), oracleId));
            }
            int expects = 0;
            for (OracleResult result : run.result().oracleResults()) {
                if (result.oracleId().startsWith("ORC-1")) {
                    expects++;
                    require(result.status() == OracleResult.Status.PASS,
                            scenario + " " + result.oracleId() + "=" + result.status());
                }
            }
            require(expects > 0, scenario + " 没有资源 EXPECT 行");
        }
        // 重复请求场景必须真的把第二次 request 记为 IDEMPOTENT。
        RuntimeRun idempotent = runScenario(VALID_SCENARIOS.get(3));
        require(idempotent.runtime().reservationDecisions().containsKey("EVT-008"),
                "SIM-104 缺少 EVT-008 的 ReservationDecision");
        require(idempotent.runtime().reservationDecisions().get("EVT-008").outcome()
                        == ReservationDecision.Outcome.IDEMPOTENT,
                "SIM-104 EVT-008 outcome="
                        + idempotent.runtime().reservationDecisions().get("EVT-008").outcome());
    }

    // ================= RSC-02 =================

    private static void rsc02() {
        Registry registry = world();
        application(registry, "APP-001");
        application(registry, "APP-030");
        application(registry, "APP-020");

        require(request(registry, "APP-001", 1000, 1).outcome()
                        == ReservationDecision.Outcome.HELD,
                "APP-001 首次请求应为 HELD");
        require(request(registry, "APP-030", 2000, 2).outcome()
                        == ReservationDecision.Outcome.WAITING,
                "APP-030 首次请求应为 WAITING");
        require(request(registry, "APP-020", 3000, 3).outcome()
                        == ReservationDecision.Outcome.WAITING,
                "APP-020 首次请求应为 WAITING");
        require(waitingOrder(registry, "R01").equals(List.of("APP-030", "APP-020")),
                "队列顺序=" + waitingOrder(registry, "R01"));

        registry.releaseReservation("APP-001");
        require(stateOf(registry, "APP-001") == ReservationState.RELEASED,
                "APP-001=" + stateOf(registry, "APP-001"));
        require(stateOf(registry, "APP-030") == ReservationState.HELD,
                "release 后队首应为 APP-030，实际=" + stateOf(registry, "APP-030"));
        require(stateOf(registry, "APP-020") == ReservationState.WAITING,
                "APP-020 仍应 WAITING，实际=" + stateOf(registry, "APP-020"));

        registry.releaseReservation("APP-030");
        require(stateOf(registry, "APP-020") == ReservationState.HELD,
                "再次 release 后 APP-020 应为 HELD，实际=" + stateOf(registry, "APP-020"));
        require(waitingOrder(registry, "R01").isEmpty(),
                "队列应为空，实际=" + waitingOrder(registry, "R01"));
    }

    // ================= RSC-03 =================

    private static void rsc03() {
        Registry registry = world();
        application(registry, "APP-001");
        application(registry, "APP-030");
        application(registry, "APP-020");
        request(registry, "APP-001", 1000, 1);
        request(registry, "APP-030", 2000, 2);
        request(registry, "APP-020", 3000, 3);

        String before = registry.resourceWorldSnapshot().canonicalText();
        ReservationDecision duplicate = request(registry, "APP-030", 9999, 99);
        require(duplicate.outcome() == ReservationDecision.Outcome.IDEMPOTENT,
                "重复请求 outcome=" + duplicate.outcome());
        require(before.equals(registry.resourceWorldSnapshot().canonicalText()),
                "重复请求改变了规范快照");
        Reservation queued = reservationOf(registry, "APP-030");
        require(queued.offsetMillis() == 2000 && queued.logicalSequence() == 2,
                "重复请求改动了排序键：offset=" + queued.offsetMillis()
                        + " seq=" + queued.logicalSequence());
        require(waitingOrder(registry, "R01").equals(List.of("APP-030", "APP-020")),
                "重复请求改动了队列：=" + waitingOrder(registry, "R01"));

        String beforeHeld = registry.resourceWorldSnapshot().canonicalText();
        ReservationDecision duplicateHeld = request(registry, "APP-001", 8888, 88);
        require(duplicateHeld.outcome() == ReservationDecision.Outcome.IDEMPOTENT,
                "HELD 重复请求 outcome=" + duplicateHeld.outcome());
        require(beforeHeld.equals(registry.resourceWorldSnapshot().canonicalText()),
                "HELD 重复请求改变了规范快照");
        require(reservationOf(registry, "APP-001").offsetMillis() == 1000,
                "HELD 重复请求改动了排序键");
    }

    // ================= RSC-04 =================

    private static void rsc04() {
        Registry registry = world();
        addSecondTarget(registry);
        application(registry, "APP-001");
        request(registry, "APP-001", 1000, 1);

        String before = registry.resourceWorldSnapshot().canonicalText();
        try {
            registry.requestReservation("APP-001", "D02", "SP02", "R02", 2000, 2);
            throw new AssertionError("同申请不同目标未抛 RESERVATION_CONFLICT");
        } catch (RegistryException e) {
            require("RESERVATION_CONFLICT".equals(e.code()),
                    "code=" + e.code() + " 期望 RESERVATION_CONFLICT");
        }
        require(before.equals(registry.resourceWorldSnapshot().canonicalText()),
                "CONFLICT 后规范快照发生变化");
        require(stateOf(registry, "APP-001") == ReservationState.HELD,
                "CONFLICT 后 APP-001=" + stateOf(registry, "APP-001"));
        require(waitingOrder(registry, "R01").isEmpty(),
                "CONFLICT 后队列=" + waitingOrder(registry, "R01"));
    }

    // ================= RSC-05 =================

    private static void rsc05() {
        Registry registry = world();
        application(registry, "APP-001");
        application(registry, "APP-002");

        request(registry, "APP-001", 1000, 1);
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.DISABLED);
        require(availabilityOf(registry, "R01") == ResourceAvailability.DISABLED,
                "R01 availability=" + availabilityOf(registry, "R01"));
        require(stateOf(registry, "APP-001") == ReservationState.HELD,
                "disable 驱逐了 holder：APP-001=" + stateOf(registry, "APP-001"));

        require(request(registry, "APP-002", 2000, 2).outcome()
                        == ReservationDecision.Outcome.WAITING,
                "DISABLED 期间的新请求应为 WAITING");
        registry.setVirtualResourceAvailability("R01", ResourceAvailability.ENABLED);
        require(stateOf(registry, "APP-001") == ReservationState.HELD,
                "enable 越过 holder 晋升：APP-001=" + stateOf(registry, "APP-001"));
        require(stateOf(registry, "APP-002") == ReservationState.WAITING,
                "enable 时无空位，APP-002 仍应 WAITING，实际=" + stateOf(registry, "APP-002"));

        registry.releaseReservation("APP-001");
        require(stateOf(registry, "APP-002") == ReservationState.HELD,
                "release 后 APP-002 应晋升 HELD，实际=" + stateOf(registry, "APP-002"));
        require(waitingOrder(registry, "R01").isEmpty(),
                "晋升后队列应为空，实际=" + waitingOrder(registry, "R01"));
    }

    // ================= RSC-06 =================

    private static void rsc06() {
        RuntimeRun run = runScenario(VALID_SCENARIOS.get(5));
        require("COMPLETED".equals(run.result().runStatus()),
                "SIM-106 runStatus=" + run.result().runStatus());
        List<RestartPair> pairs = run.runtime().restartPairs();
        require(pairs.size() == 2, "restartPairs=" + pairs.size());
        for (RestartPair pair : pairs) {
            require(pair.consistent(), pair.eventId() + " before=" + pair.beforeDigest()
                    + " after=" + pair.afterDigest());
            require(pair.beforeDigest().matches("[0-9a-f]{64}"),
                    pair.eventId() + " beforeDigest 不是 SHA-256：" + pair.beforeDigest());
        }
        require(run.runtime().externalCallAttempts().isEmpty(),
                "WORLD_RESTART 触发了外部调用：" + run.runtime().externalCallAttempts());
        require(run.runtime().interactions().isEmpty(),
                "WORLD_RESTART 产生了外部交互：" + run.runtime().interactions());
        // 重载后的派生结构必须能继续工作：release 真实发生了晋升。
        require(stateOf(run.runtime().resourceWorldSnapshot(), "APP-001") == ReservationState.RELEASED,
                "APP-001=" + stateOf(run.runtime().resourceWorldSnapshot(), "APP-001"));
        require(stateOf(run.runtime().resourceWorldSnapshot(), "APP-002") == ReservationState.HELD,
                "APP-002=" + stateOf(run.runtime().resourceWorldSnapshot(), "APP-002"));
    }

    // ================= RSC-07 =================

    private static void rsc07() {
        String[][] invalid = {
                {"scenarios/resource/invalid/sim-invalid-101-missing-destination.scenario",
                        "SIM-INVALID-101", "RESOURCE_TARGET_MISMATCH", "EVT-007"},
                {"scenarios/resource/invalid/sim-invalid-102-missing-point.scenario",
                        "SIM-INVALID-102", "UNKNOWN_POINT", "EVT-004"},
                {"scenarios/resource/invalid/sim-invalid-103-conflict.scenario",
                        "SIM-INVALID-103", "RESERVATION_CONFLICT", "EVT-011"},
                {"scenarios/resource/invalid/sim-invalid-104-terminal-reopen.scenario",
                        "SIM-INVALID-104", "RESERVATION_TERMINAL", "EVT-009"},
                {"scenarios/resource/invalid/sim-invalid-105-reference-mismatch.scenario",
                        "SIM-INVALID-105", "RESOURCE_TARGET_MISMATCH", "EVT-008"}};
        require(invalid.length == 5, "非法场景数=" + invalid.length);
        for (String[] item : invalid) {
            String scenario = item[0];
            String scenarioId = item[1];
            String code = item[2];
            String failingEvent = item[3];
            Path evidence = tmpDir.resolve("invalid-" + scenarioId);
            CliResult cli = cli("run", scenario, evidence.toString());
            require(cli.exitCode() == 1, scenario + " 退出码=" + cli.exitCode()
                    + " 输出=" + cli.output());
            require(cli.output().contains("RUN_FAILED " + scenarioId + " " + code),
                    scenario + " 缺少 RUN_FAILED " + scenarioId + " " + code
                            + "，输出=" + cli.output());
            require(cli.output().contains("EVIDENCE_WRITTEN " + evidence),
                    scenario + " 缺少 EVIDENCE_WRITTEN，输出=" + cli.output());
            new EvidenceBundleValidator().validate(evidence);

            List<String> timeline = readLines(evidence.resolve("event-timeline.tsv"));
            boolean sawFailure = false;
            for (int i = 1; i < timeline.size(); i++) {
                String[] cells = timeline.get(i).split("\t", -1);
                String eventId = cells[0];
                String status = cells[4];
                if (!sawFailure) {
                    if (eventId.equals(failingEvent)) {
                        require(status.equals("EVENT_FAIL"), scenario + " " + eventId + "=" + status);
                        sawFailure = true;
                    } else {
                        require(status.equals("EVENT_PASS"), scenario + " " + eventId + "=" + status);
                    }
                } else {
                    require(status.equals("EVENT_SKIPPED"),
                            scenario + " 首错后 " + eventId + "=" + status + "，期望 EVENT_SKIPPED");
                }
            }
            require(sawFailure, scenario + " 时间线未出现失败事件 " + failingEvent);

            RuntimeRun run = runScenario(scenario);
            require(code.equals(run.result().firstFailureCode()),
                    scenario + " firstFailureCode=" + run.result().firstFailureCode());
            require(failingEvent.equals(run.result().firstFailureEventId()),
                    scenario + " firstFailureEventId=" + run.result().firstFailureEventId());
        }
    }

    // ================= RSC-08 =================

    private static void rsc08() throws Exception {
        for (int i = 0; i < VALID_SCENARIOS.size(); i++) {
            Path scenario = Path.of(VALID_SCENARIOS.get(i));
            Path a = produce("rsc08-" + (i + 1) + "-a", scenario);
            Path b = produce("rsc08-" + (i + 1) + "-b", scenario);
            for (String fileName : EvidenceBundleValidator.MANIFEST_FILES) {
                long mismatch = Files.mismatch(a.resolve(fileName), b.resolve(fileName));
                require(mismatch == -1, scenario + " 两次运行的 " + fileName
                        + " 不一致（首个差异字节=" + mismatch + "）");
            }
            ReplayResult replay = new ReplayComparator().compare(a, b);
            require(replay.isMatch(), scenario + " compare=" + replay.toLine());
        }
    }

    // ================= RSC-09 =================

    private static void rsc09() {
        ResourceWorldSnapshot goodWorld = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 1000, 1),
                reservation("APP-002", ReservationState.WAITING, 2000, 2)));
        ResourceStep goodStep = step("EVT-001", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", ResourceWorldSnapshot.empty(), goodWorld,
                new ReservationDecision(ReservationDecision.Outcome.HELD,
                        reservation("APP-001", ReservationState.HELD, 1000, 1)));
        RestartPair goodPair = new RestartPair("EVT-009", "a".repeat(64), "a".repeat(64));
        for (OracleResult result : ResourceInvariantOracle.check(
                goodWorld, List.of(goodStep), List.of(goodPair))) {
            require(result.status() == OracleResult.Status.PASS,
                    result.oracleId() + " 正例=" + result.status() + " " + result.actual());
        }
        // ORC-RES-SYS-006 的实际值形态必须固定。
        OracleResult restart = findResult(ResourceInvariantOracle.check(
                goodWorld, List.of(goodStep), List.of(goodPair)), "ORC-RES-SYS-006");
        require(restart.actual().matches("restartPairs=\\d+,mismatches=\\d+"),
                "ORC-RES-SYS-006 actual 形态=" + restart.actual());

        // 001：同一资源两个 HELD。
        ResourceWorldSnapshot doubleHeld = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 1000, 1),
                reservation("APP-002", ReservationState.HELD, 2000, 2)));
        expectFail(doubleHeld, List.of(new ResourceStep("EVT-001",
                        ScenarioEvent.Action.RESERVATION_REQUEST, "R01", "", doubleHeld, doubleHeld, null)),
                List.of(), "ORC-RES-SYS-001", "同一资源两个 HELD");

        // 002：晋升越过队首（APP-020 先于更小键的 APP-030 被晋升）。
        ResourceWorldSnapshot queued = snapshot(List.of(
                reservation("APP-030", ReservationState.WAITING, 2000, 2),
                reservation("APP-020", ReservationState.WAITING, 3000, 3)));
        ResourceWorldSnapshot skipped = snapshot(List.of(
                reservation("APP-030", ReservationState.WAITING, 2000, 2),
                reservation("APP-020", ReservationState.HELD, 3000, 3)));
        ResourceStep queuedStep = step("EVT-002", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", ResourceWorldSnapshot.empty(), queued, null);
        ResourceStep skippedStep = step("EVT-003", ScenarioEvent.Action.RESERVATION_RELEASE,
                "", "", queued, skipped, null);
        expectFail(skipped, List.of(queuedStep, skippedStep), List.of(),
                "ORC-RES-SYS-002", "晋升越过队首");

        // 003：RELEASED 之后变回 HELD。
        ResourceWorldSnapshot released = snapshot(List.of(
                reservation("APP-001", ReservationState.RELEASED, 1000, 1)));
        ResourceWorldSnapshot reopened = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 1000, 1)));
        ResourceStep releasedStep = step("EVT-004", ScenarioEvent.Action.RESERVATION_RELEASE,
                "", "", snapshot(List.of(
                        reservation("APP-001", ReservationState.HELD, 1000, 1))), released, null);
        ResourceStep reopenedStep = step("EVT-005", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", released, reopened, null);
        expectFail(reopened, List.of(releasedStep, reopenedStep), List.of(),
                "ORC-RES-SYS-003", "终态重开");

        // 004：IDEMPOTENT 决策前后排序键改变。
        ResourceWorldSnapshot heldOld = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 2000, 1)));
        ResourceWorldSnapshot heldMoved = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 3000, 9)));
        ResourceStep firstRequest = step("EVT-006", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", ResourceWorldSnapshot.empty(), heldOld,
                new ReservationDecision(ReservationDecision.Outcome.HELD,
                        reservation("APP-001", ReservationState.HELD, 2000, 1)));
        ResourceStep duplicate = step("EVT-007", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", heldOld, heldMoved,
                new ReservationDecision(ReservationDecision.Outcome.IDEMPOTENT,
                        reservation("APP-001", ReservationState.HELD, 2000, 1)));
        expectFail(heldMoved, List.of(firstRequest, duplicate), List.of(),
                "ORC-RES-SYS-004", "幂等请求重排");

        // 005：DISABLED 驱逐 holder。
        ResourceWorldSnapshot holder = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 1000, 1)));
        ResourceWorldSnapshot evicted = snapshot(List.of(
                reservation("APP-001", ReservationState.CANCELLED, 1000, 1)));
        ResourceStep disable = step("EVT-008", ScenarioEvent.Action.RESOURCE_SET,
                "R01", "DISABLED", holder, evicted, null);
        expectFail(evicted, List.of(disable), List.of(), "ORC-RES-SYS-005", "disable 驱逐 holder");

        // 006：重载前后摘要不一致。
        RestartPair badPair = new RestartPair("EVT-009", "b".repeat(64), "c".repeat(64));
        expectFail(goodWorld, List.of(goodStep), List.of(badPair),
                "ORC-RES-SYS-006", "重启摘要不一致");
    }

    // ================= RSC-10 =================

    private static void rsc10() {
        for (String scenario : BASE_SCENARIOS) {
            RuntimeRun run = runScenario(scenario);
            require("COMPLETED".equals(run.result().runStatus()),
                    scenario + " runStatus=" + run.result().runStatus());
            require(run.result().oracleFailCount() == 0,
                    scenario + " oracleFail=" + run.result().oracleFailCount());
            for (String oracleId : List.of("ORC-SYS-001", "ORC-SYS-002", "ORC-SYS-003",
                    "ORC-SYS-004", "ORC-SYS-005", "ORC-SYS-006")) {
                require(statusOf(run.result().oracleResults(), oracleId) == OracleResult.Status.PASS,
                        scenario + " " + oracleId + "=" + statusOf(run.result().oracleResults(), oracleId));
            }
        }
        CliResult backward = cli("validate", "scenarios/invalid/invalid-backward-time.scenario");
        require(backward.exitCode() == 2, "backward-time 退出码=" + backward.exitCode());
        require(backward.output().trim().startsWith("ERR SCENARIO_EVENT_ORDER"),
                "backward-time 输出=" + backward.output());
        CliResult duplicate = cli("validate", "scenarios/invalid/invalid-duplicate-event.scenario");
        require(duplicate.exitCode() == 2, "duplicate-event 退出码=" + duplicate.exitCode());
        require(duplicate.output().trim().startsWith("ERR SCENARIO_DUPLICATE_EVENT"),
                "duplicate-event 输出=" + duplicate.output());

        Path evidence = tmpDir.resolve("rsc10-unscripted");
        CliResult unscripted = cli("run", "scenarios/invalid/invalid-unscripted-call.scenario",
                evidence.toString());
        require(unscripted.exitCode() == 1, "unscripted 退出码=" + unscripted.exitCode());
        require(unscripted.output().contains("RUN_FAILED SIM-INVALID-03 UNSCRIPTED_EXTERNAL_CALL"),
                "unscripted 输出=" + unscripted.output());
        new EvidenceBundleValidator().validate(evidence);
    }

    // ================= 基础设施 =================

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

    private record RuntimeRun(ScenarioPackage pkg, SimulationRuntime runtime,
                              SimulationRunResult result) {
    }

    private static RuntimeRun runScenario(String scenario) {
        try {
            ScenarioPackage pkg = ScenarioParser.parse(Path.of(scenario));
            SimulationRuntime runtime = new SimulationRuntime(pkg);
            SimulationRunResult result = runtime.run();
            return new RuntimeRun(pkg, runtime, result);
        } catch (Exception e) {
            throw new AssertionError(scenario + " 运行失败：" + e);
        }
    }

    private static Path produce(String name, Path scenario) {
        try {
            ScenarioPackage pkg = ScenarioParser.parse(scenario);
            SimulationRuntime runtime = new SimulationRuntime(pkg);
            SimulationRunResult result = runtime.run();
            Path evidence = tmpDir.resolve(name);
            new EvidenceBundleWriter(scenario, pkg, result, runtime.timeline(),
                    result.oracleResults()).write(evidence);
            return evidence;
        } catch (Exception e) {
            throw new AssertionError(scenario + " 证据生成失败：" + e);
        }
    }

    private static Registry world() {
        Registry registry = new Registry(new MemoryStorage());
        registry.registerPoint("P01", "原料暂存点");
        registry.registerPoint("P02", "装配线接收点");
        registry.addDestination("D01", "一号目的地");
        registry.addServicePoint("SP01", "D01", "P01", "一号服务点");
        registry.addVirtualResource("R01", "SP01");
        return registry;
    }

    private static void addSecondTarget(Registry registry) {
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P01", "二号服务点");
        registry.addVirtualResource("R02", "SP02");
    }

    private static void application(Registry registry, String applicationId) {
        registry.registerApplication(applicationId, "P01", "P02");
    }

    private static ReservationDecision request(Registry registry, String applicationId,
                                               long offsetMillis, long logicalSequence) {
        return registry.requestReservation(applicationId, "D01", "SP01", "R01",
                offsetMillis, logicalSequence);
    }

    private static Reservation reservationOf(Registry registry, String applicationId) {
        for (Reservation reservation : registry.resourceWorldSnapshot().reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation;
            }
        }
        throw new AssertionError("缺少预约 app=" + applicationId);
    }

    private static ReservationState stateOf(Registry registry, String applicationId) {
        return reservationOf(registry, applicationId).state();
    }

    private static ReservationState stateOf(ResourceWorldSnapshot snapshot, String applicationId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation.state();
            }
        }
        throw new AssertionError("缺少预约 app=" + applicationId);
    }

    private static ResourceAvailability availabilityOf(Registry registry, String resourceId) {
        for (VirtualResource resource : registry.resourceWorldSnapshot().resources()) {
            if (resource.resourceId().equals(resourceId)) {
                return resource.availability();
            }
        }
        throw new AssertionError("缺少资源 " + resourceId);
    }

    private static List<String> waitingOrder(Registry registry, String resourceId) {
        List<String> order = new ArrayList<>();
        for (Reservation reservation : registry.resourceWorldSnapshot().reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                order.add(reservation.applicationId());
            }
        }
        return order;
    }

    private static OracleResult.Status statusOf(List<OracleResult> results, String oracleId) {
        for (OracleResult result : results) {
            if (result.oracleId().equals(oracleId)) {
                return result.status();
            }
        }
        throw new AssertionError("缺少 Oracle 结果：" + oracleId);
    }

    private static OracleResult findResult(List<OracleResult> results, String oracleId) {
        for (OracleResult result : results) {
            if (result.oracleId().equals(oracleId)) {
                return result;
            }
        }
        throw new AssertionError("缺少 Oracle 结果：" + oracleId);
    }

    private static void expectFail(ResourceWorldSnapshot world, List<ResourceStep> steps,
                                   List<RestartPair> pairs, String oracleId, String label) {
        OracleResult result = findResult(ResourceInvariantOracle.check(world, steps, pairs), oracleId);
        require(result.status() == OracleResult.Status.FAIL,
                label + "：应 FAIL，实际=" + result.status() + " actual=" + result.actual());
        require(result.detail() != null && !result.detail().isEmpty(),
                label + "：FAIL 必须带 detail");
    }

    private static ResourceWorldSnapshot snapshot(List<Reservation> reservations) {
        return new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P01", "一号服务点")),
                List.of(new VirtualResource("R01", "SP01")),
                reservations);
    }

    private static Reservation reservation(String applicationId, ReservationState state,
                                           long offsetMillis, long logicalSequence) {
        return new Reservation(Reservation.idFor(applicationId), applicationId,
                "D01", "SP01", "R01", state, offsetMillis, logicalSequence);
    }

    private static ResourceStep step(String eventId, ScenarioEvent.Action action, String resourceId,
                                     String value, ResourceWorldSnapshot before,
                                     ResourceWorldSnapshot after, ReservationDecision decision) {
        return new ResourceStep(eventId, action, resourceId, value, before, after, decision);
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取失败：" + file + " " + e);
        }
    }

    private record CliResult(int exitCode, String output) {
    }

    private static CliResult cli(String... args) {
        try {
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
        } catch (Exception e) {
            throw new AssertionError("CLI 失败：" + e);
        }
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
