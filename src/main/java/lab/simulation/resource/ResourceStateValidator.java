package lab.simulation.resource;

import lab.Point;
import lab.RegistryException;
import lab.RegistryState;
import lab.TransferApplication;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源状态统一校验器（STU-SIM-02-R1 合同 §10）。
 *
 * <p>这是资源状态规则的**唯一权威实现**：三种 Storage 的 {@code loadState()} /
 * {@code commitState()}、Registry 构造与 restart 恢复都只调用这一处逻辑，
 * 不在每种 Storage 里复制规则。
 *
 * <p>固定 16 步校验顺序（§10.2）：
 * <ol>
 *   <li>RegistryState 与 ResourceWorldSnapshot 非 null；</li>
 *   <li>每个集合非 null，集合元素非 null；</li>
 *   <li>资源领域对象自身标量校验（复用 {@link ResourceValidation}）；</li>
 *   <li>Destination / ServicePoint / VirtualResource / Reservation 重复 ID；</li>
 *   <li>ServicePoint → Destination 引用存在；</li>
 *   <li>ServicePoint → Registry Point 引用存在；</li>
 *   <li>VirtualResource → ServicePoint 引用存在；</li>
 *   <li>Reservation → Application 引用存在；</li>
 *   <li>Reservation → Destination / ServicePoint / VirtualResource 引用存在；</li>
 *   <li>Reservation 目标链与实际对象一致；</li>
 *   <li>reservationId 公式正确（"RSV-" + applicationId）；</li>
 *   <li>同一 application 最多一个非终态 Reservation；</li>
 *   <li>同一 resource 最多一个 HELD Reservation；</li>
 *   <li>WAITING 队列无重复；</li>
 *   <li>WAITING 队列符合固定排序键（显式 Comparator）；</li>
 *   <li>快照不得含可直接证明的矛盾结构。</li>
 * </ol>
 *
 * <p>错误统一为 {@code RESOURCE_STATE_INVALID}，detail 固定形态
 * {@code rule=<规则ID>;entity=<对象ID>;field=<字段>;actual=<实际值>}；
 * 不输出无序 Map、对象内存地址或本机绝对路径。
 */
public final class ResourceStateValidator {

    /** 外层稳定错误码。 */
    public static final String ERROR_CODE = "RESOURCE_STATE_INVALID";

    /** WAITING 队列固定排序键：offset → logicalSequence → applicationId（逐字段显式）。 */
    private static final Comparator<Reservation> WAITING_ORDER =
            Comparator.comparingLong(Reservation::requestedOffsetMillis)
                    .thenComparingLong(Reservation::requestedLogicalSequence)
                    .thenComparing(Reservation::applicationId);

    private ResourceStateValidator() {
    }

    /**
     * 校验一份完整 Registry 状态；任一规则不满足即抛
     * {@link RegistryException}（{@code RESOURCE_STATE_INVALID}）。
     */
    public static void validate(RegistryState state) {
        // 1. 顶层对象非 null。
        if (state == null) {
            throw invalid("R01_TOP_LEVEL_NON_NULL", "RegistryState", "state", "null");
        }
        ResourceWorldSnapshot world = state.resourceWorld();
        if (world == null) {
            throw invalid("R01_TOP_LEVEL_NON_NULL", "RegistryState", "resourceWorld", "null");
        }

        // 2. 每个集合非 null，集合元素非 null。
        requireElements(state.points(), "RegistryState", "points");
        requireElements(state.applications(), "RegistryState", "applications");
        requireElements(state.outbox(), "RegistryState", "outbox");
        requireElements(state.inbox(), "RegistryState", "inbox");
        requireElements(state.workflows(), "RegistryState", "workflows");
        requireElements(world.destinations(), "ResourceWorldSnapshot", "destinations");
        requireElements(world.servicePoints(), "ResourceWorldSnapshot", "servicePoints");
        requireElements(world.resources(), "ResourceWorldSnapshot", "resources");
        requireElements(world.reservations(), "ResourceWorldSnapshot", "reservations");

        // 3. 资源领域对象自身标量校验（§8.1 ResourceValidation）。
        for (Destination destination : world.destinations()) {
            String entity = destination.destinationId();
            checkId(entity, "destinationId", destination.destinationId());
            checkName(entity, "name", destination.name());
        }
        for (ServicePoint servicePoint : world.servicePoints()) {
            String entity = servicePoint.servicePointId();
            checkId(entity, "servicePointId", servicePoint.servicePointId());
            checkId(entity, "destinationId", servicePoint.destinationId());
            checkId(entity, "pointId", servicePoint.pointId());
        }
        for (VirtualResource resource : world.resources()) {
            String entity = resource.resourceId();
            checkId(entity, "resourceId", resource.resourceId());
            checkId(entity, "servicePointId", resource.servicePointId());
            checkNonNull(entity, "availability", resource.availability());
        }
        for (Reservation reservation : world.reservations()) {
            String entity = reservation.reservationId();
            checkId(entity, "reservationId", reservation.reservationId());
            checkId(entity, "applicationId", reservation.applicationId());
            checkId(entity, "destinationId", reservation.destinationId());
            checkId(entity, "servicePointId", reservation.servicePointId());
            checkId(entity, "resourceId", reservation.resourceId());
            checkNonNull(entity, "state", reservation.state());
            checkNonNegative(entity, "requestedOffsetMillis", reservation.requestedOffsetMillis());
            checkPositive(entity, "requestedLogicalSequence", reservation.requestedLogicalSequence());
        }

        // 4. 四类资源对象分别查重复 ID，同时建立查询 Map。
        Map<String, Destination> destinationById = new HashMap<>();
        for (Destination destination : world.destinations()) {
            if (destinationById.put(destination.destinationId(), destination) != null) {
                throw invalid("R04_DUPLICATE_ID", destination.destinationId(),
                        "destinationId", destination.destinationId());
            }
        }
        Map<String, ServicePoint> servicePointById = new HashMap<>();
        for (ServicePoint servicePoint : world.servicePoints()) {
            if (servicePointById.put(servicePoint.servicePointId(), servicePoint) != null) {
                throw invalid("R04_DUPLICATE_ID", servicePoint.servicePointId(),
                        "servicePointId", servicePoint.servicePointId());
            }
        }
        Map<String, VirtualResource> resourceById = new HashMap<>();
        for (VirtualResource resource : world.resources()) {
            if (resourceById.put(resource.resourceId(), resource) != null) {
                throw invalid("R04_DUPLICATE_ID", resource.resourceId(),
                        "resourceId", resource.resourceId());
            }
        }
        Map<String, Reservation> reservationById = new HashMap<>();
        for (Reservation reservation : world.reservations()) {
            if (reservationById.put(reservation.reservationId(), reservation) != null) {
                throw invalid("R04_DUPLICATE_ID", reservation.reservationId(),
                        "reservationId", reservation.reservationId());
            }
        }

        Set<String> pointIds = new HashSet<>();
        for (Point point : state.points()) {
            pointIds.add(point.pointId());
        }
        Set<String> applicationIds = new HashSet<>();
        for (TransferApplication application : state.applications()) {
            applicationIds.add(application.applicationId());
        }

        // 5. ServicePoint → Destination。
        for (ServicePoint servicePoint : world.servicePoints()) {
            if (!destinationById.containsKey(servicePoint.destinationId())) {
                throw invalid("R05_SERVICE_POINT_DESTINATION_REF", servicePoint.servicePointId(),
                        "destinationId", servicePoint.destinationId());
            }
        }

        // 6. ServicePoint → Registry Point。
        for (ServicePoint servicePoint : world.servicePoints()) {
            if (!pointIds.contains(servicePoint.pointId())) {
                throw invalid("R06_SERVICE_POINT_REGISTRY_POINT_REF", servicePoint.servicePointId(),
                        "pointId", servicePoint.pointId());
            }
        }

        // 7. VirtualResource → ServicePoint。
        for (VirtualResource resource : world.resources()) {
            if (!servicePointById.containsKey(resource.servicePointId())) {
                throw invalid("R07_RESOURCE_SERVICE_POINT_REF", resource.resourceId(),
                        "servicePointId", resource.servicePointId());
            }
        }

        // 8. Reservation → Application。
        for (Reservation reservation : world.reservations()) {
            if (!applicationIds.contains(reservation.applicationId())) {
                throw invalid("R08_RESERVATION_APPLICATION_REF", reservation.reservationId(),
                        "applicationId", reservation.applicationId());
            }
        }

        // 9. Reservation → Destination / ServicePoint / VirtualResource。
        for (Reservation reservation : world.reservations()) {
            if (!destinationById.containsKey(reservation.destinationId())) {
                throw invalid("R09_RESERVATION_TARGET_REF", reservation.reservationId(),
                        "destinationId", reservation.destinationId());
            }
            if (!servicePointById.containsKey(reservation.servicePointId())) {
                throw invalid("R09_RESERVATION_TARGET_REF", reservation.reservationId(),
                        "servicePointId", reservation.servicePointId());
            }
            if (!resourceById.containsKey(reservation.resourceId())) {
                throw invalid("R09_RESERVATION_TARGET_REF", reservation.reservationId(),
                        "resourceId", reservation.resourceId());
            }
        }

        // 10. Reservation 目标链与实际对象一致。
        for (Reservation reservation : world.reservations()) {
            ServicePoint servicePoint = servicePointById.get(reservation.servicePointId());
            if (!servicePoint.destinationId().equals(reservation.destinationId())) {
                throw invalid("R10_RESERVATION_TARGET_CHAIN", reservation.reservationId(),
                        "destinationId", reservation.destinationId());
            }
            VirtualResource resource = resourceById.get(reservation.resourceId());
            if (!resource.servicePointId().equals(reservation.servicePointId())) {
                throw invalid("R10_RESERVATION_TARGET_CHAIN", reservation.reservationId(),
                        "servicePointId", reservation.servicePointId());
            }
        }

        // 11. reservationId 公式："RSV-" + applicationId。
        for (Reservation reservation : world.reservations()) {
            if (!Reservation.idFor(reservation.applicationId()).equals(reservation.reservationId())) {
                throw invalid("R11_RESERVATION_ID_FORMULA", reservation.reservationId(),
                        "reservationId", reservation.reservationId());
            }
        }

        // 12. 同一 application 最多一个非终态 Reservation。
        Map<String, Reservation> activeByApplication = new HashMap<>();
        for (Reservation reservation : world.reservations()) {
            if (reservation.state().isTerminal()) {
                continue;
            }
            Reservation existing = activeByApplication.putIfAbsent(
                    reservation.applicationId(), reservation);
            if (existing != null) {
                throw invalid("R12_APPLICATION_ACTIVE_RESERVATION",
                        reservation.applicationId(), "state",
                        existing.reservationId() + "," + reservation.reservationId());
            }
        }

        // 13. 同一 resource 最多一个 HELD Reservation。
        Map<String, Reservation> heldByResource = new HashMap<>();
        for (Reservation reservation : world.reservations()) {
            if (reservation.state() != ReservationState.HELD) {
                continue;
            }
            Reservation existing = heldByResource.putIfAbsent(reservation.resourceId(), reservation);
            if (existing != null) {
                throw invalid("R13_RESOURCE_HELD_RESERVATION", reservation.resourceId(),
                        "reservationId", existing.reservationId() + "," + reservation.reservationId());
            }
        }

        // 14. WAITING 队列无重复 applicationId。
        Map<String, List<Reservation>> waitingByResource = new HashMap<>();
        for (Reservation reservation : world.reservations()) {
            if (reservation.state() != ReservationState.WAITING) {
                continue;
            }
            waitingByResource.computeIfAbsent(reservation.resourceId(), key -> new ArrayList<>())
                    .add(reservation);
        }
        for (VirtualResource resource : world.resources()) {
            List<Reservation> waiting = waitingByResource.get(resource.resourceId());
            if (waiting == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Reservation reservation : waiting) {
                if (!seen.add(reservation.applicationId())) {
                    throw invalid("R14_WAITING_QUEUE_DUPLICATE", resource.resourceId(),
                            "applicationId", reservation.applicationId());
                }
            }
        }

        // 15. WAITING 队列固定排序键（逐字段显式 Comparator，不依赖 Map 遍历或插入顺序）。
        for (VirtualResource resource : world.resources()) {
            List<Reservation> waiting = waitingByResource.get(resource.resourceId());
            if (waiting == null || waiting.size() < 2) {
                continue;
            }
            for (int i = 1; i < waiting.size(); i++) {
                Reservation previous = waiting.get(i - 1);
                Reservation current = waiting.get(i);
                if (WAITING_ORDER.compare(previous, current) > 0) {
                    throw invalid("R15_WAITING_QUEUE_ORDER", resource.resourceId(), "queueOrder",
                            previous.reservationId() + ">" + current.reservationId());
                }
            }
        }

        // 16. 不得含可直接证明的矛盾结构：ENABLED 且无 HELD 的资源不应残留 WAITING
        //（这种队列头本应已被晋升；终态历史不在这里判断，留给运行时 Oracle）。
        for (VirtualResource resource : world.resources()) {
            if (resource.availability() != ResourceAvailability.ENABLED) {
                continue;
            }
            List<Reservation> waiting = waitingByResource.get(resource.resourceId());
            if (waiting == null || waiting.isEmpty()) {
                continue;
            }
            if (!heldByResource.containsKey(resource.resourceId())) {
                throw invalid("R16_STATE_CONTRADICTION", resource.resourceId(),
                        "reservation.state", "WAITING:" + waiting.get(0).applicationId());
            }
        }
    }

    private static void requireElements(List<?> values, String entity, String field) {
        if (values == null) {
            throw invalid("R02_COLLECTION_ELEMENTS_NON_NULL", entity, field, "null");
        }
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) == null) {
                throw invalid("R02_COLLECTION_ELEMENTS_NON_NULL", entity,
                        field + "[" + i + "]", "null");
            }
        }
    }

    private static void checkId(String entity, String field, String value) {
        try {
            ResourceValidation.requireId(field, value);
        } catch (IllegalArgumentException e) {
            throw invalid("R03_SCALAR", entity, field, value);
        }
    }

    private static void checkName(String entity, String field, String value) {
        try {
            ResourceValidation.requireName(field, value, 1, 40);
        } catch (IllegalArgumentException e) {
            throw invalid("R03_SCALAR", entity, field, value);
        }
    }

    private static void checkNonNull(String entity, String field, Object value) {
        try {
            ResourceValidation.requireNonNull(field, value);
        } catch (IllegalArgumentException e) {
            throw invalid("R03_SCALAR", entity, field, "null");
        }
    }

    private static void checkNonNegative(String entity, String field, long value) {
        try {
            ResourceValidation.requireNonNegative(field, value);
        } catch (IllegalArgumentException e) {
            throw invalid("R03_SCALAR", entity, field, value);
        }
    }

    private static void checkPositive(String entity, String field, long value) {
        try {
            ResourceValidation.requirePositive(field, value);
        } catch (IllegalArgumentException e) {
            throw invalid("R03_SCALAR", entity, field, value);
        }
    }

    private static RegistryException invalid(String rule, String entity, String field, Object actual) {
        return new RegistryException(ERROR_CODE,
                "rule=" + rule + ";entity=" + text(entity) + ";field=" + text(field)
                        + ";actual=" + text(actual));
    }

    private static String text(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? "empty" : text;
    }
}
