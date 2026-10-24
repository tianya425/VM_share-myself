package lab;

import java.util.List;

/**
 * Registry 的完整状态快照。
 *
 * <p>Storage 合同从"按集合读写"改为"整状态读写"后，所有存储实现与 Registry
 * 之间只交换这一个不可变对象。
 *
 * <p>构造时做深拷贝：五个集合一律 {@link List#copyOf}，WorkflowInstance 内部的
 * steps 列表也复制一份，因此持有者修改外部列表不会影响已构造的快照。
 *
 * <p>空状态用 {@link #empty()} 表示（五个集合都是空列表）。
 */
public record RegistryState(
        List<Point> points,
        List<TransferApplication> applications,
        List<OutboxEntry> outbox,
        List<InboxEntry> inbox,
        List<WorkflowInstance> workflows) {

    public RegistryState {
        points = copyOf(points, "points");
        applications = copyOf(applications, "applications");
        outbox = copyOf(outbox, "outbox");
        inbox = copyOf(inbox, "inbox");
        workflows = copyWorkflows(workflows);
    }

    /**
     * 空状态。
     */
    public static RegistryState empty() {
        return new RegistryState(List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static <T> List<T> copyOf(List<T> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException("RegistryState." + name + " 不能为 null");
        }
        return List.copyOf(values);
    }

    private static List<WorkflowInstance> copyWorkflows(List<WorkflowInstance> values) {
        if (values == null) {
            throw new IllegalArgumentException("RegistryState.workflows 不能为 null");
        }
        return values.stream()
                .map(workflow -> new WorkflowInstance(
                        workflow.instanceId(),
                        workflow.applicationId(),
                        workflow.state(),
                        workflow.currentStepIndex(),
                        List.copyOf(workflow.steps())))
                .toList();
    }
}
