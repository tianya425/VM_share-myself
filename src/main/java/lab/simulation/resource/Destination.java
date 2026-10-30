package lab.simulation.resource;

/**
 * 目的地（Destination）。不可变值对象，引用完整性由 {@link VirtualResourceWorld} 在写入时校验。
 *
 * <p>构造即校验（STU-SIM-02-R1 §8.2）：{@code destinationId} 必须符合既有 ID 规则；
 * {@code name} trim 后长度 1—40，并保存规范化（trim）后的值。
 */
public record Destination(String destinationId, String name) {

    public Destination {
        destinationId = ResourceValidation.requireId("destinationId", destinationId);
        name = ResourceValidation.requireName("name", name, 1, 40);
    }
}
