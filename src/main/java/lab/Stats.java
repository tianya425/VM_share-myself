package lab;

import java.util.Map;

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
