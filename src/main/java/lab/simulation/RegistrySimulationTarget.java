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

    /** 对应 {@link Registry#registerPoint}。 */
    @Override
    public void pointAdd(String pointId, String name) {
        registry.registerPoint(pointId, name);
    }

    /** 对应 {@link Registry#registerApplication}。 */
    @Override
    public void applicationAdd(String applicationId, String fromPointId, String toPointId) {
        registry.registerApplication(applicationId, fromPointId, toPointId);
    }

    /** 对应 {@link Registry#dispatchApplication}。 */
    @Override
    public void applicationDispatch(String applicationId) {
        registry.dispatchApplication(applicationId);
    }

    /** 对应 {@link Registry#queryApplication}。 */
    @Override
    public void applicationQuery(String applicationId) {
        registry.queryApplication(applicationId);
    }

    /** 对应 {@link Registry#receiveCallback}。 */
    @Override
    public void applicationCallback(String applicationId, String eventType) {
        registry.receiveCallback(applicationId, eventType);
    }

    /** 对应 {@link Registry#processOutbox}。 */
    @Override
    public void outboxProcess() {
        registry.processOutbox();
    }

    /** 对应 {@link Registry#startWorkflow}。 */
    @Override
    public void workflowStart(String applicationId) {
        registry.startWorkflow(applicationId);
    }

    /** 对应 {@link Registry#advanceWorkflow}。 */
    @Override
    public void workflowAdvance(String applicationId) {
        registry.advanceWorkflow(applicationId);
    }

    // ---------- Part B / PR4：资源领域 ----------

    /** 对应 {@link Registry#addDestination}。 */
    @Override
    public void destinationAdd(String destinationId, String name) {
        registry.addDestination(destinationId, name);
    }

    /** 对应 {@link Registry#addServicePoint}（三字段合同，无 name）。 */
    @Override
    public void servicePointAdd(String servicePointId, String destinationId,
                                String pointId) {
        registry.addServicePoint(servicePointId, destinationId, pointId);
    }

    /** 对应 {@link Registry#addVirtualResource}。 */
    @Override
    public void virtualResourceAdd(String resourceId, String servicePointId) {
        registry.addVirtualResource(resourceId, servicePointId);
    }

    /** 对应 {@link Registry#setVirtualResourceAvailability}；availability 取值非法即抛错。 */
    @Override
    public void resourceSetAvailability(String resourceId, String availability) {
        registry.setVirtualResourceAvailability(resourceId,
                lab.simulation.resource.ResourceAvailability.valueOf(availability));
    }

    /** 对应 {@link Registry#requestReservation}，返回本次决策。 */
    @Override
    public ReservationDecision reservationRequest(String applicationId, String destinationId,
                                                  String servicePointId, String resourceId,
                                                  long requestedOffsetMillis, long requestedLogicalSequence) {
        return registry.requestReservation(applicationId, destinationId, servicePointId,
                resourceId, requestedOffsetMillis, requestedLogicalSequence);
    }

    /** 对应 {@link Registry#releaseReservation}。 */
    @Override
    public Reservation reservationRelease(String applicationId) {
        return registry.releaseReservation(applicationId);
    }

    /** 对应 {@link Registry#cancelReservation}。 */
    @Override
    public Reservation reservationCancel(String applicationId) {
        return registry.cancelReservation(applicationId);
    }

    /** 对应 {@link Registry#resourceWorldSnapshot}。 */
    @Override
    public ResourceWorldSnapshot resourceWorldSnapshot() {
        return registry.resourceWorldSnapshot();
    }
}
