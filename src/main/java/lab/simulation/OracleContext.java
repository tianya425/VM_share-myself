package lab.simulation;

import lab.OutboxEntry;
import lab.TransferApplication;

import java.util.List;

/**
 * 一次 Oracle 检查所需的全部事实快照。
 *
 * <p>只读；Oracle 不修改其中任何对象，也不引用 {@link SimulationRuntime}。
 *
 * <p>字段：
 * <ul>
 *   <li>applications          — 最终申请快照</li>
 *   <li>outbox                — 最终 Outbox 快照</li>
 *   <li>interactions          — 脚本化外部交互（已有语义，供 Evidence 与 EXPECT 使用）</li>
 *   <li>externalCallAttempts  — 全部外部调用尝试（含未脚本化）</li>
 *   <li>stateTransitions      — 事件级状态轨迹快照</li>
 *   <li>runFailures           — 运行期失败事实</li>
 *   <li>plannedCount/executedCount/skippedCount — 计划/已执行/跳过的事件数</li>
 *   <li>evidenceLevel         — 证据等级（必须精确等于 LOCAL_SIMULATION）</li>
 *   <li>executionPortType     — 外部执行端口实现类名</li>
 *   <li>realExternalWriteCount — 真实外部写入次数（必须为 0）</li>
 * </ul>
 */
public record OracleContext(
        List<TransferApplication> applications,
        List<OutboxEntry> outbox,
        List<ExternalInteraction> interactions,
        List<ExternalCallAttempt> externalCallAttempts,
        List<StateTransition> stateTransitions,
        List<RunFailure> runFailures,
        int plannedCount,
        int executedCount,
        int skippedCount,
        String evidenceLevel,
        String executionPortType,
        int realExternalWriteCount) {

    public OracleContext {
        applications = List.copyOf(applications);
        outbox = List.copyOf(outbox);
        interactions = List.copyOf(interactions);
        externalCallAttempts = List.copyOf(externalCallAttempts);
        stateTransitions = List.copyOf(stateTransitions);
        runFailures = List.copyOf(runFailures);
    }
}
