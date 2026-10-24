package lab;

/**
 * 工作流的一步：名称与状态。
 *
 * @param name  步骤名（DISPATCH / EXECUTE / COMPLETE）
 * @param state 步骤状态
 */
public record WorkflowStep(String name, WorkflowStepState state) {

    /** 返回仅改步骤状态的副本。 */
    public WorkflowStep withState(WorkflowStepState newState) {
        return new WorkflowStep(name, newState);
    }
}
