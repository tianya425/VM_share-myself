package lab;

import java.util.HashMap;
import java.util.Map;

public class FakeRcsAdapter implements RcsExecutionPort {

    public enum Mode {
        ACCEPT,
        REJECT,
        TIMEOUT_BUT_CREATED,
        TIMEOUT_NOT_CREATED
    }

    private final Mode mode;
    private final Map<String, String> createdOrders = new HashMap<>();

    public FakeRcsAdapter(Mode mode) {
        this.mode = mode;
    }

    @Override
    public synchronized ExternalOrderResult createOrder(String applicationId,
                                                        String fromPointId,
                                                        String toPointId) {
        switch (mode) {
            case ACCEPT -> {
                String externalOrderId = "EXT-" + applicationId;
                createdOrders.put(applicationId, externalOrderId);
                return ExternalOrderResult.accepted(externalOrderId);
            }
            case REJECT -> {
                return ExternalOrderResult.rejected();
            }
            case TIMEOUT_BUT_CREATED -> {
                String externalOrderId = "EXT-" + applicationId;
                createdOrders.put(applicationId, externalOrderId);
                return ExternalOrderResult.timeout();
            }
            case TIMEOUT_NOT_CREATED -> {
                return ExternalOrderResult.timeout();
            }
            default -> {
                throw new IllegalStateException("unknown mode: " + mode);
            }
        }
    }

    @Override
    public synchronized ExternalOrderResult queryOrder(String applicationId) {
        String externalOrderId = createdOrders.get(applicationId);
        if (externalOrderId == null) {
            return ExternalOrderResult.notFound();
        }
        return ExternalOrderResult.accepted(externalOrderId);
    }
}
