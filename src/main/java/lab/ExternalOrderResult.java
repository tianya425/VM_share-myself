package lab;

/**
 * 外部执行端口一次调用的结果。
 *
 * <p>{@code outcome} 取 ACCEPTED / REJECTED / TIMEOUT / NOT_FOUND；
 * 只有 ACCEPTED（或超时后对账成功）带回外部订单号。
 */
public record ExternalOrderResult(Outcome outcome, String externalOrderId) {

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        TIMEOUT,
        NOT_FOUND
    }

    /** 接受，并带回外部订单号。 */
    public static ExternalOrderResult accepted(String externalOrderId) {
        return new ExternalOrderResult(Outcome.ACCEPTED, externalOrderId);
    }

    /** 明确拒绝（未创建外部订单）。 */
    public static ExternalOrderResult rejected() {
        return new ExternalOrderResult(Outcome.REJECTED, null);
    }

    /** 调用超时、结果未知，需要后续 query 对账。 */
    public static ExternalOrderResult timeout() {
        return new ExternalOrderResult(Outcome.TIMEOUT, null);
    }

    /** 对账时外部没有该订单。 */
    public static ExternalOrderResult notFound() {
        return new ExternalOrderResult(Outcome.NOT_FOUND, null);
    }
}
