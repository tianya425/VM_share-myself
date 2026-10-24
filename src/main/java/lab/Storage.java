package lab;

import java.util.List;

/**
 * 存储合同：整状态读写。
 *
 * <p>T2 起只保留两个抽象方法：
 * <ul>
 *   <li>{@link #loadState()} —— 返回一份深拷贝的已提交状态；</li>
 *   <li>{@link #commitState(RegistryState)} —— 原子提交整份状态；失败时已提交状态不变。</li>
 * </ul>
 *
 * <p>旧的按集合 {@code load*} / {@code save*} 方法保留为**兼容委托**，
 * 内部一律转成 {@code loadState} / {@code commitState}，不再有各自的写路径。
 * 新代码不应再依赖它们。
 */
public interface Storage {

    /**
     * 读取已提交状态（深拷贝）。
     */
    RegistryState loadState();

    /**
     * 提交整份状态。成功返回后 {@link #loadState()} 必须返回这份状态；
     * 抛异常时必须保持提交前的状态不变。
     */
    void commitState(RegistryState next);

    // ---------- 兼容委托：读 ----------

    default List<Point> loadPoints() {
        return loadState().points();
    }

    default List<TransferApplication> loadApplications() {
        return loadState().applications();
    }

    default List<OutboxEntry> loadOutbox() {
        return loadState().outbox();
    }

    default List<InboxEntry> loadInbox() {
        return loadState().inbox();
    }

    default List<WorkflowInstance> loadWorkflows() {
        return loadState().workflows();
    }

    // ---------- 兼容委托：写（只改一个集合，其余沿用已提交状态） ----------

    default void savePoints(List<Point> points) {
        RegistryState current = loadState();
        commitState(new RegistryState(points, current.applications(), current.outbox(),
                current.inbox(), current.workflows(), current.resourceWorld()));
    }

    default void saveApplications(List<TransferApplication> applications) {
        RegistryState current = loadState();
        commitState(new RegistryState(current.points(), applications, current.outbox(),
                current.inbox(), current.workflows(), current.resourceWorld()));
    }

    default void saveOutbox(List<OutboxEntry> outbox) {
        RegistryState current = loadState();
        commitState(new RegistryState(current.points(), current.applications(), outbox,
                current.inbox(), current.workflows(), current.resourceWorld()));
    }

    default void saveInbox(List<InboxEntry> inbox) {
        RegistryState current = loadState();
        commitState(new RegistryState(current.points(), current.applications(), current.outbox(),
                inbox, current.workflows(), current.resourceWorld()));
    }

    default void saveWorkflows(List<WorkflowInstance> workflows) {
        RegistryState current = loadState();
        commitState(new RegistryState(current.points(), current.applications(), current.outbox(),
                current.inbox(), workflows, current.resourceWorld()));
    }
}
