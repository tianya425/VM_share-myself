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
        RegistryState loaded = storage.loadState();
        for (Point p : loaded.points()) {
            points.put(p.pointId(), p);
        }
        for (TransferApplication a : loaded.applications()) {
            applications.put(a.applicationId(), a);
        }
        for (OutboxEntry e : loaded.outbox()) {
            outbox.put(e.entryId(), e);
        }
        for (InboxEntry e : loaded.inbox()) {
            inbox.put(e.entryId(), e);
        }
        for (WorkflowInstance w : loaded.workflows()) {
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

        Point point = new Point(pointId, name);
        CandidateState candidate = candidate();
        candidate.points.add(point);
        storage.commitState(candidate.toState());
        publish(candidate);
        return point;
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
        CandidateState candidate = candidate();
        candidate.applications.add(app);
        storage.commitState(candidate.toState());
        publish(candidate);
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

        // ① 先提交 PENDING 意图：失败则不调用外部端口
        OutboxEntry intent = pendingEntry(existing);
        CandidateState intentState = candidate();
        intentState.outbox.add(intent);
        storage.commitState(intentState.toState());
        publish(intentState);

        // ② 调外部端口，再按结果提交（失败则内存停在 ① 的已提交快照）
        CandidateState resultState = candidate();
        OutboxEntry committedIntent = lookupOutbox(resultState.outbox, intent.entryId());
        ExternalOrderResult result = applyCreateResult(resultState, committedIntent);
        storage.commitState(resultState.toState());
        publish(resultState);

        return switch (result.outcome()) {
            case ACCEPTED, TIMEOUT -> applications.get(applicationId);
            case REJECTED -> throw new RegistryException("RCS_REJECTED", applicationId);
            default -> throw new RegistryException("RCS_UNEXPECTED", applicationId);
        };
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
        TransferApplication updated = switch (result.outcome()) {
            case ACCEPTED -> existing.withState(ApplicationState.DISPATCHED)
                    .withExternalOrderId(result.externalOrderId());
            case NOT_FOUND -> throw new RegistryException("EXTERNAL_ORDER_NOT_FOUND", applicationId);
            default -> throw new RegistryException("RCS_UNEXPECTED", applicationId);
        };

        // 申请与 Outbox 同一次提交
        CandidateState candidate = candidate();
        storeApplication(candidate.applications, updated);
        OutboxEntry entry = lookupOutbox(candidate.outbox, "OBX-" + applicationId);
        if (entry != null && entry.status() != OutboxStatus.SENT) {
            storeOutbox(candidate.outbox, entry.withSent(result.externalOrderId()));
        }
        storage.commitState(candidate.toState());
        publish(candidate);
        return applications.get(applicationId);
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

        CandidateState candidate = candidate();
        storeApplication(candidate.applications, existing.withState(target));
        storage.commitState(candidate.toState());
        publish(candidate);
        return applications.get(applicationId);
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

        // PENDING 与 UNCERTAIN 都先 query：不允许直接 create（防止重复下单）
        List<String> entryIds = new ArrayList<>();
        for (OutboxEntry entry : outbox.values()) {
            if (entry.status() == OutboxStatus.PENDING
                    || entry.status() == OutboxStatus.UNCERTAIN) {
                entryIds.add(entry.entryId());
            }
        }
        entryIds.sort(String::compareTo);

        for (String entryId : entryIds) {
            OutboxEntry entry = outbox.get(entryId);
            if (entry == null) {
                continue;
            }

            ExternalOrderResult result = rcsPort.queryOrder(entry.applicationId());
            if (result.outcome() != ExternalOrderResult.Outcome.ACCEPTED) {
                // NOT_FOUND：保持 UNKNOWN/UNCERTAIN 与原状态，不盲目重发
                continue;
            }

            // 每条独立候选 + 独立提交；失败即停（异常向上抛）
            CandidateState candidate = candidate();
            storeOutbox(candidate.outbox,
                    lookupOutbox(candidate.outbox, entryId).withSent(result.externalOrderId()));
            TransferApplication app = lookupApplication(candidate.applications, entry.applicationId());
            if (app != null && (app.state() == ApplicationState.UNKNOWN
                    || app.state() == ApplicationState.CREATED)) {
                storeApplication(candidate.applications,
                        app.withState(ApplicationState.DISPATCHED)
                                .withExternalOrderId(result.externalOrderId()));
            }
            storage.commitState(candidate.toState());
            publish(candidate);
            processed++;
        }
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
            // 去重：已处理过的回调直接返回，不再提交
            return existing;
        }

        TransferApplication app = applications.get(applicationId);
        ApplicationState target = targetState(eventType);
        InboxEntry result;
        if (app == null) {
            result = new InboxEntry(entryId, applicationId, eventType,
                    InboxStatus.REJECTED, "UNKNOWN_APPLICATION");
        } else if (target == null || !app.state().canTransitionTo(target)) {
            // 终态不得倒退：canTransitionTo 对 COMPLETED/CANCELLED 一律 false
            result = new InboxEntry(entryId, applicationId, eventType,
                    InboxStatus.REJECTED, "INVALID_STATE_TRANSITION");
        } else {
            result = new InboxEntry(entryId, applicationId, eventType,
                    InboxStatus.PROCESSED, "");
        }

        // inbox 去重结果与申请状态流转同一次提交
        CandidateState candidate = candidate();
        candidate.inbox.add(result);
        if (result.status() == InboxStatus.PROCESSED) {
            storeApplication(candidate.applications, app.withState(target));
        }
        storage.commitState(candidate.toState());
        publish(candidate);
        return result;
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

        CandidateState candidate = candidate();
        candidate.workflows.add(wf);
        storage.commitState(candidate.toState());
        publish(candidate);
        return workflows.get(applicationId);
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

        String stepName = WORKFLOW_STEP_NAMES.get(index);

        // 本步必须达成的目标状态（T5：只有真的达成才写 COMPLETED）
        ApplicationState target = switch (stepName) {
            case "DISPATCH" -> ApplicationState.DISPATCHED;
            case "EXECUTE" -> ApplicationState.EXECUTING;
            case "COMPLETE" -> ApplicationState.COMPLETED;
            default -> throw new RegistryException("WORKFLOW_UNKNOWN_STEP", stepName);
        };

        // 业务动作与 Workflow 推进写在同一份候选快照上，最后只提交一次
        CandidateState candidate = candidate();
        TransferApplication app = lookupApplication(candidate.applications, applicationId);

        // 申请状态已经到位（例如用户之前手动推进过）就不重复调用业务方法
        boolean rejected = false;
        switch (stepName) {
            case "DISPATCH" -> {
                if (app.state() == ApplicationState.CREATED) {
                    OutboxEntry intent = pendingEntry(app);
                    candidate.outbox.add(intent);
                    ExternalOrderResult result = applyCreateResult(candidate, intent);
                    rejected = result.outcome() == ExternalOrderResult.Outcome.REJECTED;
                    if (!rejected
                            && result.outcome() != ExternalOrderResult.Outcome.ACCEPTED
                            && result.outcome() != ExternalOrderResult.Outcome.TIMEOUT) {
                        throw new RegistryException("RCS_UNEXPECTED", applicationId);
                    }
                }
            }
            case "EXECUTE" -> {
                if (app.state() == ApplicationState.DISPATCHED) {
                    storeApplication(candidate.applications,
                            app.withState(ApplicationState.EXECUTING));
                }
            }
            case "COMPLETE" -> {
                if (app.state() == ApplicationState.EXECUTING) {
                    storeApplication(candidate.applications,
                            app.withState(ApplicationState.COMPLETED));
                }
            }
            default -> throw new RegistryException("WORKFLOW_UNKNOWN_STEP", stepName);
        }

        // T5：只有目标阶段真的达成（达到或越过），才写 COMPLETED 并推进 index。
        // 超时（UNKNOWN）、REJECTED、CANCELLED、状态不符时：步骤保持 PENDING、currentStepIndex 不动、
        // workflow 保持 RUNNING；但本步产生的业务结果（如 UNKNOWN/UNCERTAIN、FAILED）照常落盘。
        //
        // 说明：手动推进会让申请状态跑到本步目标之前（例如先 dispatch + callback STARTED，
        // 再 workflow advance 的 DISPATCH 步），此时该阶段已达成，步骤直接追平为 COMPLETED；
        // 这正是 30 个旧 case 中 sqlite-basic 的既有语义（否则 RUNNING 1/3 会退成 0/3）。
        TransferApplication afterAction = lookupApplication(candidate.applications, applicationId);
        if (afterAction != null && hasReached(afterAction.state(), target)) {
            WorkflowInstance updated = wf.withStep(index, WorkflowStepState.COMPLETED)
                    .withCurrentStepIndex(index + 1);
            if (index + 1 >= WORKFLOW_STEP_NAMES.size()) {
                updated = updated.withState(WorkflowState.COMPLETED);
            }
            storeWorkflow(candidate.workflows, updated);
        }

        // application 的业务结果与 workflow 同一次提交
        storage.commitState(candidate.toState());
        publish(candidate);

        if (rejected) {
            throw new RegistryException("RCS_REJECTED", applicationId);
        }
        return workflows.get(applicationId);
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
            pointsCount = storage.loadState().points().size();
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
        int outboxUncertain = 0;
        int outboxFailed = 0;
        for (OutboxEntry e : outbox.values()) {
          if (e.status() == OutboxStatus.PENDING) {
           outboxPending++;
          } else if (e.status() == OutboxStatus.UNCERTAIN) {
            outboxUncertain++;
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
                outboxUncertain,
                outboxFailed,
                inboxTotal,
                inboxPending,
                wfTotal,
                wfRunning,
                overall);
    }

    // ---------- 提交合同（T4：候选快照 → 单次 commitState → 成功后发布内存） ----------

    /**
     * 候选状态：从已提交状态深拷贝出来的可改副本。
     *
     * <p>写入口只改这个副本，改完 {@code storage.commitState(candidate.toState())} 一次性提交；
     * 提交成功后才 {@link #publish} 到内存。提交失败时内存与已提交状态都保持原样。
     */
    private static final class CandidateState {

        private final List<Point> points;
        private final List<TransferApplication> applications;
        private final List<OutboxEntry> outbox;
        private final List<InboxEntry> inbox;
        private final List<WorkflowInstance> workflows;

        CandidateState(RegistryState committed) {
            this.points = new ArrayList<>(committed.points());
            this.applications = new ArrayList<>(committed.applications());
            this.outbox = new ArrayList<>(committed.outbox());
            this.inbox = new ArrayList<>(committed.inbox());
            this.workflows = new ArrayList<>(committed.workflows());
        }

        RegistryState toState() {
            return new RegistryState(points, applications, outbox, inbox, workflows);
        }
    }

    private RegistryState snapshot() {
        return new RegistryState(new ArrayList<>(points.values()),
                new ArrayList<>(applications.values()),
                new ArrayList<>(outbox.values()),
                new ArrayList<>(inbox.values()),
                new ArrayList<>(workflows.values()));
    }

    private CandidateState candidate() {
        return new CandidateState(snapshot());
    }

    /**
     * 提交成功之后，才把候选状态发布为内存状态。
     */
    private void publish(CandidateState candidate) {
        points.clear();
        for (Point point : candidate.points) {
            points.put(point.pointId(), point);
        }
        applications.clear();
        for (TransferApplication application : candidate.applications) {
            applications.put(application.applicationId(), application);
        }
        outbox.clear();
        for (OutboxEntry entry : candidate.outbox) {
            outbox.put(entry.entryId(), entry);
        }
        inbox.clear();
        for (InboxEntry entry : candidate.inbox) {
            inbox.put(entry.entryId(), entry);
        }
        workflows.clear();
        for (WorkflowInstance workflow : candidate.workflows) {
            workflows.put(workflow.applicationId(), workflow);
        }
    }

    // ---------- 候选态上的列表操作 ----------

    private static TransferApplication lookupApplication(List<TransferApplication> list, String applicationId) {
        for (TransferApplication application : list) {
            if (application.applicationId().equals(applicationId)) {
                return application;
            }
        }
        return null;
    }

    private static void storeApplication(List<TransferApplication> list, TransferApplication updated) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).applicationId().equals(updated.applicationId())) {
                list.set(i, updated);
                return;
            }
        }
        list.add(updated);
    }

    private static OutboxEntry lookupOutbox(List<OutboxEntry> list, String entryId) {
        for (OutboxEntry entry : list) {
            if (entry.entryId().equals(entryId)) {
                return entry;
            }
        }
        return null;
    }

    private static void storeOutbox(List<OutboxEntry> list, OutboxEntry updated) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).entryId().equals(updated.entryId())) {
                list.set(i, updated);
                return;
            }
        }
        list.add(updated);
    }

    private static void storeWorkflow(List<WorkflowInstance> list, WorkflowInstance updated) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).applicationId().equals(updated.applicationId())) {
                list.set(i, updated);
                return;
            }
        }
        list.add(updated);
    }

    /**
     * 应用状态是否已经达到或越过某个阶段（T5 的步骤完成条件）。
     *
     * <p>正常主线：CREATED(0) → DISPATCHED(1) → EXECUTING(2) → COMPLETED(3)。
     * 达到目标阶段即算达成；手动推进造成的"跑得更远"也算（步骤直接追平）。
     * UNKNOWN / CANCELLED 不在推进主线上，一律不算达成。
     */
    private static boolean hasReached(ApplicationState current, ApplicationState target) {
        int targetRank = lifecycleRank(target);
        return targetRank > 0 && lifecycleRank(current) >= targetRank;
    }

    private static int lifecycleRank(ApplicationState state) {
        return switch (state) {
            case CREATED -> 0;
            case DISPATCHED -> 1;
            case EXECUTING -> 2;
            case COMPLETED -> 3;
            case UNKNOWN, CANCELLED -> -1;
        };
    }

    private static OutboxEntry pendingEntry(TransferApplication application) {        return new OutboxEntry(
                "OBX-" + application.applicationId(),
                application.applicationId(),
                application.fromPointId(),
                application.toPointId(),
                OutboxStatus.PENDING,
                null,
                0);
    }

    /**
     * 调外部 create 端口，并把结果写进候选状态（不提交）。
     *
     * <p>REJECTED 只把 Outbox 标 FAILED、申请保持原状态；由调用方决定是否提交后抛错。
     */
    private ExternalOrderResult applyCreateResult(CandidateState candidate, OutboxEntry intent) {
        TransferApplication app = lookupApplication(candidate.applications, intent.applicationId());
        ExternalOrderResult result = rcsPort.createOrder(
                intent.applicationId(), intent.fromPointId(), intent.toPointId());
        switch (result.outcome()) {
            case ACCEPTED -> {
                storeOutbox(candidate.outbox, intent.withSent(result.externalOrderId()));
                if (app != null) {
                    storeApplication(candidate.applications,
                            app.withState(ApplicationState.DISPATCHED)
                                    .withExternalOrderId(result.externalOrderId()));
                }
            }
            case TIMEOUT -> {
                // 外部结果不确定：申请进入 UNKNOWN，Outbox 进入 UNCERTAIN，后续只允许 query
                storeOutbox(candidate.outbox, intent.withUncertain());
                if (app != null) {
                    storeApplication(candidate.applications,
                            app.withState(ApplicationState.UNKNOWN));
                }
            }
            case REJECTED -> storeOutbox(candidate.outbox, intent.withFailed());
            default -> {
                // createOrder 不应返回 NOT_FOUND；不改候选状态
            }
        }
        return result;
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
