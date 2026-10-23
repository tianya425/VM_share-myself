package lab.simulation;

import lab.Registry;

/**
 * SimulationTarget 的 Registry 实现。
 *
 * <p>只调用 Registry 的公开方法，不访问私有 Map，
 * 不复制状态机，不覆盖业务规则。
 */
public class RegistrySimulationTarget implements SimulationTarget {

    private final Registry registry;

    /**
    * 用给定的 Registry 创建适配器。
    * 适配器只调用 Registry 的公开方法。
    */
    public RegistrySimulationTarget(Registry registry) {
        this.registry = registry;
    }

    @Override
    public void pointAdd(String pointId, String name) {
        registry.registerPoint(pointId, name);
    }

    @Override
    public void applicationAdd(String applicationId, String fromPointId, String toPointId) {
        registry.registerApplication(applicationId, fromPointId, toPointId);
    }

    @Override
    public void applicationDispatch(String applicationId) {
        registry.dispatchApplication(applicationId);
    }

    @Override
    public void applicationQuery(String applicationId) {
        registry.queryApplication(applicationId);
    }

    @Override
    public void applicationCallback(String applicationId, String eventType) {
        registry.receiveCallback(applicationId, eventType);
    }

    @Override
    public void outboxProcess() {
        registry.processOutbox();
    }

    @Override
    public void workflowStart(String applicationId) {
        registry.startWorkflow(applicationId);
    }

    @Override
    public void workflowAdvance(String applicationId) {
        registry.advanceWorkflow(applicationId);
    }
}
