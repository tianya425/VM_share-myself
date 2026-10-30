package lab.simulation.resource;

/**
 * 虚拟资源（VirtualResource）。不可变值对象。
 *
 * <p>容量固定为 1：同一资源同一时刻最多一个 HELD 预约。
 * 新资源默认 {@link ResourceAvailability#ENABLED}（STU-SIM-02-R1 §8.4）。
 * {@code availability} 不得为 null，构造即失败。
 */
public record VirtualResource(
        String resourceId,
        String servicePointId,
        ResourceAvailability availability) {

    public VirtualResource {
        resourceId = ResourceValidation.requireId("resourceId", resourceId);
        servicePointId = ResourceValidation.requireId("servicePointId", servicePointId);
        availability = ResourceValidation.requireNonNull("availability", availability);
    }

    /** 新资源默认 {@link ResourceAvailability#ENABLED}。 */
    public VirtualResource(String resourceId, String servicePointId) {
        this(resourceId, servicePointId, ResourceAvailability.ENABLED);
    }

    /** 返回仅改可用性（ENABLED / DISABLED）的副本。 */
    public VirtualResource withAvailability(ResourceAvailability newAvailability) {
        return new VirtualResource(resourceId, servicePointId, newAvailability);
    }
}
