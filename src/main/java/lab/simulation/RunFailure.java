package lab.simulation;

/**
 * 一条运行期失败事实。
 *
 * <p>eventId 为触发失败的事件编号；非事件型失败（未消费脚本、Oracle 不满足）为 null。
 * code 为稳定错误码。
 */
public record RunFailure(String eventId, String code) {
}
