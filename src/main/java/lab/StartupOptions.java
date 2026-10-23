package lab;

import java.nio.file.Path;

/**
 * 从命令行参数解析出的启动选项。
 *
 * <p>解析规则 fail closed：
 * <ul>
 *   <li>未知参数 → 抛 StartupException，code=STARTUP_ARGUMENT，detail=原参数</li>
 *   <li>两种 storageMode 同时出现 → STARTUP_CONFLICT storage</li>
 *   <li>两种 fakeMode 同时出现 → STARTUP_CONFLICT fake</li>
 *   <li>重复参数 → STARTUP_ARGUMENT &lt;arg&gt;</li>
 * </ul>
 */
public record StartupOptions(
        StorageMode storageMode,
        boolean readOnly,
        FakeRcsAdapter.Mode fakeMode) {

    public enum StorageMode {
        MEMORY, FILE, SQLITE
    }

    /**
     * 解析命令行参数。任一步失败时抛 StartupException。
     */
    public static StartupOptions parse(String[] args) {
        StorageMode storage = StorageMode.MEMORY;
        boolean storageSeen = false;
        boolean readOnly = false;
        FakeRcsAdapter.Mode fakeMode = FakeRcsAdapter.Mode.ACCEPT;
        boolean fakeSeen = false;

        for (String arg : args) {
            switch (arg) {
                case "file" -> {
                    if (storageSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "storage");
                    }
                    storage = StorageMode.FILE;
                    storageSeen = true;
                }
                case "sqlite" -> {
                    if (storageSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "storage");
                    }
                    storage = StorageMode.SQLITE;
                    storageSeen = true;
                }
                case "readonly" -> {
                    if (readOnly) {
                        throw new StartupException("STARTUP_ARGUMENT", arg);
                    }
                    readOnly = true;
                }
                case "fake-accept" -> {
                    if (fakeSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "fake");
                    }
                    fakeMode = FakeRcsAdapter.Mode.ACCEPT;
                    fakeSeen = true;
                }
                case "fake-reject" -> {
                    if (fakeSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "fake");
                    }
                    fakeMode = FakeRcsAdapter.Mode.REJECT;
                    fakeSeen = true;
                }
                case "fake-timeout-created" -> {
                    if (fakeSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "fake");
                    }
                    fakeMode = FakeRcsAdapter.Mode.TIMEOUT_BUT_CREATED;
                    fakeSeen = true;
                }
                case "fake-timeout-not-created" -> {
                    if (fakeSeen) {
                        throw new StartupException("STARTUP_CONFLICT", "fake");
                    }
                    fakeMode = FakeRcsAdapter.Mode.TIMEOUT_NOT_CREATED;
                    fakeSeen = true;
                }
                default -> throw new StartupException("STARTUP_ARGUMENT", arg);
            }
        }

        return new StartupOptions(storage, readOnly, fakeMode);
    }

    /**
     * 按解析结果创建对应的 Storage 实例。
     */
    public Storage createStorage() {
        return switch (storageMode) {
            case MEMORY -> new MemoryStorage();
            case FILE -> new FileStorage(Path.of("data"));
            case SQLITE -> new SqliteStorage(Path.of("data", "logistics.db"), readOnly);
        };
    }
}
