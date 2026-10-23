package lab.simulation;

import java.time.Duration;
import java.time.Instant;

/**
 * 虚拟时钟。
 *
 * <p>从 startAt 开始，按事件推进逻辑时间。
 * 不使用系统当前时间，不调用 sleep。
 *
 * <p>规则：
 * <ul>
 *   <li>初始时间 = startAt</li>
 *   <li>advanceTo 目标小于当前偏移 → 抛 TIME_TRAVEL_BACKWARD</li>
 *   <li>目标相同时不改变时间</li>
 * </ul>
 */
public class VirtualClock {

    private final Instant startAt;
    private long currentOffsetMillis;

    public VirtualClock(Instant startAt) {
        this.startAt = startAt;
        this.currentOffsetMillis = 0L;
    }

    /**
     * 当前虚拟时间。
     * 初始值为 startAt，随 advanceTo 推进。
     */
    public Instant now() {
        return startAt.plus(Duration.ofMillis(currentOffsetMillis));
    }

    /**
     * 相对 startAt 的当前偏移（毫秒）。
     * 初始为 0。
     */
    public long offsetMillis() {
        return currentOffsetMillis;
    }

    /**
     * 推进虚拟时间到目标偏移。
     *
     * <p>目标小于当前偏移时抛 ScenarioValidationException，
     * code = TIME_TRAVEL_BACKWARD。
     * 目标相同时不改变时间。
     *
     * @param targetOffsetMillis 目标偏移（毫秒）
     * @throws ScenarioValidationException 时间倒退时
     */
    public void advanceTo(long targetOffsetMillis) {
        if (targetOffsetMillis < currentOffsetMillis) {
            throw new ScenarioValidationException(
                    "TIME_TRAVEL_BACKWARD",
                    currentOffsetMillis + " -> " + targetOffsetMillis);
        }
        this.currentOffsetMillis = targetOffsetMillis;
    }

    /**
     * 返回场景的起始时间。
     * 该值由 ScenarioPackage.startAt 提供，运行中不变。
     */
    public Instant startAt() {
        return startAt;
    }
}
