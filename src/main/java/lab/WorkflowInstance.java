package lab;

import java.util.ArrayList;
import java.util.List;

public record WorkflowInstance(
        String instanceId,
        String applicationId,
        WorkflowState state,
        int currentStepIndex,
        List<WorkflowStep> steps) {

    public WorkflowInstance withState(WorkflowState newState) {
        return new WorkflowInstance(instanceId, applicationId, newState,
                currentStepIndex, steps);
    }

    public WorkflowInstance withStep(int index, WorkflowStepState stepState) {
        List<WorkflowStep> newSteps = new ArrayList<>(steps);
        newSteps.set(index, steps.get(index).withState(stepState));
        return new WorkflowInstance(instanceId, applicationId, state,
                currentStepIndex, List.copyOf(newSteps));
    }

    public WorkflowInstance withCurrentStepIndex(int newIndex) {
        return new WorkflowInstance(instanceId, applicationId, state,
                newIndex, steps);
    }
}
