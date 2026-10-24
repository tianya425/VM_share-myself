package lab;

import java.util.HashMap;
import java.util.Map;

/**
 * 本地 Fake 外部执行系统：按构造时的 {@link Mode} 返回固定结果，不连接任何真实系统。
 *
 * <p>四种模式对应命令行的 fake-accept / fake-reject / fake-timeout-created /
 * fake-timeout-not-created；TIMEOUT_BUT_CREATED 会记住已创建的外部订单，供 query 对账。
 */
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

    /** 按模式返回创建结果；TIMEOUT_BUT_CREATED 记下外部订单以便对账。 */
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

    /** 按本地记录返回查询结果；没有记录即 NOT_FOUND。 */
    @Override
    public synchronized ExternalOrderResult queryOrder(String applicationId) {
        String externalOrderId = createdOrders.get(applicationId);
        if (externalOrderId == null) {
            return ExternalOrderResult.notFound();
        }
        return ExternalOrderResult.accepted(externalOrderId);
    }
}
