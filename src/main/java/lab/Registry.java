package lab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class Registry {

    private static final String ID_PATTERN = "[A-Z][A-Z0-9_-]{0,31}";
    private static final int NAME_MAX = 40;

    private final Storage storage;
    private final RcsExecutionPort rcsPort;
    private final boolean readOnly;
    private final Map<String, Point> points = new HashMap<>();
    private final Map<String, TransferApplication> applications = new HashMap<>();
    private final Map<String, OutboxEntry> outbox = new HashMap<>();
    private final Map<String, InboxEntry> inbox = new HashMap<>();
    private final Map<String, WorkflowInstance> workflows = new HashMap<>();

    public Registry(Storage storage) {
        this(storage, new FakeRcsAdapter(FakeRcsAdapter.Mode.ACCEPT), false);
    }

    public Registry(Storage storage, RcsExecutionPort rcsPort) {
        this(storage, rcsPort, false);
    }

    public Registry(Storage storage, RcsExecutionPort rcsPort, boolean readOnly) {
        this.storage = storage;
        this.rcsPort = rcsPort;
        this.readOnly = readOnly;
        for (Point p : storage.loadPoints()) {
            points.put(p.pointId(), p);
        }
        for (TransferApplication a : storage.loadApplications()) {
            applications.put(a.applicationId(), a);
        }
        for (OutboxEntry e : storage.loadOutbox()) {
            outbox.put(e.entryId(), e);
        }
        for (InboxEntry e : storage.loadInbox()) {
            inbox.put(e.entryId(), e);
        }
        for (WorkflowInstance w : storage.loadWorkflows()) {
            workflows.put(w.applicationId(), w);
        }
    }

    // ---------- point ----------

    public synchronized Point registerPoint(String pointId, String name) {
        checkWritable();
        checkId(pointId);
        checkName(name);

        if (points.containsKey(pointId)) {
            throw new RegistryException("POINT_EXISTS", pointId);
        }

        Point p = new Point(pointId, name);
        points.put(pointId, p);
        persistPoints();
        return p;
    }

    public Optional<Point> findPoint(String pointId) {
        checkId(pointId);
        return Optional.ofNullable(points.get(pointId));
    }

    public List<Point> listPoints() {
        List<Point> copy = new ArrayList<>(points.values());
        copy.sort((a, b) -> a.pointId().compareTo(b.pointId()));
        return List.copyOf(copy);
    }

    // ---------- application ----------

    public synchronized RegistrationResult registerApplication(String applicationId,
                                                                String fromPointId,
                                                                String toPointId) {
        checkWritable();
        checkId(applicationId);
        checkId(fromPointId);
        checkId(toPointId);

        TransferApplication existing = applications.get(applicationId);
        if (existing != null) {
            if (existing.fromPointId().equals(fromPointId)
                    && existing.toPointId().equals(toPointId)) {
                return new RegistrationResult(existing, true);
            }
            throw new RegistryException("APPLICATION_CONFLICT", applicationId);
        }

        if (!points.containsKey(fromPointId)) {
            throw new RegistryException("UNKNOWN_POINT", fromPointId);
        }
        if (!points.containsKey(toPointId)) {
            throw new RegistryException("UNKNOWN_POINT", toPointId);
        }
        if (fromPointId.equals(toPointId)) {
            throw new RegistryException("SAME_POINT", fromPointId);
        }

        TransferApplication app = new TransferApplication(applicationId, fromPointId, toPointId);
        applications.put(applicationId, app);
        persistApplications();
        return new RegistrationResult(app, false);
    }

    public Optional<TransferApplication> findApplication(String applicationId) {
        checkId(applicationId);
        return Optional.ofNullable(applications.get(applicationId));
    }

    public List<TransferApplication> listApplications() {
        List<TransferApplication> copy = new ArrayList<>(applications.values());
        copy.sort((a, b) -> a.applicationId().compareTo(b.applicationId()));
        return List.copyOf(copy);
    }

    // ---------- state transitions ----------

    public synchronized TransferApplication dispatchApplication(String applicationId) {
        checkWritable();
        checkId(applicationId);

        TransferApplication existing = applications.get(applicationId);
        if (existing == null) {
            throw new RegistryException("UNKNOWN_APPLICATION", applicationId);
        }
        if (existing.state() != ApplicationState.CREATED) {
            throw new RegistryException("INVALID_STATE_TRANSITION", applicationId);
        }

        OutboxEntry entry = new OutboxEntry(
                "OBX-" + applicationId,
                applicationId,
                existing.fromPointId(),
                existing.toPointId(),
                OutboxStatus.PENDING,
                null,
                0);
        outbox.put(entry.entryId(), entry);
        persistOutbox();

        ExternalOrderResult result = rcsPort.createOrder(
                applicationId, existing.fromPointId(), existing.toPointId());

        TransferApplication updated;
        switch (result.outcome()) {
            case ACCEPTED -> {
                outbox.put(entry.entryId(), entry.withSent(result.externalOrderId()));
                updated = existing.withState(ApplicationState.DISPATCHED)
                        .withExternalOrderId(result.externalOrderId());
            }
            case TIMEOUT -> {
                outbox.put(entry.entryId(), entry.withOneMoreAttempt());
                updated = existing.withState(ApplicationState.UNKNOWN);
            }
            case REJECTED -> {
                outbox.put(entry.entryId(), entry.withFailed());
                persistOutbox();
                throw new RegistryException("RCS_REJECTED", applicationId);
            }
            default -> {
                persistOutbox();
                throw new RegistryException("RCS_UNEXPECTED", applicationId);
            }
        }

        applications.put(applicationId, updated);
        persistApplications();
        persistOutbox();
        return updated;
    }

    public synchronized TransferApplication queryApplication(String applicationId) {
        checkWritable();
        checkId(applicationId);

        TransferApplication existing = applications.get(applicationId);
        if (existing == null) {
            throw new RegistryException("UNKNOWN_APPLICATION", applicationId);
        }
        if (existing.state() != ApplicationState.UNKNOWN) {
            throw new RegistryException("INVALID_STATE_TRANSITION", applicationId);
        }

        ExternalOrderResult result = rcsPort.queryOrder(applicationId);
        TransferApplication updated;
        switch (result.outcome()) {
            case ACCEPTED -> {
                updated = existing.withState(ApplicationState.DISPATCHED)
                        .withExternalOrderId(result.externalOrderId());
            }
            case NOT_FOUND -> throw new RegistryException("EXTERNAL_ORDER_NOT_FOUND", applicationId);
            default -> throw new RegistryException("RCS_UNEXPECTED", applicationId);
        }

        applications.put(applicationId, updated);
        persistApplications();
        return updated;
    }

    public synchronized TransferApplication executeApplication(String applicationId) {
        checkWritable();
        return transition(applicationId, ApplicationState.EXECUTING);
    }

    public synchronized TransferApplication completeApplication(String applicationId) {
        checkWritable();
        return transition(applicationId, ApplicationState.COMPLETED);
    }

    public synchronized TransferApplication cancelApplication(String applicationId) {
        checkWritable();
        return transition(applicationId, ApplicationState.CANCELLED);
    }

    private TransferApplication transition(String applicationId, ApplicationState target) {
        checkId(applicationId);

        TransferApplication existing = applications.get(applicationId);
        if (existing == null) {
            throw new RegistryException("UNKNOWN_APPLICATION", applicationId);
        }
        if (!existing.state().canTransitionTo(target)) {
            throw new RegistryException("INVALID_STATE_TRANSITION", applicationId);
        }

        TransferApplication updated = existing.withState(target);
        applications.put(applicationId, updated);
        persistApplications();
        return updated;
    }

    // ---------- outbox ----------

    public synchronized List<OutboxEntry> listOutbox() {
        List<OutboxEntry> copy = new ArrayList<>(outbox.values());
        copy.sort((a, b) -> a.entryId().compareTo(b.entryId()));
        return List.copyOf(copy);
    }

    public synchronized int processOutbox() {
        checkWritable();
        int processed = 0;

        List<OutboxEntry> pending = new ArrayList<>();
        for (OutboxEntry e : outbox.values()) {
            if (e.status() == OutboxStatus.PENDING) {
                pending.add(e);
            }
        }
        pending.sort((a, b) -> a.entryId().compareTo(b.entryId()));

        for (OutboxEntry entry : pending) {
            ExternalOrderResult result = rcsPort.createOrder(
                    entry.applicationId(), entry.fromPointId(), entry.toPointId());

            switch (result.outcome()) {
                case ACCEPTED -> {
                    outbox.put(entry.entryId(), entry.withSent(result.externalOrderId()));
                    TransferApplication app = applications.get(entry.applicationId());
                    if (app != null && app.state() == ApplicationState.UNKNOWN) {
                        applications.put(entry.applicationId(),
                                app.withState(ApplicationState.DISPATCHED)
                                        .withExternalOrderId(result.externalOrderId()));
                    }
                    processed++;
                }
                case REJECTED -> {
                    outbox.put(entry.entryId(), entry.withFailed());
                    processed++;
                }
                case TIMEOUT -> {
                    outbox.put(entry.entryId(), entry.withOneMoreAttempt());
                }
                default -> { /* 忽略 */ }
            }
        }

        persistOutbox();
        persistApplications();
        return processed;
    }

    // ---------- inbox ----------

    public synchronized InboxEntry receiveCallback(String applicationId, String eventType) {
        checkWritable();
        checkId(applicationId);
        if (!isValidEventType(eventType)) {
            throw new RegistryException("INVALID_EVENT_TYPE", eventType == null ? "" : eventType);
        }

        String entryId = "INB-" + applicationId + "-" + eventType;

        InboxEntry existing = inbox.get(entryId);
        if (existing != null) {
            return existing;
        }

        TransferApplication app = applications.get(applicationId);
        if (app == null) {
            InboxEntry rejected = new InboxEntry(entryId, applicationId, eventType,
                    InboxStatus.REJECTED, "UNKNOWN_APPLICATION");
            inbox.put(entryId, rejected);
            persistInbox();
            return rejected;
        }

        ApplicationState target = targetState(eventType);
        if (target == null || !app.state().canTransitionTo(target)) {
            InboxEntry rejected = new InboxEntry(entryId, applicationId, eventType,
                    InboxStatus.REJECTED, "INVALID_STATE_TRANSITION");
            inbox.put(entryId, rejected);
            persistInbox();
            return rejected;
        }

        applications.put(applicationId, app.withState(target));
        InboxEntry processed = new InboxEntry(entryId, applicationId, eventType,
                InboxStatus.PROCESSED, "");
        inbox.put(entryId, processed);
        persistApplications();
        persistInbox();
        return processed;
    }

    public synchronized List<InboxEntry> listInbox() {
        List<InboxEntry> copy = new ArrayList<>(inbox.values());
        copy.sort((a, b) -> a.entryId().compareTo(b.entryId()));
        return List.copyOf(copy);
    }

    private boolean isValidEventType(String eventType) {
        return "STARTED".equals(eventType)
                || "COMPLETED".equals(eventType)
                || "FAILED".equals(eventType);
    }

    private ApplicationState targetState(String eventType) {
        return switch (eventType) {
            case "STARTED" -> ApplicationState.EXECUTING;
            case "COMPLETED" -> ApplicationState.COMPLETED;
            case "FAILED" -> ApplicationState.CANCELLED;
            default -> null;
        };
    }

    // ---------- workflow ----------

    private static final List<String> WORKFLOW_STEP_NAMES = List.of("DISPATCH", "EXECUTE", "COMPLETE");

    public synchronized WorkflowInstance startWorkflow(String applicationId) {
        checkWritable();
        checkId(applicationId);

        if (!applications.containsKey(applicationId)) {
            throw new RegistryException("UNKNOWN_APPLICATION", applicationId);
        }
        if (workflows.containsKey(applicationId)) {
            throw new RegistryException("WORKFLOW_EXISTS", applicationId);
        }

        List<WorkflowStep> steps = new ArrayList<>();
        for (String name : WORKFLOW_STEP_NAMES) {
            steps.add(new WorkflowStep(name, WorkflowStepState.PENDING));
        }

        WorkflowInstance wf = new WorkflowInstance(
                "WF-" + applicationId,
                applicationId,
                WorkflowState.RUNNING,
                0,
                List.copyOf(steps));

        workflows.put(applicationId, wf);
        persistWorkflows();
        return wf;
    }

    public synchronized WorkflowInstance advanceWorkflow(String applicationId) {
        checkWritable();
        checkId(applicationId);

        WorkflowInstance wf = workflows.get(applicationId);
        if (wf == null) {
            throw new RegistryException("WORKFLOW_NOT_FOUND", applicationId);
        }
        if (wf.state() != WorkflowState.RUNNING) {
            throw new RegistryException("WORKFLOW_NOT_RUNNING", applicationId);
        }

        int index = wf.currentStepIndex();
        if (index >= WORKFLOW_STEP_NAMES.size()) {
            throw new RegistryException("WORKFLOW_NOT_RUNNING", applicationId);
        }

        WorkflowInstance updated = wf.withStep(index, WorkflowStepState.COMPLETED);
        int nextIndex = index + 1;
        updated = updated.withCurrentStepIndex(nextIndex);

        if (nextIndex >= WORKFLOW_STEP_NAMES.size()) {
            updated = updated.withState(WorkflowState.COMPLETED);
        }

        workflows.put(applicationId, updated);
        persistWorkflows();
        return updated;
    }

    public synchronized Optional<WorkflowInstance> findWorkflow(String applicationId) {
        checkId(applicationId);
        return Optional.ofNullable(workflows.get(applicationId));
    }

    public synchronized List<WorkflowInstance> listWorkflows() {
        List<WorkflowInstance> copy = new ArrayList<>(workflows.values());
        copy.sort((a, b) -> a.instanceId().compareTo(b.instanceId()));
        return List.copyOf(copy);
    }

    // ---------- stats ----------

    public synchronized Stats stats() {
        Map<String, Integer> appsByState = new HashMap<>();
        for (ApplicationState s : ApplicationState.values()) {
            appsByState.put(s.name(), 0);
        }
        for (TransferApplication a : applications.values()) {
            appsByState.merge(a.state().name(), 1, Integer::sum);
        }

        Map<String, Integer> outboxByStatus = new HashMap<>();
        for (OutboxStatus s : OutboxStatus.values()) {
            outboxByStatus.put(s.name(), 0);
        }
        for (OutboxEntry e : outbox.values()) {
            outboxByStatus.merge(e.status().name(), 1, Integer::sum);
        }

        Map<String, Integer> inboxByStatus = new HashMap<>();
        for (InboxStatus s : InboxStatus.values()) {
            inboxByStatus.put(s.name(), 0);
        }
        for (InboxEntry e : inbox.values()) {
            inboxByStatus.merge(e.status().name(), 1, Integer::sum);
        }

        Map<String, Integer> wfByState = new HashMap<>();
        for (WorkflowState s : WorkflowState.values()) {
            wfByState.put(s.name(), 0);
        }
        for (WorkflowInstance w : workflows.values()) {
            wfByState.merge(w.state().name(), 1, Integer::sum);
        }

        return new Stats(
                points.size(),
                applications.size(),
                Map.copyOf(appsByState),
                outbox.size(),
                Map.copyOf(outboxByStatus),
                inbox.size(),
                Map.copyOf(inboxByStatus),
                workflows.size(),
                Map.copyOf(wfByState));
    }
    // ---------- migration ----------

    public synchronized int migrationVersion() {
        if (storage instanceof SqliteStorage s) {
            return s.currentVersion();
        }
        return -1;
    }

    public synchronized List<Migration> migrationList() {
        return SqliteStorage.listMigrations();
    }

    // ---------- health ----------

    public synchronized HealthReport health() {
        String storageStatus;
        int pointsCount;
        try {
            pointsCount = storage.loadPoints().size();
            storageStatus = "OK";
        } catch (RuntimeException e) {
            pointsCount = 0;
            storageStatus = "FAIL";
        }

        int appsCount = applications.size();
        int unknownApps = 0;
        for (TransferApplication a : applications.values()) {
            if (a.state() == ApplicationState.UNKNOWN) {
                unknownApps++;
            }
        }

        int outboxTotal = outbox.size();
        int outboxPending = 0;
        int outboxFailed = 0;
        for (OutboxEntry e : outbox.values()) {
            if (e.status() == OutboxStatus.PENDING) {
                outboxPending++;
            } else if (e.status() == OutboxStatus.FAILED) {
                outboxFailed++;
            }
        }

        int inboxTotal = inbox.size();
        int inboxPending = 0;
        for (InboxEntry e : inbox.values()) {
            if (e.status() == InboxStatus.PENDING) {
                inboxPending++;
            }
        }

        int wfTotal = workflows.size();
        int wfRunning = 0;
        for (WorkflowInstance w : workflows.values()) {
            if (w.state() == WorkflowState.RUNNING) {
                wfRunning++;
            }
        }

        String overall;
        if ("FAIL".equals(storageStatus)) {
            overall = "FAIL";
        } else if (outboxPending > 0 || outboxFailed > 0 || inboxPending > 0 || unknownApps > 0) {
            overall = "WARN";
        } else {
            overall = "OK";
        }

        String mode = readOnly ? "READ_ONLY" : "READ_WRITE";

        return new HealthReport(
                mode,
                storageStatus,
                pointsCount,
                appsCount,
                unknownApps,
                outboxTotal,
                outboxPending,
                outboxFailed,
                inboxTotal,
                inboxPending,
                wfTotal,
                wfRunning,
                overall);
    }

    // ---------- persist ----------

    private void persistPoints() {
        storage.savePoints(new ArrayList<>(points.values()));
    }

    private void persistApplications() {
        storage.saveApplications(new ArrayList<>(applications.values()));
    }

    private void persistOutbox() {
        storage.saveOutbox(new ArrayList<>(outbox.values()));
    }

    private void persistInbox() {
        storage.saveInbox(new ArrayList<>(inbox.values()));
    }

    private void persistWorkflows() {
        storage.saveWorkflows(new ArrayList<>(workflows.values()));
    }

    // ---------- shared validation ----------

    private void checkWritable() {
        if (readOnly) {
            throw new RegistryException("READ_ONLY", "");
        }
    }

    private void checkId(String id) {
        if (id == null || !id.matches(ID_PATTERN)) {
            throw new RegistryException("INVALID_ID", id == null ? "" : id);
        }
    }

    private void checkName(String name) {
        if (name == null || name.isEmpty() || name.length() > NAME_MAX) {
            throw new RegistryException("INVALID_NAME", "");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                throw new RegistryException("INVALID_NAME", "");
            }
        }
    }
}
