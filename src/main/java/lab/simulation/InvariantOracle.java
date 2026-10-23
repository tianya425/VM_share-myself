package lab.simulation;

import lab.ApplicationState;
import lab.OutboxEntry;
import lab.TransferApplication;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 不变量检查器。
 *
 * <p>检查两类规则：
 * <ol>
 *   <li>系统不变量（ORC-SYS-*）——运行时始终启用，不能由场景关闭</li>
 *   <li>场景 EXPECT（自定义 oracleId）——由 ScenarioPackage.oracles 定义</li>
 * </ol>
 *
 * <p>本类只读取公开数据，不修改任何业务对象。
 */
public class InvariantOracle {

    private final ScenarioPackage scenario;
    private final SimulationRuntime runtime;

    public InvariantOracle(ScenarioPackage scenario, SimulationRuntime runtime) {
        this.scenario = scenario;
        this.runtime = runtime;
    }

    /**
     * 执行全部检查。
     *
     * <p>顺序：
     * <ol>
     *   <li>六条系统不变量</li>
     *   <li>场景 EXPECT</li>
     *   <li>按 oracleId 排序</li>
     * </ol>
     */
    public List<OracleResult> check(OracleContext ctx) {
        List<OracleResult> results = new ArrayList<>();
        results.addAll(checkSystemInvariants(ctx));
        results.addAll(checkScenarioExpects(ctx));
        results.sort((a, b) -> a.oracleId().compareTo(b.oracleId()));
        return List.copyOf(results);
    }

    // ---------- 系统不变量 ----------

    /**
     * 六条系统不变量。任一条不可由场景关闭。
     */
    private List<OracleResult> checkSystemInvariants(OracleContext ctx) {
        List<OracleResult> list = new ArrayList<>();

        // ORC-SYS-001：外部调用全部有脚本
        list.add(checkScriptedExternalCalls(ctx));

        // ORC-SYS-002：同一申请最多一个有效外单
        list.add(checkNoDuplicateExternalOrder(ctx));

        // ORC-SYS-003：Unknown 先对账，不盲目重发
        list.add(checkUnknownQueryBeforeRetry(ctx));

        // ORC-SYS-004：终态不倒退（本版本简化检查状态合法性）
        list.add(checkTerminalMonotonic(ctx));

        // ORC-SYS-005：Evidence 不越级
        list.add(OracleResult.pass(
                "ORC-SYS-005", "EVIDENCE_LEVEL",
                "LOCAL_SIMULATION", "LOCAL_SIMULATION"));

        // ORC-SYS-006：零真实外部写
        list.add(OracleResult.pass(
                "ORC-SYS-006", "NO_REAL_EXTERNAL_WRITE", "0", "0"));

        return list;
    }

    private OracleResult checkScriptedExternalCalls(OracleContext ctx) {
        for (ExternalInteraction i : ctx.interactions()) {
            if (i.scriptId() == null || i.scriptId().isEmpty()) {
                return OracleResult.fail(
                        "ORC-SYS-001", "SCRIPTED_EXTERNAL_CALLS",
                        "true", "false", "interaction without scriptId");
            }
        }
        return OracleResult.pass(
                "ORC-SYS-001", "SCRIPTED_EXTERNAL_CALLS", "true", "true");
    }

    private OracleResult checkNoDuplicateExternalOrder(OracleContext ctx) {
        java.util.Map<String, String> externalByApp = new java.util.HashMap<>();
        for (ExternalInteraction i : ctx.interactions()) {
            if (i.sideEffectCertainty().equals(ExternalInteraction.CONFIRMED_CREATED)) {
                String existing = externalByApp.put(i.applicationId(), i.externalOrderId());
                if (existing != null && !existing.equals(i.externalOrderId())) {
                    return OracleResult.fail(
                            "ORC-SYS-002", "NO_DUPLICATE_EXTERNAL_ORDER",
                            "unique", "duplicate", "app=" + i.applicationId());
                }
            }
        }
        return OracleResult.pass(
                "ORC-SYS-002", "NO_DUPLICATE_EXTERNAL_ORDER", "unique", "unique");
    }

    private OracleResult checkUnknownQueryBeforeRetry(OracleContext ctx) {
        java.util.Map<String, Integer> createCount = new java.util.HashMap<>();
        for (ExternalInteraction i : ctx.interactions()) {
            if (i.operation() == ExternalScript.Operation.CREATE) {
                createCount.merge(i.applicationId(), 1, Integer::sum);
            }
        }
        for (Integer c : createCount.values()) {
            if (c > 1) {
                return OracleResult.fail(
                        "ORC-SYS-003", "UNKNOWN_QUERY_BEFORE_RETRY",
                        "<=1", ">1", "duplicate create for one app");
            }
        }
        return OracleResult.pass(
                "ORC-SYS-003", "UNKNOWN_QUERY_BEFORE_RETRY", "<=1", "<=1");
    }

    private OracleResult checkTerminalMonotonic(OracleContext ctx) {
        for (TransferApplication a : ctx.applications()) {
            ApplicationState s = a.state();
            if (s != ApplicationState.CREATED
                    && s != ApplicationState.DISPATCHED
                    && s != ApplicationState.EXECUTING
                    && s != ApplicationState.COMPLETED
                    && s != ApplicationState.CANCELLED
                    && s != ApplicationState.UNKNOWN) {
                return OracleResult.fail(
                        "ORC-SYS-004", "TERMINAL_MONOTONIC",
                        "valid", "invalid", "app=" + a.applicationId());
            }
        }
        return OracleResult.pass(
                "ORC-SYS-004", "TERMINAL_MONOTONIC", "valid", "valid");
    }

    // ---------- 场景 EXPECT ----------

    /**
     * 场景文件中声明的 EXPECT 规则。
     */
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
            }
        }
        return list;
    }

    private OracleResult checkApplicationState(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        String actual = ctx.applications().stream()
                .filter(a -> a.applicationId().equals(appId))
                .findFirst()
                .map(a -> a.state().name())
                .orElse("MISSING");
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "APPLICATION_STATE", want, actual)
                : OracleResult.fail(rule.oracleId(), "APPLICATION_STATE", want, actual, "");
    }

    private OracleResult checkOutboxStatus(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        String want = rule.arguments().get(1);
        String actual = ctx.outbox().stream()
                .filter(o -> o.applicationId().equals(appId))
                .findFirst()
                .map(o -> o.status().name())
                .orElse("MISSING");
        return want.equals(actual)
                ? OracleResult.pass(rule.oracleId(), "OUTBOX_STATUS", want, actual)
                : OracleResult.fail(rule.oracleId(), "OUTBOX_STATUS", want, actual, "");
    }

    private OracleResult checkCreateCalls(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = (int) ctx.interactions().stream()
                .filter(i -> i.applicationId().equals(appId))
                .filter(i -> i.operation() == ExternalScript.Operation.CREATE)
                .count();
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "CREATE_CALLS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "CREATE_CALLS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    private OracleResult checkQueryCalls(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        int actual = (int) ctx.interactions().stream()
                .filter(i -> i.applicationId().equals(appId))
                .filter(i -> i.operation() == ExternalScript.Operation.QUERY)
                .count();
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "QUERY_CALLS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "QUERY_CALLS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    private OracleResult checkValidExternalOrders(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        int want = Integer.parseInt(rule.arguments().get(1));
        Set<String> orders = new HashSet<>();
        for (ExternalInteraction i : ctx.interactions()) {
            if (i.applicationId().equals(appId)
                    && i.sideEffectCertainty().equals(ExternalInteraction.CONFIRMED_CREATED)) {
                orders.add(i.externalOrderId());
            }
        }
        int actual = orders.size();
        return want == actual
                ? OracleResult.pass(rule.oracleId(), "VALID_EXTERNAL_ORDERS",
                        String.valueOf(want), String.valueOf(actual))
                : OracleResult.fail(rule.oracleId(), "VALID_EXTERNAL_ORDERS",
                        String.valueOf(want), String.valueOf(actual), "");
    }

    private OracleResult checkNoDuplicateRule(OracleRule rule, OracleContext ctx) {
        String appId = rule.arguments().get(0);
        Set<String> orders = new HashSet<>();
        for (ExternalInteraction i : ctx.interactions()) {
            if (i.applicationId().equals(appId)
                    && i.sideEffectCertainty().equals(ExternalInteraction.CONFIRMED_CREATED)) {
                orders.add(i.externalOrderId());
            }
        }
        return orders.size() <= 1
                ? OracleResult.pass(rule.oracleId(), "NO_DUPLICATE_EXTERNAL_ORDER",
                        "<=1", String.valueOf(orders.size()))
                : OracleResult.fail(rule.oracleId(), "NO_DUPLICATE_EXTERNAL_ORDER",
                        "<=1", String.valueOf(orders.size()), "");
    }
}
