package lab.simulation;

import lab.ApplicationState;
import lab.OutboxEntry;
import lab.OutboxStatus;
import lab.TransferApplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不变量检查器（只读）。
 *
 * <p>检查两类规则：
 * <ol>
 *   <li>六条系统不变量 ORC-SYS-001..006 —— 全部按 {@link OracleContext} 的事实真实计算，
 *       任何一条都不能被场景关闭，也不允许硬编码 PASS</li>
 *   <li>场景 EXPECT（自定义 oracleId）</li>
 * </ol>
 *
 * <p>本类不引用 {@link SimulationRuntime}，不修改任何业务对象。
 */
public class InvariantOracle {

    private static final String EVIDENCE_LEVEL = "LOCAL_SIMULATION";
    private static final String PORT_TYPE = "ScriptedFakeRcsAdapter";

    private final ScenarioPackage scenario;

    public InvariantOracle(ScenarioPackage scenario) {
        this.scenario = scenario;
    }

    /**
     * 执行全部检查：六条系统不变量 + 场景 EXPECT，结果按 oracleId 排序。
     */
    public List<OracleResult> check(OracleContext ctx) {
        List<OracleResult> results = new ArrayList<>();
        results.addAll(checkSystemInvariants(ctx));
        results.addAll(checkScenarioExpects(ctx));
        results.sort((a, b) -> a.oracleId().compareTo(b.oracleId()));
        return List.copyOf(results);
    }

    // ---------- 六条系统不变量 ----------

    private List<OracleResult> checkSystemInvariants(OracleContext ctx) {
        List<OracleResult> list = new ArrayList<>();
        list.add(checkScriptedExternalCalls(ctx));
        list.add(checkSingleExternalOrder(ctx));
        list.add(checkUnknownQueryBeforeRetry(ctx));
        list.add(checkTerminalMonotonic(ctx));
        list.add(checkEvidenceLevel(ctx));
        list.add(checkNoRealExternalWrite(ctx));
        return list;
    }

    /**
     * ORC-SYS-001：所有外部调用都必须有脚本。
     */
    private OracleResult checkScriptedExternalCalls(OracleContext ctx) {
        for (ExternalCallAttempt attempt : ctx.externalCallAttempts()) {
            if (!attempt.scripted()) {
                return OracleResult.fail("ORC-SYS-001", "SCRIPTED_EXTERNAL_CALLS", "true", "false",
                        "UNSCRIPTED " + attempt.operation()
                                + " ordinal=" + attempt.ordinal()
                                + " app=" + attempt.applicationId());
            }
        }
        return OracleResult.pass("ORC-SYS-001", "SCRIPTED_EXTERNAL_CALLS", "true", "true");
    }

    /**
     * ORC-SYS-002：同一申请最多一个有效外单号。
     *
     * <p>汇总 CONFIRMED_CREATED 与 OBSERVED_EXISTING 两种确定性下的 externalOrderId 集合。
     */
    private OracleResult checkSingleExternalOrder(OracleContext ctx) {
        Map<String, Set<String>> ordersByApp = new HashMap<>();
        for (ExternalInteraction interaction : ctx.interactions()) {
            if (isConfirmed(interaction.sideEffectCertainty())) {
                ordersByApp.computeIfAbsent(interaction.applicationId(), key -> new HashSet<>())
                        .add(interaction.externalOrderId());
            }
        }
        for (Map.Entry<String, Set<String>> entry : ordersByApp.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> ids = new ArrayList<>(entry.getValue());
                ids.sort(String::compareTo);
                return OracleResult.fail("ORC-SYS-002", "NO_DUPLICATE_EXTERNAL_ORDER", "<=1",
                        String.valueOf(entry.getValue().size()),
                        "app=" + entry.getKey() + " orders=" + ids);
            }
        }
        return OracleResult.pass("ORC-SYS-002", "NO_DUPLICATE_EXTERNAL_ORDER", "<=1", "<=1");
    }

    /**
     * ORC-SYS-003：UNKNOWN / UNCERTAIN 之后的下一次外部动作必须是 QUERY。
     */
    private OracleResult checkUnknownQueryBeforeRetry(OracleContext ctx) {
        for (StateTransition transition : ctx.stateTransitions()) {
            boolean uncertain = transition.applicationState() == ApplicationState.UNKNOWN
                    || transition.outboxStatus() == OutboxStatus.UNCERTAIN;
            if (!uncertain) {
                continue;
            }
            ExternalCallAttempt next = nextAttemptAfter(ctx, transition);
            if (next != null && next.operation() == ExternalScript.Operation.CREATE) {
                return OracleResult.fail("ORC-SYS-003", "UNKNOWN_QUERY_BEFORE_RETRY", "QUERY", "CREATE",
                        "app=" + transition.applicationId()
                                + " after seq=" + transition.sequence()
                                + " eventId=" + transition.eventId());
            }
        }
        return OracleResult.pass("ORC-SYS-003", "UNKNOWN_QUERY_BEFORE_RETRY", "QUERY", "QUERY");
    }

    private ExternalCallAttempt nextAttemptAfter(OracleContext ctx, StateTransition transition) {
        ExternalCallAttempt next = null;
        for (ExternalCallAttempt attempt : ctx.externalCallAttempts()) {
            if (attempt.sequence() <= transition.sequence()) {
                continue;
            }
            if (!attempt.applicationId().equals(transition.applicationId())) {
                continue;
            }
            if (next == null || attempt.sequence() < next.sequence()) {
                next = attempt;
            }
        }
        return next;
    }

    /**
     * ORC-SYS-004：进入 COMPLETED / CANCELLED 之后不得再变。
     */
    private OracleResult checkTerminalMonotonic(OracleContext ctx) {
        Map<String, ApplicationState> terminal = new HashMap<>();
        for (StateTransition transition : ctx.stateTransitions()) {
            ApplicationState state = transition.applicationState();
            if (state == null) {
                continue;
            }
            ApplicationState known = terminal.get(transition.applicationId());
            if (known != null) {
                if (state != known) {
                    return OracleResult.fail("ORC-SYS-004", "TERMINAL_MONOTONIC", known.name(), state.name(),
                            "app=" + transition.applicationId()
                                    + " at seq=" + transition.sequence());
                }
                continue;
            }
            if (state.isTerminal()) {
                terminal.put(transition.applicationId(), state);
            }
        }
        return OracleResult.pass("ORC-SYS-004", "TERMINAL_MONOTONIC", "terminal-once", "terminal-once");
    }

    /**
     * ORC-SYS-005：Evidence 等级必须精确等于 LOCAL_SIMULATION。
     */
    private OracleResult checkEvidenceLevel(OracleContext ctx) {
        String actual = ctx.evidenceLevel();
        return EVIDENCE_LEVEL.equals(actual)
                ? OracleResult.pass("ORC-SYS-005", "EVIDENCE_LEVEL", EVIDENCE_LEVEL, actual)
                : OracleResult.fail("ORC-SYS-005", "EVIDENCE_LEVEL", EVIDENCE_LEVEL,
                        actual == null ? "null" : actual, "evidence level escalated");
    }

    /**
     * ORC-SYS-006：外部执行端口必须是 ScriptedFakeRcsAdapter，且真实外部写为 0。
     */
    private OracleResult checkNoRealExternalWrite(OracleContext ctx) {
        boolean portOk = PORT_TYPE.equals(ctx.executionPortType());
        boolean writeOk = ctx.realExternalWriteCount() == 0;
        String actual = "port=" + ctx.executionPortType()
                + " realExternalWriteCount=" + ctx.realExternalWriteCount();
        if (portOk && writeOk) {
            return OracleResult.pass("ORC-SYS-006", "NO_REAL_EXTERNAL_WRITE", "port=" + PORT_TYPE
                    + " realExternalWriteCount=0", actual);
        }
        return OracleResult.fail("ORC-SYS-006", "NO_REAL_EXTERNAL_WRITE",
                "port=" + PORT_TYPE + " realExternalWriteCount=0", actual, "real external write risk");
    }

    private boolean isConfirmed(String certainty) {
        return ExternalInteraction.CONFIRMED_CREATED.equals(certainty)
                || ExternalInteraction.OBSERVED_EXISTING.equals(certainty);
    }

    // ---------- 场景 EXPECT ----------

    private List<OracleResult> checkScenarioExpects(OracleContext ctx) {
        List<OracleResult> list = new ArrayList<>();
        for (OracleRule rule : scenario.oracles()) {
            switch (rule.ruleType()) {
                case APPLICATION_STATE -> list.add(checkApplicationState(rule, ctx));
                case OUTBOX_STATUS -> list.add(checkOutboxStatus(rule, ctx));
                case CREATE_CALLS -> list.add(checkCreateCalls(rule, ctx));
                case QUERY_CALLS -> list.add(checkQueryCalls(rule, ctx));
                case VALID_EXTERNAL_ORDERS -> list.add(checkValidExternalOrders(rule, ctx));
                case NO_DUPLICATE_EXTERNAL_ORDER -> list.add(checkNoDuplicateRule(rule, ctx));
                case RESERVATION_STATE, HELD_COUNT, WAITING_COUNT, RESOURCE_AVAILABILITY,
                     QUEUE_ORDER, QUEUE_EMPTY, WORLD_RESTART_CONSISTENT, SINGLE_CAPACITY ->
                        list.add(checkResourceRuleUnavailable(rule));
            }
        }
        return list;
    }

    private OracleResult checkApplicationState(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        String actual = "MISSING";
        for (TransferApplication app : ctx.applications()) {
            if (app.applicationId().equals(appId)) {
                actual = app.state().name();
                break;
            }
        }
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "APPLICATION_STATE", want, actual)
                : OracleResult.fail(rule.oracleId(), "APPLICATION_STATE", want, actual, "");
    }

    /**
     * OUTBOX_STATUS 不使用 findFirst：
     * 0 条 → MISSING；>1 条 → DUPLICATE_OUTBOX；恰 1 条才比对。
     */
    private OracleResult checkOutboxStatus(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        List<OutboxEntry> matches = new ArrayList<>();
        for (OutboxEntry entry : ctx.outbox()) {
            if (entry.applicationId().equals(appId)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            return OracleResult.fail(rule.oracleId(), "OUTBOX_STATUS", want, "MISSING", "no outbox entry");
        }
        if (matches.size() > 1) {
            return OracleResult.fail(rule.oracleId(), "OUTBOX_STATUS", want, "DUPLICATE_OUTBOX",
                    "entries=" + matches.size());
        }
        String actual = matches.get(0).status().name();
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "OUTBOX_STATUS", want, actual)
                : OracleResult.fail(rule.oracleId(), "OUTBOX_STATUS", want, actual, "");
    }

    private OracleResult checkCreateCalls(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = 0;
        for (ExternalInteraction interaction : ctx.interactions()) {
            if (interaction.applicationId().equals(appId)
                    && interaction.operation() == ExternalScript.Operation.CREATE) {
                actual++;
            }
        }
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "CREATE_CALLS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "CREATE_CALLS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    private OracleResult checkQueryCalls(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = 0;
        for (ExternalInteraction interaction : ctx.interactions()) {
            if (interaction.applicationId().equals(appId)
                    && interaction.operation() == ExternalScript.Operation.QUERY) {
                actual++;
            }
        }
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "QUERY_CALLS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "QUERY_CALLS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    private OracleResult checkValidExternalOrders(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = confirmedOrdersOf(appId, ctx).size();
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "VALID_EXTERNAL_ORDERS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "VALID_EXTERNAL_ORDERS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    /**
     * Part B 的资源 EXPECT 在 PR4 只完成 parse 期校验；运行时检查器由 PR5/PR6 接线。
     * 接线之前 fail-closed：宁可让运行失败，也不静默 PASS。
     */
    private OracleResult checkResourceRuleUnavailable(OracleRule rule) {
        return OracleResult.fail(rule.oracleId(), rule.ruleType().name(), "SUPPORTED",
                "RESOURCE_ORACLE_NOT_AVAILABLE", "resource oracle 在 PR5/PR6 接线");
    }

    private OracleResult checkNoDuplicateRule(OracleRule rule, OracleContext ctx) {        int actual = confirmedOrdersOf(rule.arguments().get(0), ctx).size();
        return actual <= 1
                ? OracleResult.pass(rule.oracleId(), "NO_DUPLICATE_EXTERNAL_ORDER",
                        "<=1", String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "NO_DUPLICATE_EXTERNAL_ORDER",
                        "<=1", String.valueOf(actual), "");
    }

    private Set<String> confirmedOrdersOf(String appId, OracleContext ctx) {
        Set<String> orders = new HashSet<>();
        for (ExternalInteraction interaction : ctx.interactions()) {
            if (interaction.applicationId().equals(appId) && isConfirmed(interaction.sideEffectCertainty())) {
                orders.add(interaction.externalOrderId());
            }
        }
        return orders;
    }
}
