package lab;

/**
 * 一次健康检查的快照。
 *
 * @param mode               READ_WRITE / READ_ONLY
 * @param storageStatus      Storage 可用性（OK / FAIL）
 * @param points             点位数
 * @param applications       申请数
 * @param unknownApplications UNKNOWN 状态的申请数
 * @param outboxTotal        Outbox 总数
 * @param outboxPending      PENDING 数
 * @param outboxUncertain    UNCERTAIN 数
 * @param outboxFailed       FAILED 数
 * @param inboxTotal         Inbox 总数
 * @param inboxPending       PENDING 数
 * @param workflowsTotal     工作流总数
 * @param workflowsRunning   RUNNING 数
 * @param overall            OK / WARN / FAIL
 */
public record HealthReport(
        String mode,
        String storageStatus,
        int points,
        int applications,
        int unknownApplications,
        int outboxTotal,
        int outboxPending,
        int outboxUncertain,
        int outboxFailed,
        int inboxTotal,
        int inboxPending,
        int workflowsTotal,
        int workflowsRunning,
        String overall) {
}
