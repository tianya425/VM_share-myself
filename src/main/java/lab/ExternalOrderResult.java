package lab;

public record ExternalOrderResult(Outcome outcome, String externalOrderId) {

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        TIMEOUT,
        NOT_FOUND
    }

    public static ExternalOrderResult accepted(String externalOrderId) {
        return new ExternalOrderResult(Outcome.ACCEPTED, externalOrderId);
    }

    public static ExternalOrderResult rejected() {
        return new ExternalOrderResult(Outcome.REJECTED, null);
    }

    public static ExternalOrderResult timeout() {
        return new ExternalOrderResult(Outcome.TIMEOUT, null);
    }

    public static ExternalOrderResult notFound() {
        return new ExternalOrderResult(Outcome.NOT_FOUND, null);
    }
}
