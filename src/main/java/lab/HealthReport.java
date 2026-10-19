package lab;

public record HealthReport(
        String mode,
        String storageStatus,
        int points,
        int applications,
        int unknownApplications,
        int outboxTotal,
        int outboxPending,
        int outboxFailed,
        int inboxTotal,
        int inboxPending,
        int workflowsTotal,
        int workflowsRunning,
        String overall) {
}
