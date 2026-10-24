package lab.simulation;

import java.util.List;

/**
 * 一条期望规则。
 *
 * <p>字段：
 * <ul>
 *   <li>oracleId  — 场景内唯一编号</li>
 *   <li>ruleType  — 规则类型（见 RuleType）</li>
 *   <li>arguments — 规则参数</li>
 * </ul>
 */
public record OracleRule(
        String oracleId,
        RuleType ruleType,
        List<String> arguments) {

    public OracleRule {
        arguments = List.copyOf(arguments);
    }

    public enum RuleType {
        APPLICATION_STATE,
        OUTBOX_STATUS,
        CREATE_CALLS,
        QUERY_CALLS,
        VALID_EXTERNAL_ORDERS,
        NO_DUPLICATE_EXTERNAL_ORDER,

        // Part B / PR4：资源期望（检查器在 PR5/PR6 接线）
        RESERVATION_STATE,
        HELD_COUNT,
        WAITING_COUNT,
        RESOURCE_AVAILABILITY,
        QUEUE_ORDER,
        QUEUE_EMPTY,
        WORLD_RESTART_CONSISTENT,
        SINGLE_CAPACITY
    }
}
