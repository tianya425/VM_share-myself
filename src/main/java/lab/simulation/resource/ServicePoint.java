package lab.simulation.resource;

/**
 * 服务点（ServicePoint）。不可变值对象。
 *
 * <p>同时引用一个资源领域 Destination 与一个既有 Registry Point（点位）。
 * 两个引用都必须先存在，否则写入被拒绝（STU-SIM-02-R1 §8.3：三字段，
 * 无 name；Registry Point 引用字段名统一为 {@code pointId}）。
 */
public record ServicePoint(
        String servicePointId,
        String destinationId,
        String pointId) {

    public ServicePoint {
        servicePointId = ResourceValidation.requireId("servicePointId", servicePointId);
        destinationId = ResourceValidation.requireId("destinationId", destinationId);
        pointId = ResourceValidation.requireId("pointId", pointId);
    }
}
