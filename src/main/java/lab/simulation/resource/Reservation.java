package lab.simulation.resource;

/**
 * 预约（Reservation）。不可变值对象（STU-SIM-02-R1 §8.5）。
 *
 * <p>编号固定为 {@code "RSV-" + applicationId}（不截断、不散列、不随机）；
 * 队列排序键字段名统一为 {@code requestedOffsetMillis} 与
 * {@code requestedLogicalSequence}（offsetMillis / logicalSequence 不再作为规范字段名）。
 *
 * <p>构造即校验：{@code requestedOffsetMillis} 必须大于等于 0；
 * {@code requestedLogicalSequence} 必须大于等于 1；{@code state} 非 null；
 * {@code reservationId} 必须等于 {@code "RSV-" + applicationId}。
 */
public record Reservation(
        String reservationId,
        String applicationId,
        String destinationId,
        String servicePointId,
        String resourceId,
        ReservationState state,
        long requestedOffsetMillis,
        long requestedLogicalSequence) {

    public Reservation {
        reservationId = ResourceValidation.requireId("reservationId", reservationId);
        applicationId = ResourceValidation.requireId("applicationId", applicationId);
        destinationId = ResourceValidation.requireId("destinationId", destinationId);
        servicePointId = ResourceValidation.requireId("servicePointId", servicePointId);
        resourceId = ResourceValidation.requireId("resourceId", resourceId);
        state = ResourceValidation.requireNonNull("state", state);
        requestedOffsetMillis = ResourceValidation.requireNonNegative(
                "requestedOffsetMillis", requestedOffsetMillis);
        requestedLogicalSequence = ResourceValidation.requirePositive(
                "requestedLogicalSequence", requestedLogicalSequence);
        if (!reservationId.equals("RSV-" + applicationId)) {
            throw new IllegalArgumentException(
                    "type=RESERVATION_ID field=reservationId reason=formulaMismatch "
                            + "reservationId=" + reservationId + " applicationId=" + applicationId);
        }
    }

    /** 预约编号规则：{@code "RSV-" + applicationId}。 */
    public static String idFor(String applicationId) {
        return "RSV-" + applicationId;
    }

    /** 是否为终态（RELEASED / CANCELLED）。 */
    public boolean isTerminal() {
        return state.isTerminal();
    }

    /** 返回仅改状态的副本。 */
    public Reservation withState(ReservationState newState) {
        return new Reservation(reservationId, applicationId, destinationId, servicePointId,
                resourceId, newState, requestedOffsetMillis, requestedLogicalSequence);
    }
}
