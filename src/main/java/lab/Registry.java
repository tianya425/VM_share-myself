package lab;

import lab.simulation.resource.Destination;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceAvailability;
import lab.simulation.resource.ResourceWorldSnapshot;
import lab.simulation.resource.ServicePoint;
import lab.simulation.resource.VirtualResource;
import lab.simulation.resource.VirtualResourceWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 业务聚合入口：点位、申请、Outbox、Inbox、Workflow 与资源世界的规则与查询。
 *
 * <p>写入口统一遵循「候选快照 → 单次 {@code storage.commitState} → 成功后发布内存」的提交合同：
 * 提交失败时内存与已提交状态都保持原样。只读模式在写任何 SQL 之前就拒绝。
 *
 * <p>资源写入口（Part B）把资源世界变更与业务状态变更放进同一候选快照，一次提交。
 */
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
    private ResourceWorldSnapshot resourceWorld;

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
        this.resourceWorld = loaded.resourceWorld();
    }

    // ---------- point ----------

    /**
     * 登记点位。
     *
     * @param pointId 点位编号
     * @param name    点位名称（1—40 字符，不含空白）
     * @return 登记后的点位
     * @throws RegistryException 编号或名称非法、点位已存在
     */
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

    /** 按编号查询点位。 */
    public Optional<Point> findPoint(String pointId) {
        checkId(pointId);
        return Optional.ofNullable(points.get(pointId));
    }

    /** 列出全部点位（按编号升序）。 */
    public List<Point> listPoints() {
        List<Point> copy = new ArrayList<>(points.values());
        copy.sort((a, b) -> a.pointId().compareTo(b.pointId()));
        return List.copyOf(copy);
    }

    // ---------- application ----------

    /**
     * 登记搬运申请。
     *
     * <p>同编号同起终点 → 幂等重放；同编号不同起终点 → {@code APPLICATION_CONFLICT}。
     *
     * @throws RegistryException 编号非法、点位不存在、起终点相同或编号冲突
     */
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

    /** 按编号查询申请。 */
    public Optional<TransferApplication> findApplication(String applicationId) {
        checkId(applicationId);
        return Optional.ofNullable(applications.get(applicationId));
    }

    /** 列出全部申请（按编号升序）。 */
    public List<TransferApplication> listApplications() {
        List<TransferApplication> copy = new ArrayList<>(applications.values());
        copy.sort((a, b) -> a.applicationId().compareTo(b.applicationId()));
        return List.copyOf(copy);
    }

    // ---------- state transitions ----------

    /**
     * 下发申请：先提交 PENDING 意图，再调用外部端口，按结果提交。
     *
     * @throws RegistryException 申请不存在、状态不是 CREATED 或外部拒绝
     */
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

    /** 对 UNKNOWN 申请做外部对账（仅 ACCEPTED 恢复为 DISPATCHED）。 */
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

    /** 状态转换 DISPATCHED → EXECUTING。 */
    public synchronized TransferApplication executeApplication(String applicationId) {
        checkWritable();
        return transition(applicationId, ApplicationState.EXECUTING);
    }

    /** 状态转换 EXECUTING → COMPLETED。 */
    public synchronized TransferApplication completeApplication(String applicationId) {
        checkWritable();
        return transition(applicationId, ApplicationState.COMPLETED);
    }

    /** 状态转换：规则允许的非终态 → CANCELLED。 */
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

    /** 列出全部 Outbox 记录（按编号升序）。 */
    public synchronized List<OutboxEntry> listOutbox() {
        List<OutboxEntry> copy = new ArrayList<>(outbox.values());
        copy.sort((a, b) -> a.entryId().compareTo(b.entryId()));
        return List.copyOf(copy);
    }

    /**
     * 处理待恢复的 Outbox：PENDING 与 UNCERTAIN 都先 query，不直接重发 create。
     *
     * @return 本次实际处理成功的条数
     */
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

    /** 接收外部回调：写入 Inbox 去重记录，并按事件类型推进申请状态。 */
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

    /** 列出全部 Inbox 记录（按编号升序）。 */
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

    /** 为申请启动固定 3 步工作流（DISPATCH / EXECUTE / COMPLETE）。 */
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

    /**
     * 推进一步：先在同一候选上执行业务动作，只有申请状态达到或越过该步目标
     * 才把本步标记完成并前进；超时、拒绝、取消时步骤保持 PENDING、索引不动。
     */
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

    /** 按申请编号查询工作流。 */
    public synchronized Optional<WorkflowInstance> findWorkflow(String applicationId) {
        checkId(applicationId);
        return Optional.ofNullable(workflows.get(applicationId));
    }

    /** 列出全部工作流（按申请编号升序）。 */
    public synchronized List<WorkflowInstance> listWorkflows() {
        List<WorkflowInstance> copy = new ArrayList<>(workflows.values());
        copy.sort((a, b) -> a.instanceId().compareTo(b.instanceId()));
        return List.copyOf(copy);
    }

    // ---------- stats ----------

    /** 生成统计快照。 */
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

    /** 当前 schema 版本号。 */
    public synchronized int migrationVersion() {
        if (storage instanceof SqliteStorage s) {
            return s.currentVersion();
        }
        return -1;
    }

    /** 内置迁移列表。 */
    public synchronized List<Migration> migrationList() {
        return SqliteStorage.listMigrations();
    }

    // ---------- health ----------

    /** 生成健康检查快照。 */
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

    // ---------- resource（Part B / PR4：资源领域领域模型的事务入口） ----------

    /**
     * 新增 Destination：同 ID 同 name 幂等；同 ID 不同 name → {@code RESOURCE_DUPLICATE_CONFLICT}。
     *
     * <p>标量校验（ID 规则、name trim 后 1—40 并保存规范化值）由
     * {@link Destination} 构造器完成，本方法不重复校验（STU-SIM-02-R1 §8.1/§8.2）。
     */
    public synchronized Destination addDestination(String destinationId, String name) {
        checkWritable();

        VirtualResourceWorld current = world();
        Destination existing = current.findDestination(destinationId);
        VirtualResourceWorld next = current.addDestination(new Destination(destinationId, name));
        if (existing == null) {
            commitWorld(next);
        }
        return existing != null ? existing : next.findDestination(destinationId);
    }

    /**
     * 新增 ServicePoint（三字段合同）：Destination 与 Registry Point 都必须已存在。
     */
    public synchronized ServicePoint addServicePoint(String servicePointId, String destinationId,
                                                     String pointId) {
        checkWritable();
        checkId(servicePointId);
        checkId(destinationId);
        checkId(pointId);
        if (!points.containsKey(pointId)) {
            throw new RegistryException("UNKNOWN_POINT", pointId);
        }

        VirtualResourceWorld current = world();
        ServicePoint existing = current.findServicePoint(servicePointId);
        VirtualResourceWorld next = current.addServicePoint(
                new ServicePoint(servicePointId, destinationId, pointId));
        if (existing == null) {
            commitWorld(next);
        }
        return existing != null ? existing : next.findServicePoint(servicePointId);
    }

    /**
     * 新增 VirtualResource（容量固定 1，默认 ENABLED）。
     */
    public synchronized VirtualResource addVirtualResource(String resourceId, String servicePointId) {
        checkWritable();
        checkId(resourceId);
        checkId(servicePointId);

        VirtualResourceWorld current = world();
        VirtualResource existing = current.findResource(resourceId);
        VirtualResourceWorld next = current.addVirtualResource(new VirtualResource(resourceId, servicePointId));
        if (existing == null) {
            commitWorld(next);
        }
        return existing != null ? existing : next.findResource(resourceId);
    }

    /**
     * 设置资源可用性：DISABLED 不驱逐 holder；ENABLED 且无 holder 时晋升队首。
     */
    public synchronized VirtualResource setVirtualResourceAvailability(String resourceId,
                                                                       ResourceAvailability availability) {
        checkWritable();
        checkId(resourceId);
        if (availability == null) {
            throw new RegistryException("RESOURCE_INVALID_AVAILABILITY", resourceId);
        }

        VirtualResourceWorld current = world();
        VirtualResourceWorld next = current.setAvailability(resourceId, availability);
        if (next != current) {
            commitWorld(next);
        }
        return next.findResource(resourceId);
    }

    /**
     * 请求预约（STU-SIM-02-R1 §9.1 固定算法，顺序逐条一致）：
     * 校验参数 → application 存在 → 既有 Reservation 判断（终态/幂等/冲突）
     * → 无既有才检查 CREATED → Destination/ServicePoint/Resource 依次存在性
     * → 引用链 → availability 与 holder 决定 HELD/WAITING → 候选快照单次 commitState
     * → 提交成功后发布内存并返回 HELD/WAITING decision。
     *
     * <p>「相同目标」= applicationId + destinationId + servicePointId + resourceId 四元组
     * （§9.2）；排序键不参与判断，重复请求保留第一次排序键。
     * 同一申请不同目标是正常 {@link ReservationDecision.DecisionType#CONFLICT} 决策，
     * 不抛异常（§9.3）。
     */
    public synchronized ReservationDecision requestReservation(String applicationId,
                                                               String destinationId,
                                                               String servicePointId,
                                                               String resourceId,
                                                               long requestedOffsetMillis,
                                                               long requestedLogicalSequence) {
        checkWritable();
        // 1. 校验 applicationId、destinationId、servicePointId、resourceId 和排序键。
        checkId(applicationId);
        checkId(destinationId);
        checkId(servicePointId);
        checkId(resourceId);
        if (requestedOffsetMillis < 0 || requestedLogicalSequence < 1) {
            throw new RegistryException("RESERVATION_INVALID_ORDER", applicationId);
        }

        // 2. 查找 application；不存在则 RESERVATION_APPLICATION_NOT_FOUND。
        TransferApplication application = applications.get(applicationId);
        if (application == null) {
            throw new RegistryException("RESERVATION_APPLICATION_NOT_FOUND", applicationId);
        }

        // 3. 按 applicationId 查找既有 Reservation。
        VirtualResourceWorld current = world();
        Reservation existing = current.findReservation(applicationId);
        if (existing != null) {
            // 4. 终态重开 → RESERVATION_TERMINAL。
            if (existing.isTerminal()) {
                throw new RegistryException("RESERVATION_TERMINAL", applicationId);
            }
            boolean sameTarget = existing.applicationId().equals(applicationId)
                    && existing.destinationId().equals(destinationId)
                    && existing.servicePointId().equals(servicePointId)
                    && existing.resourceId().equals(resourceId);
            if (sameTarget) {
                // 5. WAITING/HELD 且目标完全相同 → IDEMPOTENT（不提交新状态）。
                return new ReservationDecision(ReservationDecision.DecisionType.IDEMPOTENT,
                        existing, "reason=SAME_ACTIVE_RESERVATION");
            }
            // 6. WAITING/HELD 且目标不同 → CONFLICT（正常决策，不抛异常、不提交）。
            return new ReservationDecision(ReservationDecision.DecisionType.CONFLICT,
                    existing, "reason=DIFFERENT_ACTIVE_TARGET");
        }

        // 7. 只有不存在既有 Reservation 时，检查 application.state == CREATED。
        if (application.state() != ApplicationState.CREATED) {
            throw new RegistryException("RESERVATION_APPLICATION_STATE", applicationId);
        }

        // 8. 依次检查 Destination、ServicePoint、Resource 是否存在（§9.3 固定错误码）。
        Destination destination = current.findDestination(destinationId);
        if (destination == null) {
            throw new RegistryException("RESOURCE_DESTINATION_NOT_FOUND", destinationId);
        }
        ServicePoint servicePoint = current.findServicePoint(servicePointId);
        if (servicePoint == null) {
            throw new RegistryException("RESOURCE_SERVICE_POINT_NOT_FOUND", servicePointId);
        }
        VirtualResource resource = current.findResource(resourceId);
        if (resource == null) {
            throw new RegistryException("RESOURCE_NOT_FOUND", resourceId);
        }

        // 9. 检查 Destination→ServicePoint→Resource→Point 引用链。
        if (!servicePoint.destinationId().equals(destinationId)
                || !resource.servicePointId().equals(servicePointId)
                || !points.containsKey(servicePoint.pointId())) {
            throw new RegistryException("RESOURCE_TARGET_MISMATCH", applicationId);
        }

        // 10. 根据 availability 和 holder 决定 HELD 或 WAITING（容量固定 1）。
        boolean canHold = resource.availability() == ResourceAvailability.ENABLED
                && !current.hasHeld(resourceId);
        ReservationState state = canHold ? ReservationState.HELD : ReservationState.WAITING;
        Reservation created = new Reservation(
                Reservation.idFor(applicationId),
                applicationId,
                destinationId,
                servicePointId,
                resourceId,
                state,
                requestedOffsetMillis,
                requestedLogicalSequence);

        // 11-13. 构造完整 next RegistryState → commitState(next) → 成功后发布内存并返回 decision。
        VirtualResourceWorld next = current.addReservation(created);
        commitWorld(next);
        Reservation committed = next.findReservation(applicationId);
        String detail = state == ReservationState.HELD
                ? "reason=RESOURCE_AVAILABLE"
                : resource.availability() == ResourceAvailability.DISABLED
                        ? "reason=RESOURCE_DISABLED"
                        : "reason=RESOURCE_BUSY";
        return new ReservationDecision(
                state == ReservationState.HELD
                        ? ReservationDecision.DecisionType.HELD
                        : ReservationDecision.DecisionType.WAITING,
                committed,
                detail);
    }

    /**
     * 释放 HELD 预约；成功后晋升该资源队首。
     */
    public synchronized Reservation releaseReservation(String applicationId) {
        checkWritable();
        checkId(applicationId);

        VirtualResourceWorld next = world().release(applicationId);
        commitWorld(next);
        return next.findReservation(applicationId);
    }

    /**
     * 取消 HELD / WAITING 预约；取消 HELD 后晋升该资源队首。
     */
    public synchronized Reservation cancelReservation(String applicationId) {
        checkWritable();
        checkId(applicationId);

        VirtualResourceWorld next = world().cancel(applicationId);
        commitWorld(next);
        return next.findReservation(applicationId);
    }

    /**
     * 当前资源世界快照（深不可变）。
     */
    public synchronized ResourceWorldSnapshot resourceWorldSnapshot() {
        return resourceWorld;
    }

    // ---------- 资源世界的提交合同 ----------

    private VirtualResourceWorld world() {
        return new VirtualResourceWorld(resourceWorld);
    }

    /**
     * 与其它写入口相同：先改候选快照，再单次 commitState，提交成功后才发布到内存。
     * 提交失败时内存与已提交状态都保持原样，不留幽灵预约。
     */
    private void commitWorld(VirtualResourceWorld next) {
        CandidateState candidate = candidate();
        candidate.resourceWorld = next.snapshot();
        storage.commitState(candidate.toState());
        publish(candidate);
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
        private ResourceWorldSnapshot resourceWorld;

        CandidateState(RegistryState committed) {
            this.points = new ArrayList<>(committed.points());
            this.applications = new ArrayList<>(committed.applications());
            this.outbox = new ArrayList<>(committed.outbox());
            this.inbox = new ArrayList<>(committed.inbox());
            this.workflows = new ArrayList<>(committed.workflows());
            this.resourceWorld = committed.resourceWorld();
        }

        RegistryState toState() {
            return new RegistryState(points, applications, outbox, inbox, workflows, resourceWorld);
        }
    }

    private RegistryState snapshot() {
        return new RegistryState(new ArrayList<>(points.values()),
                new ArrayList<>(applications.values()),
                new ArrayList<>(outbox.values()),
                new ArrayList<>(inbox.values()),
                new ArrayList<>(workflows.values()),
                resourceWorld);
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
        resourceWorld = candidate.resourceWorld;
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
