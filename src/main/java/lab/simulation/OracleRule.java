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

    /**
     * 规则类型。
     *
     * <p>资源领域固定 8 项（STU-SIM-02-R1 §12.2，逐字、固定参数）：
     * <ul>
     *   <li>{@link #RESERVATION_STATE}     applicationId state，state ∈ WAITING/HELD/RELEASED/CANCELLED/MISSING</li>
     *   <li>{@link #RESERVATION_DECISION}  eventId type，type ∈ HELD/WAITING/IDEMPOTENT/CONFLICT</li>
     *   <li>{@link #RESOURCE_AVAILABILITY} resourceId state，state ∈ ENABLED/DISABLED/MISSING</li>
     *   <li>{@link #RESOURCE_HOLDER}       resourceId applicationId|NONE</li>
     *   <li>{@link #WAIT_QUEUE}            resourceId commaSeparatedIds|NONE（按固定排序键输出）</li>
     *   <li>{@link #HELD_COUNT}            resourceId nonNegativeInt（0 或 1）</li>
     *   <li>{@link #NO_DOUBLE_HOLD}        applicationId PASS|FAIL</li>
     *   <li>{@link #NO_ORPHAN_RESERVATION} 无参数，PASS|FAIL</li>
     * </ul>
     *
     * <p>尾部五项是 ORC-RES-EXT 扩展检查，保留用于既有场景与历史证据；
     * 它们不得替代固定 8 项，也不得冒充固定合同已完成。
     */
    public enum RuleType {
        APPLICATION_STATE,
        OUTBOX_STATUS,
        CREATE_CALLS,
        QUERY_CALLS,
        VALID_EXTERNAL_ORDERS,
        NO_DUPLICATE_EXTERNAL_ORDER,

        // STU-SIM-02-R1 §12.2：资源领域固定 8 个 EXPECT
        RESERVATION_STATE,
        RESERVATION_DECISION,
        RESOURCE_AVAILABILITY,
        RESOURCE_HOLDER,
        WAIT_QUEUE,
        HELD_COUNT,
        NO_DOUBLE_HOLD,
        NO_ORPHAN_RESERVATION,

        // ORC-RES-EXT 扩展检查（不属于固定 8 项）
        WAITING_COUNT,
        QUEUE_ORDER,
        QUEUE_EMPTY,
        WORLD_RESTART_CONSISTENT,
        SINGLE_CAPACITY
    }
}
