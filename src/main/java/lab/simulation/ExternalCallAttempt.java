package lab.simulation;

import java.time.Instant;

/**
 * 一次外部调用尝试（含未脚本化的尝试）。
 *
 * <p>与 {@link ExternalInteraction} 的区别：interaction 只记录脚本化且产生结果的调用，
 * attempt 记录"调用发生过"这一事实——包括脚本缺失、被拒绝执行的情况。
 *
 * <p>字段：
 * <ul>
 *   <li>sequence           — 一次运行内严格递增的全局序号（与 StateTransition 共用一个序列）</li>
 *   <li>virtualOccurredAt  — 虚拟时间</li>
 *   <li>operation          — CREATE 或 QUERY</li>
 *   <li>ordinal            — 该 operation 的第几次调用，从 1 开始</li>
 *   <li>applicationId      — 申请编号</li>
 *   <li>scripted           — 是否找到了脚本；false 表示 UNSCRIPTED_EXTERNAL_CALL</li>
 *   <li>scriptId           — 消费的脚本编号；未脚本化时为空串</li>
 *   <li>outcome            — 脚本 outcome；未脚本化时为 UNSCRIPTED_EXTERNAL_CALL</li>
 *   <li>externalOrderId    — 外单号；没有时为 "-"</li>
 * </ul>
 */
public record ExternalCallAttempt(
        int sequence,
        Instant virtualOccurredAt,
        ExternalScript.Operation operation,
        int ordinal,
        String applicationId,
        boolean scripted,
        String scriptId,
        String outcome,
        String externalOrderId) {

    /** 未脚本化调用在 outcome 上的固定取值。 */
    public static final String UNSCRIPTED = "UNSCRIPTED_EXTERNAL_CALL";
}
