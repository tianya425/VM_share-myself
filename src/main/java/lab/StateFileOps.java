package lab;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 可注入的文件操作（STU-SIM-02-R1 合同 §11.4）。
 *
 * <p>生产构造器使用真实实现；测试构造器可以注入在 write 或 atomicMove 抛
 * {@link IOException} 的实现，用于验证"提交失败不发布候选状态"。
 *
 * <p>只暴露提交所需的两个动作，避免测试通过真实文件权限（chmod /
 * setPosixFilePermissions）制造故障。
 */
public interface StateFileOps {

    /** 把全部字节写入 staging，并在返回前 flush + fsync。 */
    void writeAndSync(Path staging, byte[] bytes) throws IOException;

    /** 单次原子切换 staging → target（ATOMIC_MOVE + REPLACE_EXISTING）。 */
    void atomicMove(Path staging, Path target) throws IOException;
}
