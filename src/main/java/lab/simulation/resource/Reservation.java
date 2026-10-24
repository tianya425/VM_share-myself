package lab.simulation.resource;

/**
 * 预约（Reservation）。
 *
 * <p>编号固定为 {@code "RSV-" + applicationId}（最长 36 字符，不截断、不散列、不随机）。
 * 队列排序键为 {@code offsetMillis → logicalSequence → applicationId}。
 */
public record Reservation(
        String reservationId,
        String applicationId,
        String destinationId,
        String servicePointId,
        String resourceId,
        ReservationState state,
        long offsetMillis,
        long logicalSequence) {

    public static String idFor(String applicationId) {
        return "RSV-" + applicationId;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public Reservation withState(ReservationState newState) {
        return new Reservation(reservationId, applicationId, destinationId, servicePointId,
                resourceId, newState, offsetMillis, logicalSequence);
    }
}
