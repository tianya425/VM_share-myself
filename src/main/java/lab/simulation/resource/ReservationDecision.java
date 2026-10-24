package lab.simulation.resource;

/**
 * 一次 request 的决策结果。
 *
 * <p>{@link Outcome#HELD} / {@link Outcome#WAITING} 表示新建了预约；
 * {@link Outcome#IDEMPOTENT} 表示同一申请对同一目标的重复请求，原预约与排序键都不变。
 */
public record ReservationDecision(Outcome outcome, Reservation reservation) {

    public enum Outcome {
        HELD,
        WAITING,
        IDEMPOTENT
    }
}
