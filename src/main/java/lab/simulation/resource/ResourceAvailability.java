package lab.simulation.resource;

/**
 * 虚拟资源的可用性。
 *
 * <p>新资源默认 {@link #ENABLED}；{@link #DISABLED} 只阻止新的晋升，
 * 不驱逐已经 HELD 的持有者。
 */
public enum ResourceAvailability {
    ENABLED,
    DISABLED
}
