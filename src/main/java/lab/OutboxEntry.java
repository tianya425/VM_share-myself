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

    public OutboxEntry withOneMoreAttempt() {
        return new OutboxEntry(entryId, applicationId, fromPointId, toPointId,
                OutboxStatus.PENDING, externalOrderId, attempts + 1);
    }
}
