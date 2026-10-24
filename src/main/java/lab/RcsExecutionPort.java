package lab;

/**
 * 外部执行系统端口（防腐层）。
 *
 * <p>生产实现应为真实 RCS / M4 适配器；练习里只有 {@link FakeRcsAdapter}，
 * 仿真里只有脚本化的 Fake，二者都不产生真实外部写。
 */
public interface RcsExecutionPort {

    ExternalOrderResult createOrder(String applicationId,
                                    String fromPointId,
                                    String toPointId);

    ExternalOrderResult queryOrder(String applicationId);
}
