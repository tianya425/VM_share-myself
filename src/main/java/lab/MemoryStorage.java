package lab;

import java.util.ArrayList;
import java.util.List;

public class MemoryStorage implements Storage {

    private List<Point> points = new ArrayList<>();
    private List<TransferApplication> applications = new ArrayList<>();
    private List<OutboxEntry> outbox = new ArrayList<>();
    private List<InboxEntry> inbox = new ArrayList<>();
    private List<WorkflowInstance> workflows = new ArrayList<>();

    @Override
    public List<Point> loadPoints() {
        return new ArrayList<>(points);
    }

    @Override
    public List<TransferApplication> loadApplications() {
        return new ArrayList<>(applications);
    }

    @Override
    public List<OutboxEntry> loadOutbox() {
        return new ArrayList<>(outbox);
    }

    @Override
    public List<InboxEntry> loadInbox() {
        return new ArrayList<>(inbox);
    }

    @Override
    public List<WorkflowInstance> loadWorkflows() {
        return new ArrayList<>(workflows);
    }

    @Override
    public void savePoints(List<Point> points) {
        this.points = new ArrayList<>(points);
    }

    @Override
    public void saveApplications(List<TransferApplication> applications) {
        this.applications = new ArrayList<>(applications);
    }

    @Override
    public void saveOutbox(List<OutboxEntry> outbox) {
        this.outbox = new ArrayList<>(outbox);
    }

    @Override
    public void saveInbox(List<InboxEntry> inbox) {
        this.inbox = new ArrayList<>(inbox);
    }

    @Override
    public void saveWorkflows(List<WorkflowInstance> workflows) {
        this.workflows = new ArrayList<>(workflows);
    }
}
