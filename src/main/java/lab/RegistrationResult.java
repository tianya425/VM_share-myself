package lab;

/**
 * 登记结果：新记录，或命中幂等重放。
 *
 * @param application 登记后的申请
 * @param replay      true 表示同编号同起终点已存在、本次为幂等重放
 */
public record RegistrationResult(TransferApplication application, boolean replay) {
}
