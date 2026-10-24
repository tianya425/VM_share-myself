package lab.simulation.resource;

/**
 * 目的地（Destination）。不可变值对象，引用完整性由 {@link VirtualResourceWorld} 在写入时校验。
 */
public record Destination(String destinationId, String name) {
}
