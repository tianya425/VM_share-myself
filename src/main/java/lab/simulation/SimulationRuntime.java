package lab.simulation;

import lab.ApplicationState;
import lab.MemoryStorage;
import lab.OutboxEntry;
import lab.OutboxStatus;
import lab.Registry;
import lab.RegistryException;
import lab.TransferApplication;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ResourceWorldSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仿真运行时。
 *
 * <p>按顺序驱动场景：建立全新的 Storage / Registry / Clock / Fake，
 * 依次推进虚拟时间并执行事件，收集外部交互、状态轨迹、调用尝试、Oracle 结果和运行结果。
 *
 * <p>运行语义：
 * <ul>
 *   <li>单次运行：同一个实例第二次调用 {@link #run()} 抛 SIM_RUNTIME_ALREADY_USED</li>
 *   <li>首错即停：第一个失败事件之后的事件全部记为 EVENT_SKIPPED，
 *       不推进时钟、不调 Registry、不调外部端口、不改状态</li>
 *   <li>未消费外部脚本 → FAILED + UNCONSUMED_EXTERNAL_SCRIPT</li>
 * </ul>
 *
 * <p>运行状态：PREPARED → RUNNING → COMPLETED / FAILED / INCONCLUSIVE。
 */
public class SimulationRuntime {

    private final ScenarioPackage scenario;
    private final RunFactLog facts = new RunFactLog();
    private final List<EventRecord> timeline = new ArrayList<>();
    private String runStatus = "PREPARED";
    private boolean used;
    private RuntimeContext ctx;
    private int planned;
    private int executed;
    private int skipped;

    public SimulationRuntime(ScenarioPackage scenario) {
        this.scenario = scenario;
    }

    /**
     * 执行整个场景；同一个实例只允许运行一次。
     */
    public SimulationRunResult run() {
        if (used) {
            throw new ScenarioValidationException("SIM_RUNTIME_ALREADY_USED", scenario.scenarioId());
        }
        used = true;
        runStatus = "RUNNING";

        ctx = setup();
        Execution execution = executeEvents(ctx);
        planned = scenario.events().size();
        executed = execution.pass() + execution.fail();
        skipped = execution.skipped();

        List<OracleResult> oracleResults = checkOracle(ctx, execution);
        RunFailure firstFailure = decideFirstFailure(ctx, execution, oracleResults);
        String finalStatus = firstFailure == null
                ? SimulationRunResult.COMPLETED
                : SimulationRunResult.FAILED;
        runStatus = finalStatus;

        return buildResult(ctx, execution, oracleResults, finalStatus, firstFailure);
    }

    // ---------- 1. 建立运行时上下文 ----------

    private RuntimeContext setup() {
        VirtualClock clock = new VirtualClock(scenario.startAt());
        MemoryStorage storage = new MemoryStorage();
        ScriptedFakeRcsAdapter fake = new ScriptedFakeRcsAdapter(clock, scenario.externalScripts(), facts);
        Registry registry = new Registry(storage, fake, false);
        RegistrySimulationTarget target = new RegistrySimulationTarget(registry);
        return new RuntimeContext(clock, storage, registry, fake, target);
    }

    // ---------- 2. 执行事件（首错即停） ----------

    private Execution executeEvents(RuntimeContext ctx) {
        int eventPass = 0;
        int eventFail = 0;
        int eventSkipped = 0;
        RunFailure firstFailure = null;

        for (ScenarioEvent event : scenario.events()) {
            if (firstFailure != null) {
                // 首错之后：只记 EVENT_SKIPPED，不推进时钟、不调 Registry、不调 Port
                timeline.add(new EventRecord(event, "EVENT_SKIPPED", "", ctx.clock().now()));
                eventSkipped++;
                continue;
            }

            ctx.clock().advanceTo(event.offsetMillis());
            ResourceWorldSnapshot before = isResourceAction(event.action())
                    ? ctx.target().resourceWorldSnapshot()
                    : null;
            try {
                dispatch(ctx, event);
                if (before != null) {
                    facts.recordResourceStep(new ResourceStep(
                            event.eventId(),
                            event.action(),
                            resourceIdOf(event),
                            valueOf(event),
                            before,
                            ctx.target().resourceWorldSnapshot(),
                            facts.reservationDecisions().get(event.eventId())));
                }
                timeline.add(new EventRecord(event, "EVENT_PASS", "", ctx.clock().now()));
                eventPass++;
            } catch (RegistryException | ScenarioValidationException e) {
                timeline.add(new EventRecord(event, "EVENT_FAIL", describe(e), ctx.clock().now()));
                eventFail++;
                firstFailure = new RunFailure(event.eventId(), code(e));
            }
            recordStateTransitions(ctx, event);
        }

        return new Execution(eventPass, eventFail, eventSkipped, firstFailure);
    }

    private void dispatch(RuntimeContext ctx, ScenarioEvent event) {
        SimulationTarget target = ctx.target();
        List<String> args = event.arguments();
        switch (event.action()) {
            case POINT_ADD -> target.pointAdd(args.get(0), args.get(1));
            case APPLICATION_ADD -> target.applicationAdd(args.get(0), args.get(1), args.get(2));
            case APPLICATION_DISPATCH -> target.applicationDispatch(args.get(0));
            case APPLICATION_QUERY -> target.applicationQuery(args.get(0));
            case APPLICATION_CALLBACK -> target.applicationCallback(args.get(0), args.get(1));
            case OUTBOX_PROCESS -> target.outboxProcess();
            case WORKFLOW_START -> target.workflowStart(args.get(0));
            case WORKFLOW_ADVANCE -> target.workflowAdvance(args.get(0));
            case DESTINATION_ADD -> target.destinationAdd(args.get(0), args.get(1));
            case SERVICE_POINT_ADD -> target.servicePointAdd(
                    args.get(0), args.get(1), args.get(2), args.get(3));
            case VIRTUAL_RESOURCE_ADD -> target.virtualResourceAdd(args.get(0), args.get(1));
            case RESOURCE_SET -> target.resourceSetAvailability(args.get(0), args.get(1));
            case RESERVATION_REQUEST -> {
                ReservationDecision decision = target.reservationRequest(
                        args.get(0), args.get(1), args.get(2), args.get(3),
                        event.offsetMillis(), event.logicalSequence());
                if (decision == null) {
                    throw new RegistryException("RESERVATION_DECISION_MISSING", event.eventId());
                }
                facts.recordReservationDecision(event.eventId(), decision);
            }
            case RESERVATION_RELEASE -> target.reservationRelease(args.get(0));
            case RESERVATION_CANCEL -> target.reservationCancel(args.get(0));
            case WORLD_RESTART -> restart(ctx, event);
        }
    }

    /**
     * 保留同一 Storage / Clock / Fake，丢弃 Registry 与 Target，
     * 用 {@code loadState()} 重建并重派生结构；不触发任何外部调用。
     */
    private void restart(RuntimeContext ctx, ScenarioEvent event) {
        String beforeDigest = ctx.target().resourceWorldSnapshot().canonicalDigest();
        Registry reloaded = new Registry(ctx.storage(), ctx.fake(), false);
        ctx.replaceTarget(reloaded);
        String afterDigest = ctx.target().resourceWorldSnapshot().canonicalDigest();
        facts.recordRestartPair(new RestartPair(event.eventId(), beforeDigest, afterDigest));
    }

    private static boolean isResourceAction(ScenarioEvent.Action action) {
        return switch (action) {
            case DESTINATION_ADD, SERVICE_POINT_ADD, VIRTUAL_RESOURCE_ADD, RESOURCE_SET,
                 RESERVATION_REQUEST, RESERVATION_RELEASE, RESERVATION_CANCEL, WORLD_RESTART -> true;
            default -> false;
        };
    }

    private static String resourceIdOf(ScenarioEvent event) {
        return switch (event.action()) {
            case VIRTUAL_RESOURCE_ADD, RESOURCE_SET -> event.arguments().get(0);
            case RESERVATION_REQUEST -> event.arguments().get(3);
            default -> "";
        };
    }

    private static String valueOf(ScenarioEvent event) {
        return event.action() == ScenarioEvent.Action.RESOURCE_SET
                ? event.arguments().get(1)
                : "";
    }

    /**
     * 记录一个事件执行后的状态快照（每个申请一条）。
     */
    private void recordStateTransitions(RuntimeContext ctx, ScenarioEvent event) {
        List<OutboxEntry> outbox = ctx.registry().listOutbox();
        for (TransferApplication app : ctx.registry().listApplications()) {
            OutboxStatus status = null;
            for (OutboxEntry entry : outbox) {
                if (entry.applicationId().equals(app.applicationId())) {
                    status = entry.status();
                    break;
                }
            }
            facts.recordTransition(new StateTransition(
                    facts.nextSequence(), ctx.clock().now(), event.eventId(),
                    app.applicationId(), app.state(), status));
        }
    }

    private String code(RuntimeException e) {
        if (e instanceof RegistryException registryException) {
            return registryException.code();
        }
        if (e instanceof ScenarioValidationException validationException) {
            return validationException.code();
        }
        return "INTERNAL_ERROR";
    }

    private String describe(RuntimeException e) {
        if (e instanceof RegistryException registryException) {
            return registryException.code()
                    + (registryException.detail().isEmpty() ? "" : " " + registryException.detail());
        }
        if (e instanceof ScenarioValidationException validationException) {
            return validationException.code()
                    + (validationException.detail().isEmpty() ? "" : " " + validationException.detail());
        }
        return "INTERNAL_ERROR";
    }

    // ---------- 3. 执行 Oracle ----------

    private List<OracleResult> checkOracle(RuntimeContext ctx, Execution execution) {
        List<TransferApplication> apps = ctx.registry().listApplications();
        List<OutboxEntry> outbox = ctx.registry().listOutbox();

        OracleContext oracleCtx = new OracleContext(
                apps,
                outbox,
                ctx.fake().interactions(),
                facts.attempts(),
                facts.transitions(),
                execution.firstFailure() == null ? List.of() : List.of(execution.firstFailure()),
                planned,
                executed,
                skipped,
                SimulationRunResult.EVIDENCE_LEVEL,
                ctx.fake().getClass().getSimpleName(),
                0,
                ctx.target().resourceWorldSnapshot(),
                facts.resourceSteps(),
                facts.restartPairs());
        return new InvariantOracle(scenario).check(oracleCtx);
    }

    // ---------- 4. 决定首个失败 ----------

    /**
     * 失败优先级：事件失败 > 未消费脚本 > Oracle 不满足。
     */
    private RunFailure decideFirstFailure(RuntimeContext ctx, Execution execution,
                                          List<OracleResult> oracleResults) {
        if (execution.firstFailure() != null) {
            return execution.firstFailure();
        }
        if (!allScriptsConsumed(ctx.fake())) {
            return new RunFailure(null, "UNCONSUMED_EXTERNAL_SCRIPT");
        }
        for (OracleResult result : oracleResults) {
            if (result.status() == OracleResult.Status.FAIL) {
                return new RunFailure(null, result.oracleId());
            }
        }
        return null;
    }

    private boolean allScriptsConsumed(ScriptedFakeRcsAdapter fake) {
        for (ExternalScript s : scenario.externalScripts()) {
            if (fake.callCount(s.operation()) < s.ordinal()) {
                return false;
            }
        }
        return true;
    }

    // ---------- 5. 构造结果 ----------

    private SimulationRunResult buildResult(RuntimeContext ctx, Execution execution,
                                            List<OracleResult> oracleResults,
                                            String status, RunFailure firstFailure) {
        return new SimulationRunResult(
                SimulationRunResult.SCHEMA_VERSION,
                status,
                scenario.scenarioId(),
                scenario.version(),
                planned,
                executed,
                execution.pass(),
                execution.fail(),
                skipped,
                firstFailure == null ? null : firstFailure.eventId(),
                firstFailure == null ? null : firstFailure.code(),
                SimulationRunResult.EVIDENCE_LEVEL,
                0,
                ctx.fake().callCount(ExternalScript.Operation.CREATE),
                ctx.fake().callCount(ExternalScript.Operation.QUERY),
                ctx.fake().validExternalOrderCount(),
                timeline(),
                ctx.fake().interactions(),
                oracleResults);
    }

    // ---------- 快照访问器 ----------

    /**
     * 事件时间线快照。
     */
    public List<EventRecord> timeline() {
        return List.copyOf(timeline);
    }

    /**
     * 外部交互快照（脚本化且产生结果的调用）。
     */
    public List<ExternalInteraction> interactions() {
        return ctx == null ? List.of() : ctx.fake().interactions();
    }

    /**
     * 全部外部调用尝试（含未脚本化）。
     */
    public List<ExternalCallAttempt> externalCallAttempts() {
        return facts.attempts();
    }

    /**
     * 事件级状态轨迹。
     */
    public List<StateTransition> stateTransitions() {
        return facts.transitions();
    }

    /**
     * 运行结束时的申请状态。
     */
    public Map<String, ApplicationState> finalApplicationStates() {
        if (ctx == null) {
            return Map.of();
        }
        Map<String, ApplicationState> map = new LinkedHashMap<>();
        for (TransferApplication app : ctx.registry().listApplications()) {
            map.put(app.applicationId(), app.state());
        }
        return Map.copyOf(map);
    }

    /**
     * 运行结束时的 Outbox 状态。
     */
    public Map<String, OutboxStatus> finalOutboxStates() {
        if (ctx == null) {
            return Map.of();
        }
        Map<String, OutboxStatus> map = new LinkedHashMap<>();
        for (OutboxEntry entry : ctx.registry().listOutbox()) {
            map.put(entry.applicationId(), entry.status());
        }
        return Map.copyOf(map);
    }

    /**
     * 每次 {@code RESERVATION_REQUEST} 的决策，按 eventId 存档。
     */
    public Map<String, ReservationDecision> reservationDecisions() {
        return facts.reservationDecisions();
    }

    /**
     * 资源 Action 的执行观察（按执行顺序）。
     */
    public List<ResourceStep> resourceSteps() {
        return facts.resourceSteps();
    }

    /**
     * 每次 {@code WORLD_RESTART} 的前后摘要对（按执行顺序）。
     */
    public List<RestartPair> restartPairs() {
        return facts.restartPairs();
    }

    /**
     * 运行结束时的资源世界规范快照；未运行时为空快照。
     */
    public ResourceWorldSnapshot resourceWorldSnapshot() {
        return ctx == null ? ResourceWorldSnapshot.empty() : ctx.target().resourceWorldSnapshot();
    }

    /**
     * 计划事件数。
     */
    public int plannedCount() {
        return planned;
    }

    /**
     * 已执行事件数。
     */
    public int executedCount() {
        return executed;
    }

    /**
     * 跳过事件数。
     */
    public int skippedCount() {
        return skipped;
    }

    /**
     * 一条事件执行记录。
     *
     * <p>字段固定为 eventId / plannedOffsetMillis / logicalSequence / action /
     * status / virtualOccurredAt / detail。
     */
    public record EventRecord(ScenarioEvent event, String status, String detail, Instant virtualOccurredAt) {

        /**
         * 事件编号。
         */
        public String eventId() {
            return event.eventId();
        }

        /**
         * 计划偏移（毫秒）。
         */
        public long plannedOffsetMillis() {
            return event.offsetMillis();
        }

        /**
         * 逻辑序号。
         */
        public long logicalSequence() {
            return event.logicalSequence();
        }

        /**
         * 动作。
         */
        public ScenarioEvent.Action action() {
            return event.action();
        }
    }

    // ---------- 辅助类型 ----------

    /**
     * 运行时上下文：Storage / Clock / Fake 在一次运行内固定；
     * Registry 与 Target 可被 {@code WORLD_RESTART} 整体替换。
     */
    private static final class RuntimeContext {

        private final VirtualClock clock;
        private final MemoryStorage storage;
        private final ScriptedFakeRcsAdapter fake;
        private Registry registry;
        private RegistrySimulationTarget target;

        private RuntimeContext(VirtualClock clock,
                               MemoryStorage storage,
                               Registry registry,
                               ScriptedFakeRcsAdapter fake,
                               RegistrySimulationTarget target) {
            this.clock = clock;
            this.storage = storage;
            this.registry = registry;
            this.fake = fake;
            this.target = target;
        }

        VirtualClock clock() {
            return clock;
        }

        MemoryStorage storage() {
            return storage;
        }

        ScriptedFakeRcsAdapter fake() {
            return fake;
        }

        Registry registry() {
            return registry;
        }

        RegistrySimulationTarget target() {
            return target;
        }

        void replaceTarget(Registry reloaded) {
            this.registry = reloaded;
            this.target = new RegistrySimulationTarget(reloaded);
        }
    }

    private record Execution(int pass, int fail, int skipped, RunFailure firstFailure) {
    }
}
