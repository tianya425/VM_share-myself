package lab.simulation;

import lab.ApplicationState;
import lab.OutboxEntry;
import lab.OutboxStatus;
import lab.TransferApplication;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceWorldSnapshot;
import lab.simulation.resource.VirtualResource;

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
        // Part B：六条资源不变量始终启用、真算。
        list.addAll(ResourceInvariantOracle.check(
                ctx.resourceWorld(), ctx.resourceSteps(), ctx.restartPairs()));
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
                case RESERVATION_STATE -> list.add(checkReservationState(rule, ctx));
                case HELD_COUNT -> list.add(checkReservationCount(rule, ctx, ReservationState.HELD));
                case WAITING_COUNT -> list.add(checkReservationCount(rule, ctx, ReservationState.WAITING));
                case RESOURCE_AVAILABILITY -> list.add(checkResourceAvailability(rule, ctx));
                case QUEUE_ORDER -> list.add(checkQueueOrder(rule, ctx));
                case QUEUE_EMPTY -> list.add(checkQueueEmpty(rule, ctx));
                case WORLD_RESTART_CONSISTENT -> list.add(checkWorldRestartConsistent(rule, ctx));
                case SINGLE_CAPACITY -> list.add(checkSingleCapacityRule(rule, ctx));
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

    // ---------- 场景 EXPECT（Part B 资源） ----------

    private OracleResult checkReservationState(OracleRule rule, OracleContext ctx) {
        String applicationId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        List<Reservation> matches = reservationsOf(ctx.resourceWorld(), applicationId);
        if (matches.isEmpty()) {
            return OracleResult.fail(rule.oracleId(), "RESERVATION_STATE", want, "MISSING",
                    "no reservation for app=" + applicationId);
        }
        if (matches.size() > 1) {
            return OracleResult.fail(rule.oracleId(), "RESERVATION_STATE", want, "DUPLICATE_RESERVATION",
                    "app=" + applicationId + " entries=" + matches.size());
        }
        String actual = matches.get(0).state().name();
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "RESERVATION_STATE", want, actual)
                : OracleResult.fail(rule.oracleId(), "RESERVATION_STATE", want, actual, "");
    }

    private OracleResult checkReservationCount(OracleRule rule, OracleContext ctx, ReservationState state) {
        String resourceId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = 0;
        for (Reservation reservation : ctx.resourceWorld().reservations()) {
            if (reservation.resourceId().equals(resourceId) && reservation.state() == state) {
                actual++;
            }
        }
        String ruleType = state == ReservationState.HELD ? "HELD_COUNT" : "WAITING_COUNT";
        return want == actual
                ? OracleResult.pass(rule.oracleId(), ruleType, String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), ruleType, String.valueOf(want),
                        String.valueOf(actual), "resource=" + resourceId);
    }

    private OracleResult checkResourceAvailability(OracleRule rule, OracleContext ctx) {
        String resourceId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        List<VirtualResource> matches = resourcesOf(ctx.resourceWorld(), resourceId);
        if (matches.isEmpty()) {
            return OracleResult.fail(rule.oracleId(), "RESOURCE_AVAILABILITY", want, "MISSING",
                    "no resource " + resourceId);
        }
        if (matches.size() > 1) {
            return OracleResult.fail(rule.oracleId(), "RESOURCE_AVAILABILITY", want, "DUPLICATE_RESOURCE",
                    "resource=" + resourceId + " entries=" + matches.size());
        }
        String actual = matches.get(0).availability().name();
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "RESOURCE_AVAILABILITY", want, actual)
                : OracleResult.fail(rule.oracleId(), "RESOURCE_AVAILABILITY", want, actual, "");
    }

    private OracleResult checkQueueOrder(OracleRule rule, OracleContext ctx) {
        String resourceId = rule.arguments().get(0);
        List<String> want = rule.arguments().subList(1, rule.arguments().size());
        List<String> actual = new ArrayList<>();
        for (Reservation reservation : ctx.resourceWorld().reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                actual.add(reservation.applicationId());
            }
        }
        if (want.equals(actual)) {
            return OracleResult.pass(rule.oracleId(), "QUEUE_ORDER", String.join(",", want),
                    String.join(",", actual));
        }
        return OracleResult.fail(rule.oracleId(), "QUEUE_ORDER", String.join(",", want),
                actual.isEmpty() ? "EMPTY" : String.join(",", actual), "resource=" + resourceId);
    }

    private OracleResult checkQueueEmpty(OracleRule rule, OracleContext ctx) {
        String resourceId = rule.arguments().get(0);
        int waiting = 0;
        for (Reservation reservation : ctx.resourceWorld().reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                waiting++;
            }
        }
        return waiting == 0
                ? OracleResult.pass(rule.oracleId(), "QUEUE_EMPTY", "0", "0")
                : OracleResult.fail(rule.oracleId(), "QUEUE_EMPTY", "0", String.valueOf(waiting),
                        "resource=" + resourceId);
    }

    private OracleResult checkWorldRestartConsistent(OracleRule rule, OracleContext ctx) {
        List<RestartPair> pairs = ctx.restartPairs();
        int mismatches = 0;
        for (RestartPair pair : pairs) {
            if (!pair.consistent()) {
                mismatches++;
            }
        }
        String actual = "restartPairs=" + pairs.size() + ",mismatches=" + mismatches;
        String expected = "restartPairs>0,mismatches=0";
        if (pairs.isEmpty()) {
            return OracleResult.fail(rule.oracleId(), "WORLD_RESTART_CONSISTENT", expected, actual,
                    "no WORLD_RESTART event");
        }
        return mismatches == 0
                ? OracleResult.pass(rule.oracleId(), "WORLD_RESTART_CONSISTENT", expected, actual)
                : OracleResult.fail(rule.oracleId(), "WORLD_RESTART_CONSISTENT", expected, actual,
                        "restart digest mismatch");
    }

    /**
     * 单容量：把运行中每个观察点的 HELD 数取最大值，真实判断是否越过 1。
     */
    private OracleResult checkSingleCapacityRule(OracleRule rule, OracleContext ctx) {
        String resourceId = rule.arguments().get(0);
        if (resourcesOf(ctx.resourceWorld(), resourceId).isEmpty()) {
            return OracleResult.fail(rule.oracleId(), "SINGLE_CAPACITY", "<=1", "MISSING",
                    "no resource " + resourceId);
        }
        int max = heldCount(ctx.resourceWorld(), resourceId);
        for (ResourceStep step : ctx.resourceSteps()) {
            max = Math.max(max, heldCount(step.after(), resourceId));
        }
        return max <= 1
                ? OracleResult.pass(rule.oracleId(), "SINGLE_CAPACITY", "<=1", String.valueOf(max))
                : OracleResult.fail(rule.oracleId(), "SINGLE_CAPACITY", "<=1", String.valueOf(max),
                        "resource=" + resourceId + " 同时 HELD");
    }

    private static int heldCount(ResourceWorldSnapshot snapshot, String resourceId) {
        int held = 0;
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.HELD) {
                held++;
            }
        }
        return held;
    }

    private static List<Reservation> reservationsOf(ResourceWorldSnapshot snapshot, String applicationId) {
        List<Reservation> matches = new ArrayList<>();
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                matches.add(reservation);
            }
        }
        return matches;
    }

    private static List<VirtualResource> resourcesOf(ResourceWorldSnapshot snapshot, String resourceId) {
        List<VirtualResource> matches = new ArrayList<>();
        for (VirtualResource resource : snapshot.resources()) {
            if (resource.resourceId().equals(resourceId)) {
                matches.add(resource);
            }
        }
        return matches;
    }

    private OracleResult checkNoDuplicateRule(OracleRule rule, OracleContext ctx) {
        int actual = confirmedOrdersOf(rule.arguments().get(0), ctx).size();
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
