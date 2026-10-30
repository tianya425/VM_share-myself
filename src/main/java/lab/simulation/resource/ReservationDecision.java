package lab.simulation.resource;

/**
 * 一次 request 的决策结果（STU-SIM-02-R1 §8.6）。
 *
 * <p>{@link DecisionType#HELD} / {@link DecisionType#WAITING} 表示新建了预约；
 * {@link DecisionType#IDEMPOTENT} 表示同一申请对同一目标的重复请求，原预约与排序键都不变；
 * {@link DecisionType#CONFLICT} 表示同一申请存在活跃预约但目标不同，原预约不变。
 *
 * <p>{@code type} / {@code reservation} / {@code detail} 均非 null；{@code detail}
 * 使用稳定 {@code key=value} 文本（HELD→{@code reason=RESOURCE_AVAILABLE}、
 * WAITING→{@code reason=RESOURCE_BUSY} 或 {@code reason=RESOURCE_DISABLED}、
 * IDEMPOTENT→{@code reason=SAME_ACTIVE_RESERVATION}、
 * CONFLICT→{@code reason=DIFFERENT_ACTIVE_TARGET}）。
 * CONFLICT / IDEMPOTENT 返回原有 reservation 且不提交新状态。
 */
public record ReservationDecision(DecisionType type, Reservation reservation, String detail) {

    public ReservationDecision {
        type = ResourceValidation.requireNonNull("type", type);
        reservation = ResourceValidation.requireNonNull("reservation", reservation);
        detail = ResourceValidation.requireNonNull("detail", detail);
    }

    /** 决策类型（原 Outcome 枚举更名并新增 CONFLICT）。 */
    public enum DecisionType {
        HELD,
        WAITING,
        IDEMPOTENT,
        CONFLICT
    }
}
