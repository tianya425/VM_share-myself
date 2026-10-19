package lab;

import java.util.List;

public interface Storage {

    List<Point> loadPoints();

    List<TransferApplication> loadApplications();

    List<OutboxEntry> loadOutbox();

    List<InboxEntry> loadInbox();

    List<WorkflowInstance> loadWorkflows();

    void savePoints(List<Point> points);

    void saveApplications(List<TransferApplication> applications);

    void saveOutbox(List<OutboxEntry> outbox);

    void saveInbox(List<InboxEntry> inbox);

    void saveWorkflows(List<WorkflowInstance> workflows);
}
