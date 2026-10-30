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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
            "scenarios/resource/sim-102-contention-promotion.scenario",
            "scenarios/resource/sim-103-idempotent-request.scenario",
            "scenarios/resource/sim-104-conflicting-target.scenario",
            "scenarios/resource/sim-105-disable-enable.scenario",
            "scenarios/resource/sim-106-restart-recovery.scenario");

    private static final List<String> VALID_SCENARIO_IDS = List.of(
            "SIM-101", "SIM-102", "SIM-103", "SIM-104", "SIM-105", "SIM-106");

    /** 固定 8 项场景 EXPECT 的 ruleType（§12.2）。 */
    private static final List<String> FIXED_EXPECTS = List.of(
            "RESERVATION_STATE", "RESERVATION_DECISION", "RESOURCE_AVAILABILITY",
            "RESOURCE_HOLDER", "WAIT_QUEUE", "HELD_COUNT",
            "NO_DOUBLE_HOLD", "NO_ORPHAN_RESERVATION");

    /** 每个合法场景必须出现的 EXPECT 行（ruleType + 参数），逐字按任务书 §14。 */
    private static final Map<String, List<String>> REQUIRED_EXPECT = new LinkedHashMap<>();

    static {
        REQUIRED_EXPECT.put("SIM-101", List.of(
                "RESERVATION_STATE APP-101 RELEASED",
                "RESOURCE_HOLDER RES-01 NONE",
                "WAIT_QUEUE RES-01 NONE",
                "HELD_COUNT RES-01 0",
                "NO_ORPHAN_RESERVATION"));
        REQUIRED_EXPECT.put("SIM-102", List.of(
                "RESERVATION_STATE APP-102-A RELEASED",
                "RESERVATION_STATE APP-102-B HELD",
                "RESOURCE_HOLDER RES-01 APP-102-B",
                "WAIT_QUEUE RES-01 NONE",
                "HELD_COUNT RES-01 1",
                "NO_DOUBLE_HOLD APP-102-A PASS",
                "NO_DOUBLE_HOLD APP-102-B PASS",
                "NO_ORPHAN_RESERVATION"));
        REQUIRED_EXPECT.put("SIM-103", List.of(
                "RESERVATION_STATE APP-103 HELD",
                "RESERVATION_DECISION EVT-008 IDEMPOTENT",
                "RESOURCE_HOLDER RES-01 APP-103",
                "HELD_COUNT RES-01 1",
                "NO_DOUBLE_HOLD APP-103 PASS",
                "NO_ORPHAN_RESERVATION"));
        REQUIRED_EXPECT.put("SIM-104", List.of(
                "RESERVATION_DECISION EVT-011 CONFLICT",
                "RESERVATION_STATE APP-104 HELD",
                "RESOURCE_HOLDER RES-01 APP-104",
                "RESOURCE_HOLDER RES-02 NONE",
                "HELD_COUNT RES-01 1",
                "HELD_COUNT RES-02 0",
                "NO_ORPHAN_RESERVATION"));
        REQUIRED_EXPECT.put("SIM-105", List.of(
                "RESOURCE_AVAILABILITY RES-01 DISABLED",
                "RESERVATION_STATE APP-105 RELEASED",
                "RESOURCE_HOLDER RES-01 NONE",
                "WAIT_QUEUE RES-01 NONE",
                "HELD_COUNT RES-01 0",
                "RESERVATION_DECISION EVT-008 WAITING",
                "NO_ORPHAN_RESERVATION"));
        REQUIRED_EXPECT.put("SIM-106", List.of(
                "RESERVATION_STATE APP-106-A RELEASED",
                "RESERVATION_STATE APP-106-B HELD",
                "RESOURCE_HOLDER RES-01 APP-106-B",
                "WAIT_QUEUE RES-01 NONE",
                "HELD_COUNT RES-01 1",
                "NO_ORPHAN_RESERVATION"));
    }

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
            "同申请不同目标 → CONFLICT 决策（不抛异常），状态与规范快照逐字不变";
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
        for (int i = 0; i < VALID_SCENARIOS.size(); i++) {
            String scenario = VALID_SCENARIOS.get(i);
            String scenarioId = VALID_SCENARIO_IDS.get(i);
            String text = readText(scenario);
            require(text.startsWith("SCENARIO " + scenarioId + " "),
                    scenario + " 头部 scenarioId 不符：" + firstLine(text));
            for (String required : REQUIRED_EXPECT.get(scenarioId)) {
                require(hasExpect(text, required),
                        scenario + " 缺少 EXPECT " + required);
            }
            CliResult validate = cli("validate", scenario);
            require(validate.exitCode() == 0,
                    scenario + " validate exit=" + validate.exitCode() + " 输出=" + validate.output());
            Path evidence = tmpDir.resolve("valid-" + scenarioId);
            CliResult run = cli("run", scenario, evidence.toString());
            require(run.exitCode() == 0, scenario + " run exit=" + run.exitCode()
                    + " 输出=" + run.output());
            require(run.output().contains("RUN_COMPLETED " + scenarioId),
                    scenario + " 缺少 RUN_COMPLETED，" + run.output());
            require(run.output().contains("EVIDENCE_WRITTEN " + evidence),
                    scenario + " 缺少 EVIDENCE_WRITTEN，" + run.output());
            new EvidenceBundleValidator().validate(evidence);
            require("COMPLETED".equals(summaryValue(evidence, "runStatus")),
                    scenario + " runStatus=" + summaryValue(evidence, "runStatus"));
            require("0".equals(summaryValue(evidence, "realExternalWriteCount")),
                    scenario + " realExternalWriteCount="
                            + summaryValue(evidence, "realExternalWriteCount"));
            require(scenarioId.equals(summaryValue(evidence, "scenarioId")),
                    scenario + " summary scenarioId=" + summaryValue(evidence, "scenarioId"));

            int expects = 0;
            for (String[] row : tsvRows(evidence.resolve("oracle-results.tsv"))) {
                String oracleId = row[0];
                String ruleType = row[1];
                String status = row[2];
                if (RESOURCE_INVARIANTS.contains(oracleId)) {
                    require("PASS".equals(status),
                            scenario + " " + oracleId + "=" + status);
                }
                if (FIXED_EXPECTS.contains(ruleType)) {
                    expects++;
                    require("PASS".equals(status),
                            scenario + " " + oracleId + "(" + ruleType + ")=" + status
                                    + " actual=" + row[4]);
                }
            }
            require(expects > 0, scenario + " 没有固定 EXPECT 结果");
        }
    }

    // ================= RSC-02 =================

    private static void rsc02() {
        RuntimeRun run = runScenario(VALID_SCENARIOS.get(1));
        require("COMPLETED".equals(run.result().runStatus()),
                "SIM-102 runStatus=" + run.result().runStatus());
        ResourceWorldSnapshot world = run.runtime().resourceWorldSnapshot();
        require(stateOf(world, "APP-102-A") == ReservationState.RELEASED,
                "APP-102-A=" + stateOf(world, "APP-102-A"));
        require(stateOf(world, "APP-102-B") == ReservationState.HELD,
                "APP-102-B=" + stateOf(world, "APP-102-B"));
        require("APP-102-B".equals(holderOf(world, "RES-01")),
                "holder=" + holderOf(world, "RES-01"));
        require(waitingOf(world, "RES-01").isEmpty(),
                "queue=" + waitingOf(world, "RES-01"));
        Reservation a = reservationOf(world, "APP-102-A");
        Reservation b = reservationOf(world, "APP-102-B");
        require(a.requestedOffsetMillis() == b.requestedOffsetMillis(),
                "offset 不同：A=" + a.requestedOffsetMillis() + " B=" + b.requestedOffsetMillis());
        require(a.requestedLogicalSequence() < b.requestedLogicalSequence(),
                "A 的 logicalSequence 必须更小：A=" + a.requestedLogicalSequence()
                        + " B=" + b.requestedLogicalSequence());
        require(decisionType(run, "EVT-008") == ReservationDecision.DecisionType.HELD,
                "A 首次请求 type=" + decisionType(run, "EVT-008"));
        require(decisionType(run, "EVT-009") == ReservationDecision.DecisionType.WAITING,
                "B 首次请求 type=" + decisionType(run, "EVT-009"));
    }

    // ================= RSC-03 =================

    private static void rsc03() {
        RuntimeRun run = runScenario(VALID_SCENARIOS.get(2));
        require("COMPLETED".equals(run.result().runStatus()),
                "SIM-103 runStatus=" + run.result().runStatus());
        require(decisionType(run, "EVT-008") == ReservationDecision.DecisionType.IDEMPOTENT,
                "SIM-103 EVT-008 type=" + decisionType(run, "EVT-008"));
        ResourceStep duplicate = stepOf(run.runtime().resourceSteps(), "EVT-008");
        require(duplicate != null, "SIM-103 缺少 EVT-008 的 ResourceStep");
        require(duplicate.decision() != null
                        && duplicate.decision().type() == ReservationDecision.DecisionType.IDEMPOTENT,
                "EVT-008 decision=" + (duplicate.decision() == null
                        ? "null" : duplicate.decision().type()));
        require(duplicate.before().canonicalText().equals(duplicate.after().canonicalText()),
                "第二次请求改变了规范快照");
        List<Reservation> entries = reservationsOf(run.runtime().resourceWorldSnapshot(), "APP-103");
        require(entries.size() == 1, "APP-103 条目数=" + entries.size());
        require(entries.get(0).state() == ReservationState.HELD,
                "APP-103 state=" + entries.get(0).state());
        require(entries.get(0).requestedOffsetMillis() == 2000
                        && entries.get(0).requestedLogicalSequence() == 70,
                "排序键被改动：offset=" + entries.get(0).requestedOffsetMillis()
                        + " seq=" + entries.get(0).requestedLogicalSequence());
    }

    // ================= RSC-04 =================

    private static void rsc04() {
        RuntimeRun run = runScenario(VALID_SCENARIOS.get(3));
        require("COMPLETED".equals(run.result().runStatus()),
                "SIM-104 runStatus=" + run.result().runStatus());
        require(decisionType(run, "EVT-011") == ReservationDecision.DecisionType.CONFLICT,
                "SIM-104 EVT-011 type=" + decisionType(run, "EVT-011"));
        ResourceWorldSnapshot world = run.runtime().resourceWorldSnapshot();
        List<Reservation> entries = reservationsOf(world, "APP-104");
        require(entries.size() == 1, "APP-104 条目数=" + entries.size());
        require("RES-01".equals(entries.get(0).resourceId()),
                "APP-104 绑定资源=" + entries.get(0).resourceId());
        require(entries.get(0).state() == ReservationState.HELD,
                "APP-104 state=" + entries.get(0).state());
        require(holderOf(world, "RES-02") == null,
                "RES-02 holder=" + holderOf(world, "RES-02"));
        require(holderOf(world, "RES-01") != null
                        && holderOf(world, "RES-01").equals("APP-104"),
                "RES-01 holder=" + holderOf(world, "RES-01"));
    }

    // ================= RSC-05 =================

    private static void rsc05() {
        RuntimeRun run = runScenario(VALID_SCENARIOS.get(4));
        require("COMPLETED".equals(run.result().runStatus()),
                "SIM-105 runStatus=" + run.result().runStatus());
        ResourceWorldSnapshot world = run.runtime().resourceWorldSnapshot();
        require(availabilityOf(world, "RES-01") == ResourceAvailability.DISABLED,
                "RES-01 availability=" + availabilityOf(world, "RES-01"));
        require(stateOf(world, "APP-105") == ReservationState.RELEASED,
                "APP-105=" + stateOf(world, "APP-105"));
        require(holderOf(world, "RES-01") == null,
                "holder=" + holderOf(world, "RES-01"));
        require(waitingOf(world, "RES-01").isEmpty(),
                "queue=" + waitingOf(world, "RES-01"));
        require(decisionType(run, "EVT-008") == ReservationDecision.DecisionType.WAITING,
                "DISABLED 期间请求 type=" + decisionType(run, "EVT-008"));
        ResourceStep enable = stepOf(run.runtime().resourceSteps(), "EVT-009");
        require(enable != null, "缺少 EVT-009 ResourceStep");
        require(stateOf(enable.after(), "APP-105") == ReservationState.HELD,
                "ENABLED 后 APP-105=" + stateOf(enable.after(), "APP-105"));
        ResourceStep disableAgain = stepOf(run.runtime().resourceSteps(), "EVT-010");
        require(disableAgain != null, "缺少 EVT-010 ResourceStep");
        require(stateOf(disableAgain.after(), "APP-105") == ReservationState.HELD,
                "再次 DISABLED 驱逐了 holder：APP-105="
                        + stateOf(disableAgain.after(), "APP-105"));
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
        ResourceWorldSnapshot world = run.runtime().resourceWorldSnapshot();
        require(stateOf(world, "APP-106-A") == ReservationState.RELEASED,
                "APP-106-A=" + stateOf(world, "APP-106-A"));
        require(stateOf(world, "APP-106-B") == ReservationState.HELD,
                "APP-106-B=" + stateOf(world, "APP-106-B"));
        require("APP-106-B".equals(holderOf(world, "RES-01")),
                "holder=" + holderOf(world, "RES-01"));
        OracleResult restart = findResult(run.result().oracleResults(), "ORC-RES-SYS-006");
        require(restart.status() == OracleResult.Status.PASS,
                "ORC-RES-SYS-006=" + restart.status());
        require(restart.actual().matches("restartPairs=2,mismatches=0"),
                "ORC-RES-SYS-006 actual=" + restart.actual());
    }

    // ================= RSC-07 =================

    private static void rsc07() {
        String[][] invalid = {
                {"scenarios/resource/invalid/missing-destination.scenario",
                        "SIM-INVALID-101", "RESOURCE_DESTINATION_NOT_FOUND"},
                {"scenarios/resource/invalid/target-mismatch.scenario",
                        "SIM-INVALID-102", "RESOURCE_TARGET_MISMATCH"},
                {"scenarios/resource/invalid/duplicate-conflicting-id.scenario",
                        "SIM-INVALID-103", "RESOURCE_DUPLICATE_CONFLICT"},
                {"scenarios/resource/invalid/release-non-holder.scenario",
                        "SIM-INVALID-104", "RESERVATION_NOT_HELD"},
                {"scenarios/resource/invalid/terminal-reopen.scenario",
                        "SIM-INVALID-105", "RESERVATION_TERMINAL"}};
        require(invalid.length == 5, "非法场景数=" + invalid.length);
        for (String[] item : invalid) {
            String scenario = item[0];
            String scenarioId = item[1];
            String code = item[2];
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
            require("FAILED".equals(summaryValue(evidence, "runStatus")),
                    scenario + " runStatus=" + summaryValue(evidence, "runStatus"));
            require("0".equals(summaryValue(evidence, "realExternalWriteCount")),
                    scenario + " realExternalWriteCount="
                            + summaryValue(evidence, "realExternalWriteCount"));

            RuntimeRun run = runScenario(scenario);
            require(code.equals(run.result().firstFailureCode()),
                    scenario + " firstFailureCode=" + run.result().firstFailureCode());
            String failingEvent = run.result().firstFailureEventId();
            require(failingEvent != null, scenario + " firstFailureEventId=null");

            List<String> timeline = readLines(evidence.resolve("event-timeline.tsv"));
            boolean sawFailure = false;
            int skipped = 0;
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
                    skipped++;
                    require(status.equals("EVENT_SKIPPED"),
                            scenario + " 首错后 " + eventId + "=" + status + "，期望 EVENT_SKIPPED");
                }
            }
            require(sawFailure, scenario + " 时间线未出现失败事件 " + failingEvent);
            require(skipped > 0, scenario + " 首错后没有 EVENT_SKIPPED 事件");

            // 保留最后已提交快照：场景 EXPECT 必须对保留快照求值并 PASS；六条系统 Oracle 继续执行。
            int expects = 0;
            for (String[] row : tsvRows(evidence.resolve("oracle-results.tsv"))) {
                String oracleId = row[0];
                String ruleType = row[1];
                String status = row[2];
                if (RESOURCE_INVARIANTS.contains(oracleId)) {
                    require("PASS".equals(status),
                            scenario + " " + oracleId + "=" + status + " actual=" + row[4]);
                }
                if (FIXED_EXPECTS.contains(ruleType)) {
                    expects++;
                    require("PASS".equals(status),
                            scenario + " " + oracleId + "(" + ruleType + ")=" + status
                                    + " actual=" + row[4]);
                }
            }
            require(expects > 0, scenario + " 没有保留快照 EXPECT 结果");
        }
    }

    // ================= RSC-08 =================

    private static void rsc08() throws Exception {
        for (int i = 0; i < VALID_SCENARIOS.size(); i++) {
            String scenario = VALID_SCENARIOS.get(i);
            Path a = tmpDir.resolve("rsc08-" + (i + 1) + "-a");
            Path b = tmpDir.resolve("rsc08-" + (i + 1) + "-b");
            for (Path dir : List.of(a, b)) {
                CliResult cli = cli("run", scenario, dir.toString());
                require(cli.exitCode() == 0, scenario + " run exit=" + cli.exitCode()
                        + " 输出=" + cli.output());
                require(cli.output().contains("EVIDENCE_WRITTEN " + dir),
                        scenario + " 缺少 EVIDENCE_WRITTEN，" + cli.output());
                new EvidenceBundleValidator().validate(dir);
                require("COMPLETED".equals(summaryValue(dir, "runStatus")),
                        scenario + " " + dir + " runStatus=" + summaryValue(dir, "runStatus"));
                require("0".equals(summaryValue(dir, "realExternalWriteCount")),
                        scenario + " " + dir + " realExternalWriteCount="
                                + summaryValue(dir, "realExternalWriteCount"));
            }
            for (String fileName : EvidenceBundleValidator.MANIFEST_FILES) {
                long mismatch = Files.mismatch(a.resolve(fileName), b.resolve(fileName));
                require(mismatch == -1, scenario + " 两次运行的 " + fileName
                        + " 不一致（首个差异字节=" + mismatch + "）");
            }
            for (String fileName : List.of("manifest.sha256", "scenario.sha256")) {
                long mismatch = Files.mismatch(a.resolve(fileName), b.resolve(fileName));
                require(mismatch == -1, scenario + " 两次运行的 " + fileName
                        + " 不一致（首个差异字节=" + mismatch + "）");
            }
            List<String> manifest = readLines(a.resolve("manifest.sha256"));
            require(manifest.size() == 6, scenario + " manifest 行数=" + manifest.size());
            List<String> names = new ArrayList<>();
            for (String line : manifest) {
                names.add(line.substring(line.indexOf("  ") + 2));
            }
            require(names.equals(EvidenceBundleValidator.MANIFEST_FILES),
                    scenario + " manifest 文件集/顺序=" + names);
            require(summaryValue(a, "scenarioId").equals(summaryValue(b, "scenarioId")),
                    scenario + " 两跑 scenarioId 不一致");
            require(summaryValue(a, "scenarioVersion").equals("1"),
                    scenario + " scenarioVersion=" + summaryValue(a, "scenarioVersion"));
            require(summaryValue(a, "scenarioDigest").equals(summaryValue(b, "scenarioDigest")),
                    scenario + " 两跑 scenarioDigest 不一致");
            for (String field : List.of("schemaVersion", "runStatus", "plannedEventCount",
                    "executedEventCount", "eventPassCount", "eventFailCount",
                    "eventSkippedCount", "firstFailureEventId", "firstFailureCode",
                    "realExternalWriteCount")) {
                require(summaryValue(a, field) != null, scenario + " 缺少字段 " + field);
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
                new ReservationDecision(ReservationDecision.DecisionType.HELD,
                        reservation("APP-001", ReservationState.HELD, 1000, 1),
                        "reason=RESOURCE_AVAILABLE"));
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

        // 002：同一 application 两条非终态 Reservation。
        ResourceWorldSnapshot doubleActive = snapshot(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                        ReservationState.HELD, 2000, 1),
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                        ReservationState.WAITING, 3000, 2)));
        expectFail(doubleActive, List.of(), List.of(),
                "ORC-RES-SYS-002", "同一 application 两条非终态");

        // 003：引用链悬空（reservation 指向不存在的 resource）。
        ResourceWorldSnapshot dangling = new ResourceWorldSnapshot(
                List.of(new Destination("D01", "一号目的地")),
                List.of(new ServicePoint("SP01", "D01", "P01")),
                List.of(new VirtualResource("R01", "SP01")),
                List.of(reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R99",
                        ReservationState.HELD, 2000, 1)));
        expectFail(dangling, List.of(), List.of(),
                "ORC-RES-SYS-003", "引用链悬空");

        // 004：WAITING 队列出现重复 applicationId。
        ResourceWorldSnapshot duplicateQueue = snapshot(List.of(
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                        ReservationState.WAITING, 2000, 1),
                reservation("RSV-APP-001", "APP-001", "D01", "SP01", "R01",
                        ReservationState.WAITING, 3000, 2)));
        expectFail(duplicateQueue, List.of(), List.of(),
                "ORC-RES-SYS-004", "WAITING 重复 applicationId");

        // 005：RELEASED 之后变回 HELD。
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
                "ORC-RES-SYS-005", "终态重开");

        // ORC-RES-EXT-001：IDEMPOTENT 决策前后排序键改变。
        ResourceWorldSnapshot heldOld = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 2000, 1)));
        ResourceWorldSnapshot heldMoved = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 3000, 9)));
        ResourceStep firstRequest = step("EVT-006", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", ResourceWorldSnapshot.empty(), heldOld,
                new ReservationDecision(ReservationDecision.DecisionType.HELD,
                        reservation("APP-001", ReservationState.HELD, 2000, 1),
                        "reason=RESOURCE_AVAILABLE"));
        ResourceStep duplicate = step("EVT-007", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", heldOld, heldMoved,
                new ReservationDecision(ReservationDecision.DecisionType.IDEMPOTENT,
                        reservation("APP-001", ReservationState.HELD, 2000, 1),
                        "reason=SAME_ACTIVE_RESERVATION"));
        expectFail(heldMoved, List.of(firstRequest, duplicate), List.of(),
                "ORC-RES-EXT-001", "幂等请求重排");

        // ORC-RES-EXT-002：DISABLED 驱逐 holder。
        ResourceWorldSnapshot holder = snapshot(List.of(
                reservation("APP-001", ReservationState.HELD, 1000, 1)));
        ResourceWorldSnapshot evicted = snapshot(List.of(
                reservation("APP-001", ReservationState.CANCELLED, 1000, 1)));
        ResourceStep disable = step("EVT-008", ScenarioEvent.Action.RESOURCE_SET,
                "R01", "DISABLED", holder, evicted, null);
        expectFail(evicted, List.of(disable), List.of(),
                "ORC-RES-EXT-002", "disable 驱逐 holder");

        // ORC-RES-EXT-003：晋升越过队首。
        ResourceWorldSnapshot queued = snapshot(List.of(
                reservation("APP-030", ReservationState.WAITING, 2000, 2),
                reservation("APP-020", ReservationState.WAITING, 3000, 3)));
        ResourceWorldSnapshot skipped = snapshot(List.of(
                reservation("APP-030", ReservationState.WAITING, 2000, 2),
                reservation("APP-020", ReservationState.HELD, 3000, 3)));
        ResourceStep queuedStep = step("EVT-010", ScenarioEvent.Action.RESERVATION_REQUEST,
                "R01", "", ResourceWorldSnapshot.empty(), queued, null);
        ResourceStep skippedStep = step("EVT-011", ScenarioEvent.Action.RESERVATION_RELEASE,
                "", "", queued, skipped, null);
        expectFail(skipped, List.of(queuedStep, skippedStep), List.of(),
                "ORC-RES-EXT-003", "晋升越过队首");

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

    // ================= 证据/场景读取辅助 =================

    private static String readText(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("读取失败：" + file + " " + e);
        }
    }

    private static String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index);
    }

    private static boolean hasExpect(String text, String required) {
        return Pattern.compile("(?m)^EXPECT \\S+ " + Pattern.quote(required) + "\\s*$")
                .matcher(text).find();
    }

    private static String summaryValue(Path evidenceDir, String key) {
        for (String line : readLines(evidenceDir.resolve("run-summary.txt"))) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static List<String[]> tsvRows(Path file) {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = readLines(file);
        for (int i = 1; i < lines.size(); i++) {
            rows.add(lines.get(i).split("\t", -1));
        }
        return rows;
    }

    private static List<Reservation> reservationsOf(ResourceWorldSnapshot world, String applicationId) {
        List<Reservation> matches = new ArrayList<>();
        for (Reservation reservation : world.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                matches.add(reservation);
            }
        }
        return matches;
    }

    private static Reservation reservationOf(ResourceWorldSnapshot world, String applicationId) {
        List<Reservation> matches = reservationsOf(world, applicationId);
        if (matches.size() != 1) {
            throw new AssertionError("预约条目数=" + matches.size() + " app=" + applicationId);
        }
        return matches.get(0);
    }

    private static String holderOf(ResourceWorldSnapshot world, String resourceId) {
        for (Reservation reservation : world.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.HELD) {
                return reservation.applicationId();
            }
        }
        return null;
    }

    private static List<String> waitingOf(ResourceWorldSnapshot world, String resourceId) {
        List<String> queue = new ArrayList<>();
        for (Reservation reservation : world.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                queue.add(reservation.applicationId());
            }
        }
        return queue;
    }

    private static ResourceAvailability availabilityOf(ResourceWorldSnapshot world, String resourceId) {
        for (VirtualResource resource : world.resources()) {
            if (resource.resourceId().equals(resourceId)) {
                return resource.availability();
            }
        }
        return null;
    }

    private static ResourceStep stepOf(List<ResourceStep> steps, String eventId) {
        for (ResourceStep step : steps) {
            if (step.eventId().equals(eventId)) {
                return step;
            }
        }
        return null;
    }

    private static ReservationDecision.DecisionType decisionType(RuntimeRun run, String eventId) {
        ReservationDecision decision = run.runtime().reservationDecisions().get(eventId);
        return decision == null ? null : decision.type();
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
        registry.addServicePoint("SP01", "D01", "P01");
        registry.addVirtualResource("R01", "SP01");
        return registry;
    }

    private static void addSecondTarget(Registry registry) {
        registry.addDestination("D02", "二号目的地");
        registry.addServicePoint("SP02", "D02", "P01");
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
                List.of(new ServicePoint("SP01", "D01", "P01")),
                List.of(new VirtualResource("R01", "SP01")),
                reservations);
    }

    private static Reservation reservation(String applicationId, ReservationState state,
                                           long offsetMillis, long logicalSequence) {
        return new Reservation(Reservation.idFor(applicationId), applicationId,
                "D01", "SP01", "R01", state, offsetMillis, logicalSequence);
    }

    private static Reservation reservation(String reservationId, String applicationId,
                                           String destinationId, String servicePointId,
                                           String resourceId, ReservationState state,
                                           long offsetMillis, long logicalSequence) {
        return new Reservation(reservationId, applicationId, destinationId, servicePointId,
                resourceId, state, offsetMillis, logicalSequence);
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
