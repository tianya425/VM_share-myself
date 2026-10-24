package lab.simulation.resource;

/**
 * 预约状态。
 *
 * <p>生命周期：请求时为 {@link #HELD}（资源空闲且 ENABLED）或 {@link #WAITING}（占用/禁用）；
 * {:@link #HELD} 可 release → {@link #RELEASED}，HELD/WAITING 可 cancel → {@link #CANCELLED}。
 * 终态不可重开。
 */
public enum ReservationState {
    HELD,
    WAITING,
    RELEASED,
    CANCELLED;

    /** 是否为终态（RELEASED / CANCELLED）。 */
    public boolean isTerminal() {
        return this == RELEASED || this == CANCELLED;
    }
}
