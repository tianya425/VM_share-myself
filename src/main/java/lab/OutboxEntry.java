package lab;

/**
 * Outbox 可靠意图记录。
 *
 * <p>PENDING → SENT / FAILED / UNCERTAIN；UNCERTAIN 表示外部调用结果未知，
 * 恢复时必须先 query 对账，不允许直接重发 create。
 *
 * @param entryId         意图编号（{@code "OBX-" + applicationId}）
 * @param applicationId   申请编号
 * @param fromPointId     起点编号
 * @param toPointId       终点编号
 * @param status          PENDING / SENT / FAILED / UNCERTAIN
 * @param externalOrderId 外部订单号（未创建时为 null）
 * @param attempts        对外部端口的尝试次数
 */
public record OutboxEntry(
        String entryId,
        String applicationId,
        String fromPointId,
        String toPointId,
        OutboxStatus status,
        String externalOrderId,
        int attempts) {

    /** 返回已发送副本，并记录外部订单号。 */
    public OutboxEntry withSent(String newExternalOrderId) {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.SENT, newExternalOrderId, attempts);
    }

    /** 返回发送失败副本。 */
    public OutboxEntry withFailed() {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.FAILED, externalOrderId, attempts);
    }
    /**
     * 将状态改为 UNCERTAIN。
     * 用于 create 调用后结果不确定（TIMEOUT）的情况。
     */
    public OutboxEntry withUncertain() {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.UNCERTAIN, externalOrderId, attempts + 1);
    }

    /** 返回尝试次数 +1 的副本。 */
    public OutboxEntry withOneMoreAttempt() {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.PENDING, externalOrderId, attempts + 1);
    }
}
