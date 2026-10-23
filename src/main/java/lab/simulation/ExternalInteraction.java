package lab.simulation;

import java.time.Instant;

/**
 * 一次外部交互记录。
 *
 * <p>字段：
 * <ul>
 *   <li>interactionSequence — 全局递增序号</li>
 *   <li>virtualOccurredAt   — 虚拟时间</li>
 *   <li>operation           — CREATE 或 QUERY</li>
 *   <li>ordinal             — 该 operation 的第几次调用</li>
 *   <li>applicationId       — 关联的申请</li>
 *   <li>outcome             — 返回给业务端的结果</li>
 *   <li>sideEffectCertainty — 副作用确定性</li>
 *   <li>externalOrderId     — 外部订单号（可为 "-"）</li>
 *   <li>scriptId            — 消费的脚本编号</li>
 * </ul>
 */
public record ExternalInteraction(
        int interactionSequence,
        Instant virtualOccurredAt,
        ExternalScript.Operation operation,
        int ordinal,
        String applicationId,
        String outcome,
        String sideEffectCertainty,
        String externalOrderId,
        String scriptId) {

    /**
     * 副作用确定性取值：
     * CONFIRMED_CREATED / CONFIRMED_NOT_CREATED / REJECTED_NO_EFFECT
     * / OBSERVED_EXISTING / OBSERVED_NOT_FOUND
     */
    public static final String CONFIRMED_CREATED = "CONFIRMED_CREATED";
    public static final String CONFIRMED_NOT_CREATED = "CONFIRMED_NOT_CREATED";
    public static final String REJECTED_NO_EFFECT = "REJECTED_NO_EFFECT";
    public static final String OBSERVED_EXISTING = "OBSERVED_EXISTING";
    public static final String OBSERVED_NOT_FOUND = "OBSERVED_NOT_FOUND";
}
