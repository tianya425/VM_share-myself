package lab;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流实例：固定 3 步（DISPATCH / EXECUTE / COMPLETE）。
 *
 * @param instanceId       实例编号
 * @param applicationId    关联申请编号
 * @param state            RUNNING / COMPLETED / CANCELLED / FAILED
 * @param currentStepIndex 当前步骤下标（0 起）
 * @param steps            三步状态（不可变列表）
 */
public record WorkflowInstance(
        String instanceId,
        String applicationId,
        WorkflowState state,
        int currentStepIndex,
        List<WorkflowStep> steps) {

    /** 返回仅改工作流状态的副本。 */
    public WorkflowInstance withState(WorkflowState newState) {
        return new WorkflowInstance(instanceId, applicationId, newState,
                currentStepIndex, steps);
    }

    /** 返回把指定下标步骤改成给定状态的副本（其余步骤不变）。 */
    public WorkflowInstance withStep(int index, WorkflowStepState stepState) {
        List<WorkflowStep> newSteps = new ArrayList<>(steps);
        newSteps.set(index, steps.get(index).withState(stepState));
        return new WorkflowInstance(instanceId, applicationId, state,
                currentStepIndex, List.copyOf(newSteps));
    }

    /** 返回仅改当前步骤下标的副本。 */
    public WorkflowInstance withCurrentStepIndex(int newIndex) {
        return new WorkflowInstance(instanceId, applicationId, state,
                newIndex, steps);
    }
}
