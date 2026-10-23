package lab;

public record WorkflowStep(String name, WorkflowStepState state) {

    public WorkflowStep withState(WorkflowStepState newState) {
        return new WorkflowStep(name, newState);
    }
}
