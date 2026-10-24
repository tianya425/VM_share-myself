package lab.simulation;

import lab.Registry;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationDecision;
import lab.simulation.resource.ResourceWorldSnapshot;

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

    // ---------- Part B / PR4：资源领域 ----------

    @Override
    public void destinationAdd(String destinationId, String name) {
        registry.addDestination(destinationId, name);
    }

    @Override
    public void servicePointAdd(String servicePointId, String destinationId,
                                String registryPointId, String name) {
        registry.addServicePoint(servicePointId, destinationId, registryPointId, name);
    }

    @Override
    public void virtualResourceAdd(String resourceId, String servicePointId) {
        registry.addVirtualResource(resourceId, servicePointId);
    }

    @Override
    public void resourceSetAvailability(String resourceId, String availability) {
        registry.setVirtualResourceAvailability(resourceId,
                lab.simulation.resource.ResourceAvailability.valueOf(availability));
    }

    @Override
    public ReservationDecision reservationRequest(String applicationId, String destinationId,
                                                  String servicePointId, String resourceId,
                                                  long offsetMillis, long logicalSequence) {
        return registry.requestReservation(applicationId, destinationId, servicePointId,
                resourceId, offsetMillis, logicalSequence);
    }

    @Override
    public Reservation reservationRelease(String applicationId) {
        return registry.releaseReservation(applicationId);
    }

    @Override
    public Reservation reservationCancel(String applicationId) {
        return registry.cancelReservation(applicationId);
    }

    @Override
    public ResourceWorldSnapshot resourceWorldSnapshot() {
        return registry.resourceWorldSnapshot();
    }
}
