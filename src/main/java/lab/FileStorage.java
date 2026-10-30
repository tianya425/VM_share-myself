package lab;

import lab.simulation.resource.Destination;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceAvailability;
import lab.simulation.resource.ResourceStateValidator;
import lab.simulation.resource.ResourceWorldSnapshot;
import lab.simulation.resource.ServicePoint;
import lab.simulation.resource.VirtualResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 文件存储：单快照原子提交（STU-SIM-02-R1 §11）。
 *
 * <p>权威文件固定为同目录下的 {@code registry-state.txt}，section 顺序固定：
 * FORMAT / POINTS / APPLICATIONS / OUTBOX / INBOX / WORKFLOWS / DESTINATIONS /
 * SERVICE_POINTS / VIRTUAL_RESOURCES / RESERVATIONS / END。每个 section 按稳定键
 * 排序，字段编码沿用原有格式函数。
 *
 * <p>{@link #commitState(RegistryState)} 固定 12 步：校验候选 → 序列化确定性字节 →
 * 同目录唯一 staging → 写全量字节 → flush → fsync staging → 从 staging 重新解析 →
 * 校验解析结果 → 比较规范摘要 → 单次 ATOMIC_MOVE + REPLACE_EXISTING → fsync 父目录
 * （平台支持时）→ 成功返回。最终切换前任一步失败都不会改动旧
 * {@code registry-state.txt}，也不会发布内存候选状态。
 *
 * <p>旧六文件兼容读取：{@code registry-state.txt} 存在时只读新快照；不存在时
 * 读取旧六文件组成 {@link RegistryState} 并执行统一校验（LEGACY_READ_FALLBACK_ONLY）。
 * 第一次成功提交写出新快照；旧六文件不自动删除；新快照存在后不再混合读取。
 *
 * <p>文件操作通过 {@link StateFileOps} 注入，生产构造器使用真实实现；
 * 测试构造器可注入在 write 或 atomicMove 抛 {@link IOException} 的实现，
 * 不使用真实文件权限制造故障（§11.4）。
 */
public class FileStorage implements Storage {

    private static final String SNAPSHOT_FILE_NAME = "registry-state.txt";
    private static final String STAGING_SUFFIX = ".staging";
    private static final int FORMAT_VERSION = 1;

    private static final StateFileOps REAL_OPS = new StateFileOps() {
        @Override
        public void writeAndSync(Path staging, byte[] bytes) throws IOException {
            try (FileChannel channel = FileChannel.open(staging,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
        }

        @Override
        public void atomicMove(Path staging, Path target) throws IOException {
            try {
                Files.move(staging, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic move not supported", e);
            }
        }
    };

    private final Path dataDir;
    private final Path snapshotFile;
    private final StateFileOps ops;
    private final Path pointsFile;
    private final Path applicationsFile;
    private final Path outboxFile;
    private final Path inboxFile;
    private final Path workflowsFile;
    private final Path resourcesFile;

    /** 生产构造器：真实文件操作。 */
    public FileStorage(Path dataDir) {
        this(dataDir, REAL_OPS);
    }

    /** 可注入构造器：测试可注入 write / atomicMove 失败的实现（§11.4）。 */
    public FileStorage(Path dataDir, StateFileOps ops) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir", e);
        }
        this.dataDir = dataDir;
        this.ops = Objects.requireNonNull(ops, "ops");
        this.snapshotFile = dataDir.resolve(SNAPSHOT_FILE_NAME);
        this.pointsFile = dataDir.resolve("points.txt");
        this.applicationsFile = dataDir.resolve("applications.txt");
        this.outboxFile = dataDir.resolve("outbox.txt");
        this.inboxFile = dataDir.resolve("inbox.txt");
        this.workflowsFile = dataDir.resolve("workflows.txt");
        this.resourcesFile = dataDir.resolve("resources.txt");
    }

    /**
     * 读取整份状态。
     *
     * <p>{@code registry-state.txt} 存在时只读这一份权威快照；否则回退读取旧六文件
     * （LEGACY_READ_FALLBACK_ONLY）。两条路径返回前都执行统一校验。
     */
    @Override
    public RegistryState loadState() {
        RegistryState state;
        if (Files.exists(snapshotFile)) {
            state = readSnapshot(snapshotFile);
        } else {
            // LEGACY_READ_FALLBACK_ONLY：仅当新快照尚不存在时读取旧六文件。
            // 新快照写出后不再混合读取，旧六文件保持原样、不自动删除。
            state = readLegacyState();
        }
        ResourceStateValidator.validate(state);
        return state;
    }

    /**
     * 单快照原子提交：最终切换前失败时旧 {@code registry-state.txt} 保持原字节，
     * 且不发布内存候选状态。
     */
    @Override
    public void commitState(RegistryState next) {
        // 1. 校验候选状态。
        ResourceStateValidator.validate(next);
        // 2. 序列化完整 next 为确定性字节。
        byte[] bytes = render(next).getBytes(StandardCharsets.UTF_8);

        Path staging = null;
        try {
            // 3. 同目录创建唯一 staging 文件。
            staging = Files.createTempFile(dataDir, "registry-state-", STAGING_SUFFIX);
            // 4-6. 写全部字节、flush、fsync staging。
            ops.writeAndSync(staging, bytes);
            // 7. 从 staging 重新 parse。
            RegistryState parsed = readSnapshot(staging);
            // 8. 校验解析结果。
            ResourceStateValidator.validate(parsed);
            // 9. 比较 parsedState 规范摘要与 next 摘要。
            String expectedDigest = sha256Hex(bytes);
            String actualDigest = sha256Hex(render(parsed).getBytes(StandardCharsets.UTF_8));
            if (!expectedDigest.equals(actualDigest)) {
                throw new IOException("snapshot canonical digest mismatch");
            }
            // 10. 单次 ATOMIC_MOVE + REPLACE_EXISTING 切换为 registry-state.txt。
            ops.atomicMove(staging, snapshotFile);
            staging = null;
            // 11. fsync 父目录（平台支持时）。
            fsyncDirectory(dataDir);
            // 12. 成功返回。
        } catch (IOException e) {
            cleanup(staging, e);
            throw new RuntimeException("cannot commit state", e);
        } catch (RuntimeException e) {
            cleanup(staging, e);
            throw e;
        }
    }

    // ---------- 新快照：序列化 ----------

    private static String render(RegistryState state) {
        StringBuilder builder = new StringBuilder();
        builder.append("FORMAT|").append(FORMAT_VERSION).append('\n');

        builder.append("POINTS\n");
        builder.append(formatPoints(sorted(state.points(), Comparator.comparing(Point::pointId))));

        builder.append("APPLICATIONS\n");
        builder.append(formatApplications(sorted(state.applications(),
                Comparator.comparing(TransferApplication::applicationId))));

        builder.append("OUTBOX\n");
        builder.append(formatOutbox(sorted(state.outbox(),
                Comparator.comparing(OutboxEntry::entryId))));

        builder.append("INBOX\n");
        builder.append(formatInbox(sorted(state.inbox(),
                Comparator.comparing(InboxEntry::entryId))));

        builder.append("WORKFLOWS\n");
        builder.append(formatWorkflows(sorted(state.workflows(),
                Comparator.comparing(WorkflowInstance::instanceId))));

        ResourceWorldSnapshot world = state.resourceWorld();
        builder.append("DESTINATIONS\n");
        builder.append(formatDestinations(sorted(world.destinations(),
                Comparator.comparing(Destination::destinationId))));

        builder.append("SERVICE_POINTS\n");
        builder.append(formatServicePoints(sorted(world.servicePoints(),
                Comparator.comparing(ServicePoint::servicePointId))));

        builder.append("VIRTUAL_RESOURCES\n");
        builder.append(formatVirtualResources(sorted(world.resources(),
                Comparator.comparing(VirtualResource::resourceId))));

        builder.append("RESERVATIONS\n");
        builder.append(formatReservations(sorted(world.reservations(), reservationOrder())));

        builder.append("END\n");
        return builder.toString();
    }

    /** 快照内 Reservation 固定排序键：offset → logicalSequence → applicationId。 */
    private static Comparator<Reservation> reservationOrder() {
        return Comparator.comparingLong(Reservation::requestedOffsetMillis)
                .thenComparingLong(Reservation::requestedLogicalSequence)
                .thenComparing(Reservation::applicationId);
    }

    private static <T> List<T> sorted(List<T> values, Comparator<T> comparator) {
        List<T> copy = new ArrayList<>(values);
        copy.sort(comparator);
        return copy;
    }

    private static String formatPoints(List<Point> points) {
        StringBuilder builder = new StringBuilder();
        for (Point p : points) {
            builder.append("P|").append(p.pointId()).append('|').append(p.name()).append('\n');
        }
        return builder.toString();
    }

    private static String formatApplications(List<TransferApplication> applications) {
        StringBuilder builder = new StringBuilder();
        for (TransferApplication a : applications) {
            String external = a.externalOrderId() == null ? "" : a.externalOrderId();
            builder.append("A|").append(a.applicationId()).append('|')
                    .append(a.fromPointId()).append('|')
                    .append(a.toPointId()).append('|')
                    .append(a.state().name()).append('|')
                    .append(external).append('\n');
        }
        return builder.toString();
    }

    private static String formatOutbox(List<OutboxEntry> outbox) {
        StringBuilder builder = new StringBuilder();
        for (OutboxEntry e : outbox) {
            String external = e.externalOrderId() == null ? "" : e.externalOrderId();
            builder.append("O|").append(e.entryId()).append('|')
                    .append(e.applicationId()).append('|')
                    .append(e.fromPointId()).append('|')
                    .append(e.toPointId()).append('|')
                    .append(e.status().name()).append('|')
                    .append(external).append('|')
                    .append(e.attempts()).append('\n');
        }
        return builder.toString();
    }

    private static String formatInbox(List<InboxEntry> inbox) {
        StringBuilder builder = new StringBuilder();
        for (InboxEntry e : inbox) {
            String reason = e.reason() == null ? "" : e.reason();
            builder.append("I|").append(e.entryId()).append('|')
                    .append(e.applicationId()).append('|')
                    .append(e.eventType()).append('|')
                    .append(e.status().name()).append('|')
                    .append(reason).append('\n');
        }
        return builder.toString();
    }

    private static String formatWorkflows(List<WorkflowInstance> workflows) {
        StringBuilder builder = new StringBuilder();
        for (WorkflowInstance wf : workflows) {
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < wf.steps().size(); i++) {
                if (i > 0) {
                    steps.append(',');
                }
                WorkflowStep step = wf.steps().get(i);
                steps.append(step.name()).append(':').append(step.state().name());
            }
            builder.append("W|").append(wf.instanceId()).append('|')
                    .append(wf.applicationId()).append('|')
                    .append(wf.state().name()).append('|')
                    .append(wf.currentStepIndex()).append('|')
                    .append(steps).append('\n');
        }
        return builder.toString();
    }

    private static String formatDestinations(List<Destination> destinations) {
        StringBuilder builder = new StringBuilder();
        for (Destination destination : destinations) {
            builder.append("D|").append(destination.destinationId()).append('|')
                    .append(destination.name()).append('\n');
        }
        return builder.toString();
    }

    private static String formatServicePoints(List<ServicePoint> servicePoints) {
        StringBuilder builder = new StringBuilder();
        for (ServicePoint servicePoint : servicePoints) {
            builder.append("S|").append(servicePoint.servicePointId()).append('|')
                    .append(servicePoint.destinationId()).append('|')
                    .append(servicePoint.pointId()).append('\n');
        }
        return builder.toString();
    }

    private static String formatVirtualResources(List<VirtualResource> resources) {
        StringBuilder builder = new StringBuilder();
        for (VirtualResource resource : resources) {
            builder.append("R|").append(resource.resourceId()).append('|')
                    .append(resource.servicePointId()).append('|')
                    .append(resource.availability().name()).append('\n');
        }
        return builder.toString();
    }

    private static String formatReservations(List<Reservation> reservations) {
        StringBuilder builder = new StringBuilder();
        for (Reservation reservation : reservations) {
            builder.append("V|").append(reservation.reservationId()).append('|')
                    .append(reservation.applicationId()).append('|')
                    .append(reservation.destinationId()).append('|')
                    .append(reservation.servicePointId()).append('|')
                    .append(reservation.resourceId()).append('|')
                    .append(reservation.state().name()).append('|')
                    .append(reservation.requestedOffsetMillis()).append('|')
                    .append(reservation.requestedLogicalSequence()).append('\n');
        }
        return builder.toString();
    }

    private static String formatResourceWorld(ResourceWorldSnapshot world) {
        return formatDestinations(world.destinations())
                + formatServicePoints(world.servicePoints())
                + formatVirtualResources(world.resources())
                + formatReservations(world.reservations());
    }

    // ---------- 新快照：解析 ----------

    private static RegistryState readSnapshot(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("cannot read registry snapshot", e);
        }

        ParsedState parsed = new ParsedState();
        int index = requireLine(lines, 0, "FORMAT|" + FORMAT_VERSION);
        index = readSection(lines, index, "POINTS", "APPLICATIONS",
                line -> parsed.points.add(parsePoint(line)));
        index = readSection(lines, index, "APPLICATIONS", "OUTBOX",
                line -> parsed.applications.add(parseApplication(line)));
        index = readSection(lines, index, "OUTBOX", "INBOX",
                line -> parsed.outbox.add(parseOutboxEntry(line)));
        index = readSection(lines, index, "INBOX", "WORKFLOWS",
                line -> parsed.inbox.add(parseInboxEntry(line)));
        index = readSection(lines, index, "WORKFLOWS", "DESTINATIONS",
                line -> parsed.workflows.add(parseWorkflow(line)));
        index = readSection(lines, index, "DESTINATIONS", "SERVICE_POINTS",
                line -> parsed.destinations.add(parseDestination(line)));
        index = readSection(lines, index, "SERVICE_POINTS", "VIRTUAL_RESOURCES",
                line -> parsed.servicePoints.add(parseServicePoint(line)));
        index = readSection(lines, index, "VIRTUAL_RESOURCES", "RESERVATIONS",
                line -> parsed.resources.add(parseVirtualResource(line)));
        index = readSection(lines, index, "RESERVATIONS", "END",
                line -> parsed.reservations.add(parseReservation(line)));
        index = requireLine(lines, index, "END");
        while (index < lines.size() && lines.get(index).isEmpty()) {
            index++;
        }
        if (index != lines.size()) {
            throw new RuntimeException("cannot read registry snapshot: trailing content");
        }
        return new RegistryState(
                parsed.points,
                parsed.applications,
                parsed.outbox,
                parsed.inbox,
                parsed.workflows,
                new ResourceWorldSnapshot(parsed.destinations, parsed.servicePoints,
                        parsed.resources, parsed.reservations));
    }

    private static int requireLine(List<String> lines, int index, String expected) {
        if (index >= lines.size() || !lines.get(index).equals(expected)) {
            throw new RuntimeException("cannot read registry snapshot: expected " + expected
                    + " at line " + (index + 1));
        }
        return index + 1;
    }

    private static int readSection(List<String> lines, int index, String header,
                                   String nextHeader, Consumer<String> consumer) {
        index = requireLine(lines, index, header);
        while (index < lines.size() && !lines.get(index).equals(nextHeader)) {
            String line = lines.get(index);
            if (line.isEmpty()) {
                throw new RuntimeException("cannot read registry snapshot: empty line in " + header);
            }
            consumer.accept(line);
            index++;
        }
        if (index >= lines.size()) {
            throw new RuntimeException("cannot read registry snapshot: missing " + nextHeader);
        }
        return index;
    }

    private static Point parsePoint(String line) {
        String[] parts = splitRecord(line, "P", 3);
        return new Point(parts[1], parts[2]);
    }

    private static TransferApplication parseApplication(String line) {
        String[] parts = splitRecord(line, "A", 6);
        ApplicationState state;
        try {
            state = ApplicationState.valueOf(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read registry snapshot: A.state=" + parts[4], e);
        }
        String externalOrderId = parts[5].isEmpty() ? null : parts[5];
        return new TransferApplication(parts[1], parts[2], parts[3], state, externalOrderId);
    }

    private static OutboxEntry parseOutboxEntry(String line) {
        String[] parts = splitRecord(line, "O", 8);
        OutboxStatus status;
        try {
            status = OutboxStatus.valueOf(parts[5]);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read registry snapshot: O.status=" + parts[5], e);
        }
        String externalOrderId = parts[6].isEmpty() ? null : parts[6];
        int attempts;
        try {
            attempts = Integer.parseInt(parts[7]);
        } catch (NumberFormatException e) {
            throw new RuntimeException("cannot read registry snapshot: O.attempts=" + parts[7], e);
        }
        return new OutboxEntry(parts[1], parts[2], parts[3], parts[4],
                status, externalOrderId, attempts);
    }

    private static InboxEntry parseInboxEntry(String line) {
        String[] parts = splitRecord(line, "I", 6);
        InboxStatus status;
        try {
            status = InboxStatus.valueOf(parts[4]);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read registry snapshot: I.status=" + parts[4], e);
        }
        return new InboxEntry(parts[1], parts[2], parts[3], status, parts[5]);
    }

    private static WorkflowInstance parseWorkflow(String line) {
        String[] parts = splitRecord(line, "W", 6);
        WorkflowState state;
        try {
            state = WorkflowState.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read registry snapshot: W.state=" + parts[3], e);
        }
        int currentStepIndex;
        try {
            currentStepIndex = Integer.parseInt(parts[4]);
        } catch (NumberFormatException e) {
            throw new RuntimeException("cannot read registry snapshot: W.currentStepIndex=" + parts[4], e);
        }
        List<WorkflowStep> steps = new ArrayList<>();
        for (String stepText : parts[5].split(",", -1)) {
            String[] kv = stepText.split(":", -1);
            if (kv.length != 2) {
                throw new RuntimeException("cannot read registry snapshot: W.steps=" + parts[5]);
            }
            WorkflowStepState stepState;
            try {
                stepState = WorkflowStepState.valueOf(kv[1]);
            } catch (IllegalArgumentException e) {
                throw new RuntimeException("cannot read registry snapshot: W.step.state=" + kv[1], e);
            }
            steps.add(new WorkflowStep(kv[0], stepState));
        }
        return new WorkflowInstance(parts[1], parts[2], state, currentStepIndex, List.copyOf(steps));
    }

    private static Destination parseDestination(String line) {
        String[] parts = splitRecord(line, "D", 3);
        try {
            return new Destination(parts[1], parts[2]);
        } catch (IllegalArgumentException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "destination", parts[1], e);
        }
    }

    private static ServicePoint parseServicePoint(String line) {
        String[] parts = splitRecord(line, "S", 4);
        try {
            return new ServicePoint(parts[1], parts[2], parts[3]);
        } catch (IllegalArgumentException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "servicePoint", parts[1], e);
        }
    }

    private static VirtualResource parseVirtualResource(String line) {
        String[] parts = splitRecord(line, "R", 4);
        ResourceAvailability availability;
        try {
            availability = ResourceAvailability.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "availability", parts[3], e);
        }
        try {
            return new VirtualResource(parts[1], parts[2], availability);
        } catch (IllegalArgumentException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "virtualResource", parts[1], e);
        }
    }

    private static Reservation parseReservation(String line) {
        String[] parts = splitRecord(line, "V", 9);
        ReservationState state;
        try {
            state = ReservationState.valueOf(parts[6]);
        } catch (IllegalArgumentException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "state", parts[6], e);
        }
        long offsetMillis;
        long logicalSequence;
        try {
            offsetMillis = parseLong(parts[7], "requestedOffsetMillis");
            logicalSequence = parseLong(parts[8], "requestedLogicalSequence");
        } catch (RuntimeException e) {
            throw invalidRecord("R03_SCALAR", parts[1], "requestedOffsetMillis", parts[7], e);
        }
        try {
            return new Reservation(parts[1], parts[2], parts[3], parts[4], parts[5],
                    state, offsetMillis, logicalSequence);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("RESERVATION_ID") || message.contains("formulaMismatch")) {
                throw invalidRecord("R11_RESERVATION_ID_FORMULA", parts[1],
                        "reservationId", parts[1], e);
            }
            throw invalidRecord("R03_SCALAR", parts[1], "reservation", parts[1], e);
        }
    }

    private static String[] splitRecord(String line, String type, int expectedFields) {
        String[] parts = line.split("\\|", -1);
        if (parts.length != expectedFields || !parts[0].equals(type)) {
            throw new RuntimeException("cannot read registry snapshot: record " + type
                    + " fields=" + parts.length + " expected=" + expectedFields);
        }
        return parts;
    }

    private static RegistryException invalidRecord(String rule, String entity, String field,
                                                   String actual, RuntimeException cause) {
        RegistryException exception = new RegistryException(ResourceStateValidator.ERROR_CODE,
                "rule=" + rule + ";entity=" + entity + ";field=" + field + ";actual=" + actual);
        exception.initCause(cause);
        return exception;
    }

    // ---------- 旧六文件兼容读取（LEGACY_READ_FALLBACK_ONLY） ----------

    private RegistryState readLegacyState() {
        return new RegistryState(
                readPoints(),
                readApplications(),
                readOutbox(),
                readInbox(),
                readWorkflows(),
                readResourceWorld());
    }

    private List<Point> readPoints() {
        List<Point> result = new ArrayList<>();
        for (String[] parts : readLines(pointsFile)) {
            if (parts.length != 3 || !parts[0].equals("P")) {
                continue;
            }
            result.add(new Point(parts[1], parts[2]));
        }
        return result;
    }

    private List<TransferApplication> readApplications() {
        List<TransferApplication> result = new ArrayList<>();
        for (String[] parts : readLines(applicationsFile)) {
            if (parts.length != 6 || !parts[0].equals("A")) {
                continue;
            }
            ApplicationState state;
            try {
                state = ApplicationState.valueOf(parts[4]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String externalOrderId = parts[5].isEmpty() ? null : parts[5];
            result.add(new TransferApplication(parts[1], parts[2], parts[3], state, externalOrderId));
        }
        return result;
    }

    private List<OutboxEntry> readOutbox() {
        List<OutboxEntry> result = new ArrayList<>();
        for (String[] parts : readLines(outboxFile)) {
            if (parts.length != 8 || !parts[0].equals("O")) {
                continue;
            }
            OutboxStatus status;
            try {
                status = OutboxStatus.valueOf(parts[5]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            String externalOrderId = parts[6].isEmpty() ? null : parts[6];
            int attempts;
            try {
                attempts = Integer.parseInt(parts[7]);
            } catch (NumberFormatException e) {
                continue;
            }
            result.add(new OutboxEntry(parts[1], parts[2], parts[3], parts[4],
                    status, externalOrderId, attempts));
        }
        return result;
    }

    private List<InboxEntry> readInbox() {
        List<InboxEntry> result = new ArrayList<>();
        for (String[] parts : readLines(inboxFile)) {
            if (parts.length != 6 || !parts[0].equals("I")) {
                continue;
            }
            InboxStatus status;
            try {
                status = InboxStatus.valueOf(parts[4]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            result.add(new InboxEntry(parts[1], parts[2], parts[3], status, parts[5]));
        }
        return result;
    }

    private List<WorkflowInstance> readWorkflows() {
        List<WorkflowInstance> result = new ArrayList<>();
        for (String[] parts : readLines(workflowsFile)) {
            if (parts.length != 6 || !parts[0].equals("W")) {
                continue;
            }
            WorkflowState state;
            try {
                state = WorkflowState.valueOf(parts[3]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            int currentStepIndex;
            try {
                currentStepIndex = Integer.parseInt(parts[4]);
            } catch (NumberFormatException e) {
                continue;
            }
            List<WorkflowStep> steps = new ArrayList<>();
            boolean ok = true;
            for (String stepText : parts[5].split(",")) {
                String[] kv = stepText.split(":", -1);
                if (kv.length != 2) {
                    ok = false;
                    break;
                }
                WorkflowStepState stepState;
                try {
                    stepState = WorkflowStepState.valueOf(kv[1]);
                } catch (IllegalArgumentException e) {
                    ok = false;
                    break;
                }
                steps.add(new WorkflowStep(kv[0], stepState));
            }
            if (!ok) {
                continue;
            }
            result.add(new WorkflowInstance(parts[1], parts[2], state,
                    currentStepIndex, List.copyOf(steps)));
        }
        return result;
    }

    /**
     * 读资源世界。格式不合法时 fail-closed：抛异常而不是静默丢行，
     * 避免"读回一个缺少 holder / 目的地的世界"。
     */
    private ResourceWorldSnapshot readResourceWorld() {
        List<Destination> destinations = new ArrayList<>();
        List<ServicePoint> servicePoints = new ArrayList<>();
        List<VirtualResource> resources = new ArrayList<>();
        List<Reservation> reservations = new ArrayList<>();
        for (String[] parts : readLines(resourcesFile)) {
            switch (parts[0]) {
                case "D" -> {
                    requireFields(parts, 3, "D");
                    destinations.add(new Destination(parts[1], parts[2]));
                }
                case "S" -> {
                    requireFields(parts, 4, "S");
                    servicePoints.add(new ServicePoint(parts[1], parts[2], parts[3]));
                }
                case "R" -> {
                    requireFields(parts, 4, "R");
                    resources.add(new VirtualResource(parts[1], parts[2],
                            parseAvailability(parts[3])));
                }
                case "V" -> {
                    requireFields(parts, 9, "V");
                    reservations.add(new Reservation(parts[1], parts[2], parts[3], parts[4], parts[5],
                            parseReservationState(parts[6]),
                            parseLong(parts[7], "requestedOffsetMillis"),
                            parseLong(parts[8], "requestedLogicalSequence")));
                }
                default -> throw new RuntimeException(
                        "cannot read resource world: unknown record " + parts[0]);
            }
        }
        return new ResourceWorldSnapshot(destinations, servicePoints, resources, reservations);
    }

    private static void requireFields(String[] parts, int expected, String record) {
        if (parts.length != expected) {
            throw new RuntimeException("cannot read resource world: " + record
                    + " fields=" + parts.length + " expected=" + expected);
        }
    }

    private static ResourceAvailability parseAvailability(String value) {
        try {
            return ResourceAvailability.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read resource world: availability=" + value, e);
        }
    }

    private static ReservationState parseReservationState(String value) {
        try {
            return ReservationState.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("cannot read resource world: state=" + value, e);
        }
    }

    private static long parseLong(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new RuntimeException("cannot read resource world: " + field + "=" + value, e);
        }
    }

    /**
     * 读一个旧文件并切成 {@code |} 分隔的字段；文件不存在时返回空列表，空行跳过。
     */
    private List<String[]> readLines(Path file) {
        List<String[]> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                result.add(line.split("\\|", -1));
            }
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + file.getFileName(), e);
        }
        return result;
    }

    // ---------- 提交辅助 ----------

    private static void cleanup(Path staging, Throwable failure) {
        if (staging == null) {
            return;
        }
        try {
            Files.deleteIfExists(staging);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 平台不支持目录 fsync 时跳过（§11 第 11 步）。
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data);
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 解析中间态：只在 readSnapshot 内使用。 */
    private static final class ParsedState {
        private final List<Point> points = new ArrayList<>();
        private final List<TransferApplication> applications = new ArrayList<>();
        private final List<OutboxEntry> outbox = new ArrayList<>();
        private final List<InboxEntry> inbox = new ArrayList<>();
        private final List<WorkflowInstance> workflows = new ArrayList<>();
        private final List<Destination> destinations = new ArrayList<>();
        private final List<ServicePoint> servicePoints = new ArrayList<>();
        private final List<VirtualResource> resources = new ArrayList<>();
        private final List<Reservation> reservations = new ArrayList<>();
    }
}
