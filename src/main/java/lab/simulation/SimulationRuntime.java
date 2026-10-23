package lab.simulation;

import lab.ApplicationState;
import lab.MemoryStorage;
import lab.OutboxEntry;
import lab.Registry;
import lab.RegistryException;
import lab.TransferApplication;

import java.util.ArrayList;
import java.util.List;

/**
 * 仿真运行时。
 *
 * <p>按顺序驱动场景：建立全新的 Storage / Registry / Clock / Fake，
 * 依次推进虚拟时间并执行事件，收集外部交互、Oracle 结果和运行结果。
 *
 * <p>运行状态：
 * <pre>
 *   PREPARED → RUNNING → COMPLETED
 *                      ↘ FAILED
 *                      ↘ INCONCLUSIVE
 * </pre>
 */
public class SimulationRuntime {

    private final ScenarioPackage scenario;
    private final List<EventRecord> timeline = new ArrayList<>();
    private String runStatus = "PREPARED";

    public SimulationRuntime(ScenarioPackage scenario) {
        this.scenario = scenario;
    }

    /**
     * 执行整个场景。
     */
    public SimulationRunResult run() {
        runStatus = "RUNNING";

        RuntimeContext ctx = setup();
        EventStats stats = executeEvents(ctx);
        List<OracleResult> oracleResults = checkOracle(ctx, stats);
        String finalStatus = decideStatus(ctx.fake(), stats, oracleResults);
        runStatus = finalStatus;

        return buildResult(ctx, stats, oracleResults, finalStatus);
    }

    // ---------- 1. 建立运行时上下文 ----------

    /**
     * 建立一次运行所需的全部对象：
     * VirtualClock、MemoryStorage、ScriptedFakeRcsAdapter、
     * Registry 和 RegistrySimulationTarget。
     *
     * <p>每次调用都创建全新实例，不共享静态状态。
     */
    private RuntimeContext setup() {
        VirtualClock clock = new VirtualClock(scenario.startAt());
        MemoryStorage storage = new MemoryStorage();
        ScriptedFakeRcsAdapter fake = new ScriptedFakeRcsAdapter(clock, scenario.externalScripts());
        Registry registry = new Registry(storage, fake, false);
        RegistrySimulationTarget target = new RegistrySimulationTarget(registry);
        return new RuntimeContext(clock, storage, registry, fake, target);
    }

    // ---------- 2. 执行事件 ----------

    /**
     * 按 scenario.events() 顺序遍历事件，推进虚拟时间，并记录时间线。
     *
     * <p>每个事件的 RegistryException 或 ScenarioValidationException
     * 会转成一条 EVENT_FAIL 记录，不中断后续事件。
     */
    private EventStats executeEvents(RuntimeContext ctx) {
        int eventPass = 0;
        int eventFail = 0;

        for (ScenarioEvent event : scenario.events()) {
            ctx.clock().advanceTo(event.offsetMillis());
            try {
                dispatch(ctx.target(), event);
                timeline.add(new EventRecord(event, "EVENT_PASS", ""));
                eventPass++;
            } catch (RegistryException e) {
                timeline.add(new EventRecord(event, "EVENT_FAIL", describe(e)));
                eventFail++;
            } catch (ScenarioValidationException e) {
                timeline.add(new EventRecord(event, "EVENT_FAIL", describe(e)));
                eventFail++;
            }
        }

        return new EventStats(eventPass, eventFail);
    }

    /**
     * 把动作分派到 target 的对应方法。
     */
    private void dispatch(SimulationTarget target, ScenarioEvent event) {
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
        }
    }

    private String describe(RegistryException e) {
        return e.code() + (e.detail().isEmpty() ? "" : " " + e.detail());
    }

    private String describe(ScenarioValidationException e) {
        return e.code() + (e.detail().isEmpty() ? "" : " " + e.detail());
    }

    // ---------- 3. 执行 Oracle ----------

    /**
     * 收集业务对象快照，并调用 InvariantOracle 检查。
     */
    private List<OracleResult> checkOracle(RuntimeContext ctx, EventStats stats) {
        List<TransferApplication> apps = ctx.registry().listApplications();
        List<OutboxEntry> outbox = ctx.registry().listOutbox();
        List<ExternalInteraction> interactions = ctx.fake().interactions();

        OracleContext oracleCtx = new OracleContext(apps, outbox, interactions);
        InvariantOracle oracle = new InvariantOracle(scenario, this);
        return oracle.check(oracleCtx);
    }

    // ---------- 4. 决定最终状态 ----------

    /**
     * 综合外部脚本消费情况、事件失败数、Oracle 结果决定最终状态。
     *
     * <p>优先级：
     * <ol>
     *   <li>有脚本未消费 → FAILED</li>
     *   <li>有事件失败 → FAILED</li>
     *   <li>有 Oracle FAIL → FAILED</li>
     *   <li>否则 → COMPLETED</li>
     * </ol>
     */
    private String decideStatus(ScriptedFakeRcsAdapter fake, EventStats stats,
                                List<OracleResult> oracleResults) {
        if (!allScriptsConsumed(fake)) {
            return SimulationRunResult.FAILED;
        }
        if (stats.eventFail() > 0) {
            return SimulationRunResult.FAILED;
        }
        for (OracleResult r : oracleResults) {
            if (r.status() == OracleResult.Status.FAIL) {
                return SimulationRunResult.FAILED;
            }
        }
        return SimulationRunResult.COMPLETED;
    }

    private boolean allScriptsConsumed(ScriptedFakeRcsAdapter fake) {
        for (ExternalScript s : scenario.externalScripts()) {
            if (s.operation() == ExternalScript.Operation.CREATE
                    && fake.callCount(ExternalScript.Operation.CREATE) < s.ordinal()) {
                return false;
            }
            if (s.operation() == ExternalScript.Operation.QUERY
                    && fake.callCount(ExternalScript.Operation.QUERY) < s.ordinal()) {
                return false;
            }
        }
        return true;
    }

    // ---------- 5. 构造结果 ----------

    private SimulationRunResult buildResult(RuntimeContext ctx, EventStats stats,
                                            List<OracleResult> oracleResults,
                                            String status) {
        return new SimulationRunResult(
                status,
                scenario.scenarioId(),
                scenario.version(),
                scenario.events().size(),
                stats.eventPass(),
                stats.eventFail(),
                ctx.fake().callCount(ExternalScript.Operation.CREATE),
                ctx.fake().callCount(ExternalScript.Operation.QUERY),
                ctx.fake().validExternalOrderCount(),
                ctx.fake().interactions(),
                oracleResults);
    }

    // ---------- 时间线 ----------

    /**
     * 事件时间线快照。
     */
    public List<EventRecord> timeline() {
        return List.copyOf(timeline);
    }

    /**
     * 一条事件执行记录。
     */
    public record EventRecord(ScenarioEvent event, String status, String detail) {
    }

    // ---------- 辅助类型 ----------

    /**
     * 一次运行所需的全部对象引用。
     */
    private record RuntimeContext(
            VirtualClock clock,
            MemoryStorage storage,
            Registry registry,
            ScriptedFakeRcsAdapter fake,
            RegistrySimulationTarget target) {
    }

    /**
     * 事件执行统计。
     */
    private record EventStats(int eventPass, int eventFail) {
    }
}
