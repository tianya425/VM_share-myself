package lab;

import java.util.Map;

/**
 * 统计快照：各集合数量与状态分布（供 {@code stats} 输出）。
 *
 * @param points             点位数
 * @param applications       申请数
 * @param applicationsByState 申请按状态分布
 * @param outbox             Outbox 总数
 * @param outboxByStatus     Outbox 按状态分布
 * @param inbox              Inbox 总数
 * @param inboxByStatus      Inbox 按状态分布
 * @param workflows          工作流总数
 * @param workflowsByState   工作流按状态分布
 */
public record Stats(
        int points,
        int applications,
        Map<String, Integer> applicationsByState,
        int outbox,
        Map<String, Integer> outboxByStatus,
        int inbox,
        Map<String, Integer> inboxByStatus,
        int workflows,
        Map<String, Integer> workflowsByState) {
}
