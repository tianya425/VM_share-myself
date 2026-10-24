package lab;

/**
 * Inbox 记录：外部回调的接收与处理结果。
 *
 * <p>entryId 固定为 {@code "INB-" + applicationId + "-" + eventType}，重复回调据此去重。
 *
 * @param entryId       去重键
 * @param applicationId 申请编号
 * @param eventType     STARTED / COMPLETED / FAILED
 * @param status        PENDING / PROCESSED / REJECTED
 * @param reason        拒绝原因（PROCESSED 时为空串）
 */
public record InboxEntry(
        String entryId,
        String applicationId,
        String eventType,
        InboxStatus status,
        String reason) {

    /** 返回处理成功的副本。 */
    public InboxEntry withProcessed() {
        return new InboxEntry(entryId, applicationId, eventType, InboxStatus.PROCESSED, "");
    }

    /** 返回被拒绝的副本，并记录原因。 */
    public InboxEntry withRejected(String newReason) {
        return new InboxEntry(entryId, applicationId, eventType, InboxStatus.REJECTED, newReason);
    }
}
