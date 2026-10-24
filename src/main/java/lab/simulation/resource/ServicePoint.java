package lab.simulation.resource;

/**
 * 服务点（ServicePoint）。
 *
 * <p>同时引用一个资源领域 Destination 与一个既有 Registry Point（点位）。
 * 两个引用都必须先存在，否则写入被拒绝。
 */
public record ServicePoint(
        String servicePointId,
        String destinationId,
        String registryPointId,
        String name) {
}
