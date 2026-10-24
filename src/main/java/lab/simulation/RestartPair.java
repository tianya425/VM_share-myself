package lab.simulation;

/**
 * 一次 {@code WORLD_RESTART} 的重载前后摘要对。
 *
 * <p>运行时保留同一 Storage / Clock / Fake，丢弃 Registry 与 Target，
 * 用 {@code loadState()} 重建后分别取规范快照的 SHA-256。
 * {@code beforeDigest.equals(afterDigest)} 才表示重载无损。
 *
 * @param eventId      触发重载的事件编号
 * @param beforeDigest 丢弃旧 Registry 前的规范快照摘要
 * @param afterDigest  重载并重建派生结构后的规范快照摘要
 */
public record RestartPair(String eventId, String beforeDigest, String afterDigest) {

    /** 重载前后摘要是否一致（重载无损）。 */
    public boolean consistent() {
        return beforeDigest != null && beforeDigest.equals(afterDigest);
    }
}
