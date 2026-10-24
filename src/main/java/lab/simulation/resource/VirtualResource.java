package lab.simulation.resource;

/**
 * 虚拟资源（VirtualResource）。
 *
 * <p>容量固定为 1：同一资源同一时刻最多一个 HELD 预约。
 * 新资源默认 {@link ResourceAvailability#ENABLED}。
 */
public record VirtualResource(
        String resourceId,
        String servicePointId,
        ResourceAvailability availability) {

    public VirtualResource(String resourceId, String servicePointId) {
        this(resourceId, servicePointId, ResourceAvailability.ENABLED);
    }

    public VirtualResource withAvailability(ResourceAvailability newAvailability) {
        return new VirtualResource(resourceId, servicePointId, newAvailability);
    }
}
