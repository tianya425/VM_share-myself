package lab;

public record InboxEntry(
        String entryId,
        String applicationId,
        String eventType,
        InboxStatus status,
        String reason) {

    public InboxEntry withProcessed() {
        return new InboxEntry(entryId, applicationId, eventType, InboxStatus.PROCESSED, "");
    }

    public InboxEntry withRejected(String newReason) {
        return new InboxEntry(entryId, applicationId, eventType, InboxStatus.REJECTED, newReason);
    }
}
