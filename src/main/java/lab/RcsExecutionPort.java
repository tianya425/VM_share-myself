package lab;

public interface RcsExecutionPort {

    ExternalOrderResult createOrder(String applicationId,
                                    String fromPointId,
                                    String toPointId);

    ExternalOrderResult queryOrder(String applicationId);
}
