package lab.simulation.resource;

import lab.RegistryException;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源世界：不可变状态的纯领域模型。
 *
 * <p>每个写操作返回新的 {@link VirtualResourceWorld}，绝不修改当前实例；
 * 调用方（Registry）负责把新快照放进候选 RegistryState 并单次 commitState。
 *
 * <p>fail-closed 约定：加载快照时不做事先的交叉校验；任何操作一旦遇到悬空引用
 * （资源缺 ServicePoint、ServicePoint 缺 Destination 等），一律抛
 * {@code RESOURCE_TARGET_MISMATCH}，绝不放行。
 */
public final class VirtualResourceWorld {

    private final ResourceWorldSnapshot snapshot;

    public VirtualResourceWorld(ResourceWorldSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("ResourceWorldSnapshot 不能为 null");
        }
        this.snapshot = snapshot;
    }

    public static VirtualResourceWorld empty() {
        return new VirtualResourceWorld(ResourceWorldSnapshot.empty());
    }

    public ResourceWorldSnapshot snapshot() {
        return snapshot;
    }

    // ---------- Destination ----------

    /**
     * 新增 Destination：同 ID 同 name 幂等；同 ID 不同 name 冲突。
     */
    public VirtualResourceWorld addDestination(Destination destination) {
        Destination existing = findDestination(destination.destinationId());
        if (existing != null) {
            if (existing.equals(destination)) {
                return this;
            }
            throw new RegistryException("RESOURCE_DUPLICATE_CONFLICT", destination.destinationId());
        }
        List<Destination> next = new ArrayList<>(snapshot.destinations());
        next.add(destination);
        return new VirtualResourceWorld(new ResourceWorldSnapshot(
                next, snapshot.servicePoints(), snapshot.resources(), snapshot.reservations()));
    }

    // ---------- ServicePoint ----------

    /**
     * 新增 ServicePoint：Destination 与 Registry Point 必须已存在（后者由 Registry 校验）。
     */
    public VirtualResourceWorld addServicePoint(ServicePoint servicePoint) {
        if (findDestination(servicePoint.destinationId()) == null) {
            throw new RegistryException("RESOURCE_UNKNOWN_DESTINATION", servicePoint.destinationId());
        }
        ServicePoint existing = findServicePoint(servicePoint.servicePointId());
        if (existing != null) {
            if (existing.equals(servicePoint)) {
                return this;
            }
            throw new RegistryException("RESOURCE_DUPLICATE_CONFLICT", servicePoint.servicePointId());
        }
        List<ServicePoint> next = new ArrayList<>(snapshot.servicePoints());
        next.add(servicePoint);
        return new VirtualResourceWorld(new ResourceWorldSnapshot(
                snapshot.destinations(), next, snapshot.resources(), snapshot.reservations()));
    }

    // ---------- VirtualResource ----------

    /**
     * 新增 VirtualResource：ServicePoint 必须已存在；新资源默认 ENABLED。
     */
    public VirtualResourceWorld addVirtualResource(VirtualResource resource) {
        if (findServicePoint(resource.servicePointId()) == null) {
            throw new RegistryException("RESOURCE_UNKNOWN_SERVICE_POINT", resource.servicePointId());
        }
        VirtualResource existing = findResource(resource.resourceId());
        if (existing != null) {
            if (existing.servicePointId().equals(resource.servicePointId())) {
                return this;
            }
            throw new RegistryException("RESOURCE_DUPLICATE_CONFLICT", resource.resourceId());
        }
        List<VirtualResource> next = new ArrayList<>(snapshot.resources());
        next.add(resource);
        return new VirtualResourceWorld(new ResourceWorldSnapshot(
                snapshot.destinations(), snapshot.servicePoints(), next, snapshot.reservations()));
    }

    /**
     * 设置可用性：DISABLED 不驱逐已 HELD 的持有者；ENABLED 且无 holder 时晋升队首。
     */
    public VirtualResourceWorld setAvailability(String resourceId, ResourceAvailability availability) {
        VirtualResource existing = findResource(resourceId);
        if (existing == null) {
            throw new RegistryException("RESOURCE_UNKNOWN_RESOURCE", resourceId);
        }
        if (existing.availability() == availability) {
            return this;
        }
        List<VirtualResource> next = new ArrayList<>(snapshot.resources().size());
        for (VirtualResource resource : snapshot.resources()) {
            next.add(resource.resourceId().equals(resourceId)
                    ? resource.withAvailability(availability)
                    : resource);
        }
        VirtualResourceWorld updated = new VirtualResourceWorld(new ResourceWorldSnapshot(
                snapshot.destinations(), snapshot.servicePoints(), next, snapshot.reservations()));
        return availability == ResourceAvailability.ENABLED ? updated.promote(resourceId) : updated;
    }

    // ---------- Reservation ----------

    /**
     * 请求预约：引用链必须一致；终态不可重开；同申请不同目标冲突；同目标重复请求幂等。
     *
     * <p>返回新世界（幂等时返回 {@code this}）。
     */
    public VirtualResourceWorld request(String applicationId,
                                        String destinationId,
                                        String servicePointId,
                                        String resourceId,
                                        long offsetMillis,
                                        long logicalSequence) {
        VirtualResource resource = findResource(resourceId);
        if (resource == null) {
            throw new RegistryException("RESOURCE_UNKNOWN_RESOURCE", resourceId);
        }
        ServicePoint servicePoint = findServicePoint(servicePointId);
        if (servicePoint == null
                || !resource.servicePointId().equals(servicePointId)
                || !servicePoint.destinationId().equals(destinationId)
                || findDestination(destinationId) == null) {
            throw new RegistryException("RESOURCE_TARGET_MISMATCH", applicationId);
        }

        Reservation existing = findReservation(applicationId);
        if (existing != null) {
            if (existing.isTerminal()) {
                throw new RegistryException("RESERVATION_TERMINAL", applicationId);
            }
            boolean sameTarget = existing.destinationId().equals(destinationId)
                    && existing.servicePointId().equals(servicePointId)
                    && existing.resourceId().equals(resourceId);
            if (sameTarget) {
                return this;
            }
            throw new RegistryException("RESERVATION_CONFLICT", applicationId);
        }

        boolean canHold = resource.availability() == ResourceAvailability.ENABLED
                && !hasHeld(resourceId)
                && !hasWaiting(resourceId);
        ReservationState state = canHold ? ReservationState.HELD : ReservationState.WAITING;
        Reservation created = new Reservation(
                Reservation.idFor(applicationId),
                applicationId,
                destinationId,
                servicePointId,
                resourceId,
                state,
                offsetMillis,
                logicalSequence);
        List<Reservation> next = new ArrayList<>(snapshot.reservations());
        next.add(created);
        return new VirtualResourceWorld(new ResourceWorldSnapshot(
                snapshot.destinations(), snapshot.servicePoints(), snapshot.resources(), next));
    }

    /**
     * 释放 HELD 预约，并晋升该资源的队首。
     */
    public VirtualResourceWorld release(String applicationId) {
        Reservation existing = findReservation(applicationId);
        if (existing == null) {
            throw new RegistryException("RESERVATION_NOT_FOUND", applicationId);
        }
        if (existing.isTerminal()) {
            throw new RegistryException("RESERVATION_TERMINAL", applicationId);
        }
        if (existing.state() != ReservationState.HELD) {
            throw new RegistryException("RESERVATION_NOT_HELD", applicationId);
        }
        VirtualResourceWorld released = replaceReservation(existing.withState(ReservationState.RELEASED));
        return released.promote(existing.resourceId());
    }

    /**
     * 取消 HELD 或 WAITING 预约；取消 HELD 后晋升队首。
     */
    public VirtualResourceWorld cancel(String applicationId) {
        Reservation existing = findReservation(applicationId);
        if (existing == null) {
            throw new RegistryException("RESERVATION_NOT_FOUND", applicationId);
        }
        if (existing.isTerminal()) {
            throw new RegistryException("RESERVATION_TERMINAL", applicationId);
        }
        VirtualResourceWorld cancelled = replaceReservation(existing.withState(ReservationState.CANCELLED));
        if (existing.state() == ReservationState.HELD) {
            return cancelled.promote(existing.resourceId());
        }
        return cancelled;
    }

    // ---------- 查询 ----------

    public Destination findDestination(String destinationId) {
        for (Destination destination : snapshot.destinations()) {
            if (destination.destinationId().equals(destinationId)) {
                return destination;
            }
        }
        return null;
    }

    public ServicePoint findServicePoint(String servicePointId) {
        for (ServicePoint servicePoint : snapshot.servicePoints()) {
            if (servicePoint.servicePointId().equals(servicePointId)) {
                return servicePoint;
            }
        }
        return null;
    }

    public VirtualResource findResource(String resourceId) {
        for (VirtualResource resource : snapshot.resources()) {
            if (resource.resourceId().equals(resourceId)) {
                return resource;
            }
        }
        return null;
    }

    public Reservation findReservation(String applicationId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.applicationId().equals(applicationId)) {
                return reservation;
            }
        }
        return null;
    }

    // ---------- 内部 ----------

    private boolean hasHeld(String resourceId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.HELD) {
                return true;
            }
        }
        return false;
    }

    private boolean hasWaiting(String resourceId) {
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                return true;
            }
        }
        return false;
    }

    private VirtualResourceWorld replaceReservation(Reservation updated) {
        List<Reservation> next = new ArrayList<>(snapshot.reservations().size());
        for (Reservation reservation : snapshot.reservations()) {
            next.add(reservation.reservationId().equals(updated.reservationId()) ? updated : reservation);
        }
        return new VirtualResourceWorld(new ResourceWorldSnapshot(
                snapshot.destinations(), snapshot.servicePoints(), snapshot.resources(), next));
    }

    /**
     * 晋升队首：资源 ENABLED、无 HELD 时，把队列最小键的 WAITING 变 HELD。
     *
     * <p>快照内预约已按 offset → logicalSequence → applicationId 排好序，
     * 因此第一个 WAITING 就是队首。
     */
    private VirtualResourceWorld promote(String resourceId) {
        VirtualResource resource = findResource(resourceId);
        if (resource == null || resource.availability() != ResourceAvailability.ENABLED || hasHeld(resourceId)) {
            return this;
        }
        for (Reservation reservation : snapshot.reservations()) {
            if (reservation.resourceId().equals(resourceId)
                    && reservation.state() == ReservationState.WAITING) {
                return replaceReservation(reservation.withState(ReservationState.HELD));
            }
        }
        return this;
    }
}
