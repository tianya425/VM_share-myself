package lab;

/**
 * 内存存储：copy-on-write。
 *
 * <p>已提交状态是一个不可变引用。{@link #commitState(RegistryState)} 先把候选状态
 * 深拷贝到局部变量，拷贝成功后才替换已提交引用；任何失败都不会让对外可见的状态
 * 变成"改了一半"。
 *
 * <p>{@link #loadState()} 返回深拷贝，调用方拿到的列表与内部状态互不影响。
 */
public class MemoryStorage implements Storage {

    private RegistryState committed = RegistryState.empty();

    /** 返回深拷贝，调用方修改不影响内部状态。 */
    @Override
    public synchronized RegistryState loadState() {
        return deepCopy(committed);
    }

    /** copy-on-write：先深拷贝候选，成功后才替换已提交引用。 */
    @Override
    public synchronized void commitState(RegistryState next) {
        // 先深拷贝（RegistryState 构造器会复制五个集合与 steps），成功后才发布
        RegistryState candidate = deepCopy(next);
        this.committed = candidate;
    }

    private static RegistryState deepCopy(RegistryState state) {
        return new RegistryState(state.points(), state.applications(), state.outbox(),
                state.inbox(), state.workflows(), state.resourceWorld());
    }
}
