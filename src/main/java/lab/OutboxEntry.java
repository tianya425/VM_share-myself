package lab;

public record OutboxEntry(
        String entryId,
        String applicationId,
        String fromPointId,
        String toPointId,
        OutboxStatus status,
        String externalOrderId,
        int attempts) {

    public OutboxEntry withSent(String newExternalOrderId) {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.SENT, newExternalOrderId, attempts);
    }

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

    public OutboxEntry withOneMoreAttempt() {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.PENDING, externalOrderId, attempts + 1);
    }
}
