package lab.simulation.resource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 资源世界的规范快照。
 *
 * <p>深不可变：四个集合一律 {@link List#copyOf}，并按固定键排序（目的地/服务点/资源按编号，
 * 预约按 requestedOffsetMillis → requestedLogicalSequence → applicationId）。不存派生 Map，也不在加载时做交叉校验；
 * 引用完整性在 {@link VirtualResourceWorld} 的写入操作里 fail-closed。
 */
public record ResourceWorldSnapshot(
        List<Destination> destinations,
        List<ServicePoint> servicePoints,
        List<VirtualResource> resources,
        List<Reservation> reservations) {

    public ResourceWorldSnapshot {
        destinations = List.copyOf(sortedDestinations(destinations));
        servicePoints = List.copyOf(sortedServicePoints(servicePoints));
        resources = List.copyOf(sortedResources(resources));
        reservations = List.copyOf(sortedReservations(reservations));
    }

    /**
     * 空快照。
     */
    public static ResourceWorldSnapshot empty() {
        return new ResourceWorldSnapshot(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 规范文本：按固定字段顺序逐行输出，用于 WORLD_RESTART 前后逐字比对。
     */
    public String canonicalText() {
        StringBuilder builder = new StringBuilder();
        for (Destination destination : destinations) {
            builder.append("D|").append(destination.destinationId()).append('|')
                    .append(destination.name()).append('\n');
        }
        for (ServicePoint servicePoint : servicePoints) {
            builder.append("S|").append(servicePoint.servicePointId()).append('|')
                    .append(servicePoint.destinationId()).append('|')
                    .append(servicePoint.pointId()).append('\n');
        }
        for (VirtualResource resource : resources) {
            builder.append("R|").append(resource.resourceId()).append('|')
                    .append(resource.servicePointId()).append('|')
                    .append(resource.availability().name()).append('\n');
        }
        for (Reservation reservation : reservations) {
            builder.append("V|").append(reservation.reservationId()).append('|')
                    .append(reservation.applicationId()).append('|')
                    .append(reservation.destinationId()).append('|')
                    .append(reservation.servicePointId()).append('|')
                    .append(reservation.resourceId()).append('|')
                    .append(reservation.state().name()).append('|')
                    .append(reservation.requestedOffsetMillis()).append('|')
                    .append(reservation.requestedLogicalSequence()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 规范快照的 SHA-256（小写 hex，基于 UTF-8 的 {@link #canonicalText()}）。
     *
     * <p>WORLD_RESTART 用它记录重载前后的摘要，逐字比较即可判断重载是否一致。
     */
    public String canonicalDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonicalText().getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<Destination> sortedDestinations(List<Destination> values) {
        if (values == null) {
            throw new IllegalArgumentException("ResourceWorldSnapshot.destinations 不能为 null");
        }
        List<Destination> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(Destination::destinationId));
        return copy;
    }

    private static List<ServicePoint> sortedServicePoints(List<ServicePoint> values) {
        if (values == null) {
            throw new IllegalArgumentException("ResourceWorldSnapshot.servicePoints 不能为 null");
        }
        List<ServicePoint> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(ServicePoint::servicePointId));
        return copy;
    }

    private static List<VirtualResource> sortedResources(List<VirtualResource> values) {
        if (values == null) {
            throw new IllegalArgumentException("ResourceWorldSnapshot.resources 不能为 null");
        }
        List<VirtualResource> copy = new ArrayList<>(values);
        copy.sort(Comparator.comparing(VirtualResource::resourceId));
        return copy;
    }

    private static List<Reservation> sortedReservations(List<Reservation> values) {
        if (values == null) {
            throw new IllegalArgumentException("ResourceWorldSnapshot.reservations 不能为 null");
        }
        List<Reservation> copy = new ArrayList<>(values);
        copy.sort(Comparator
                .comparingLong(Reservation::requestedOffsetMillis)
                .thenComparingLong(Reservation::requestedLogicalSequence)
                .thenComparing(Reservation::applicationId));
        return copy;
    }
}
