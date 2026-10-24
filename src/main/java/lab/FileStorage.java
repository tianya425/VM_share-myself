package lab;

import lab.simulation.resource.Destination;
import lab.simulation.resource.Reservation;
import lab.simulation.resource.ReservationState;
import lab.simulation.resource.ResourceAvailability;
import lab.simulation.resource.ResourceWorldSnapshot;
import lab.simulation.resource.ServicePoint;
import lab.simulation.resource.VirtualResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件存储：整状态原子提交。
 *
 * <p>提交顺序固定为：
 * <ol>
 *   <li>把五个集合各自写进同目录的 {@code *.staging} 暂存文件，写完 flush 并 fsync；</li>
 *   <li>全部暂存文件写成功之后，才逐个用
 *       {@code Files.move(ATOMIC_MOVE, REPLACE_EXISTING)} 替换旧文件。</li>
 * </ol>
 *
 * <p>任一步失败：删除暂存文件并抛异常，旧快照保持可用（不会出现"写了一半"的正式文件）。
 */
public class FileStorage implements Storage {

    private static final String STAGING_SUFFIX = ".staging";

    private final Path dataDir;
    private final Path pointsFile;
    private final Path applicationsFile;
    private final Path outboxFile;
    private final Path inboxFile;
    private final Path workflowsFile;
    private final Path resourcesFile;

    public FileStorage(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new RuntimeException("cannot create data dir: " + dataDir, e);
        }
        this.dataDir = dataDir;
        this.pointsFile = dataDir.resolve("points.txt");
        this.applicationsFile = dataDir.resolve("applications.txt");
        this.outboxFile = dataDir.resolve("outbox.txt");
        this.inboxFile = dataDir.resolve("inbox.txt");
        this.workflowsFile = dataDir.resolve("workflows.txt");
        this.resourcesFile = dataDir.resolve("resources.txt");
    }

    /** 从数据目录读取整份状态；文件缺失按空集合处理。 */
    @Override
    public RegistryState loadState() {
        return new RegistryState(
                readPoints(),
                readApplications(),
                readOutbox(),
                readInbox(),
                readWorkflows(),
                readResourceWorld());
    }

    /** 写入 {@code *.staging} 并 fsync 后 ATOMIC_MOVE 替换；失败保留旧快照、不留 staging。 */
    @Override
    public void commitState(RegistryState next) {
        List<Path> stagingFiles = new ArrayList<>();
        try {
            stagingFiles.add(writeStaging(pointsFile, formatPoints(next.points())));
            stagingFiles.add(writeStaging(applicationsFile, formatApplications(next.applications())));
            stagingFiles.add(writeStaging(outboxFile, formatOutbox(next.outbox())));
            stagingFiles.add(writeStaging(inboxFile, formatInbox(next.inbox())));
            stagingFiles.add(writeStaging(workflowsFile, formatWorkflows(next.workflows())));
            stagingFiles.add(writeStaging(resourcesFile, formatResourceWorld(next.resourceWorld())));

            moveIntoPlace(pointsFile, stagingFiles.get(0));
            moveIntoPlace(applicationsFile, stagingFiles.get(1));
            moveIntoPlace(outboxFile, stagingFiles.get(2));
            moveIntoPlace(inboxFile, stagingFiles.get(3));
            moveIntoPlace(workflowsFile, stagingFiles.get(4));
            moveIntoPlace(resourcesFile, stagingFiles.get(5));
        } catch (IOException e) {
            for (Path staging : stagingFiles) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new RuntimeException("cannot commit state: " + dataDir, e);
        }
    }

    // ---------- 提交辅助 ----------

    private Path writeStaging(Path target, String content) throws IOException {
        Path staging = target.resolveSibling(target.getFileName() + STAGING_SUFFIX);
        try (Writer writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
        }
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        return staging;
    }

    private void moveIntoPlace(Path target, Path staging) throws IOException {
        try {
            Files.move(staging, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("atomic move not supported: " + staging + " -> " + target, e);
        }
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

    private static String formatResourceWorld(ResourceWorldSnapshot world) {
        StringBuilder builder = new StringBuilder();
        for (Destination destination : world.destinations()) {
            builder.append("D|").append(destination.destinationId()).append('|')
                    .append(destination.name()).append('\n');
        }
        for (ServicePoint servicePoint : world.servicePoints()) {
            builder.append("S|").append(servicePoint.servicePointId()).append('|')
                    .append(servicePoint.destinationId()).append('|')
                    .append(servicePoint.registryPointId()).append('|')
                    .append(servicePoint.name()).append('\n');
        }
        for (VirtualResource resource : world.resources()) {
            builder.append("R|").append(resource.resourceId()).append('|')
                    .append(resource.servicePointId()).append('|')
                    .append(resource.availability().name()).append('\n');
        }
        for (Reservation reservation : world.reservations()) {
            builder.append("V|").append(reservation.reservationId()).append('|')
                    .append(reservation.applicationId()).append('|')
                    .append(reservation.destinationId()).append('|')
                    .append(reservation.servicePointId()).append('|')
                    .append(reservation.resourceId()).append('|')
                    .append(reservation.state().name()).append('|')
                    .append(reservation.offsetMillis()).append('|')
                    .append(reservation.logicalSequence()).append('\n');
        }
        return builder.toString();
    }

    // ---------- 读取 ----------

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
                    requireFields(parts, 5, "S");
                    servicePoints.add(new ServicePoint(parts[1], parts[2], parts[3], parts[4]));
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
                            parseLong(parts[7], "offsetMillis"),
                            parseLong(parts[8], "logicalSequence")));
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
     * 读一个文件并切成 {@code |} 分隔的字段；文件不存在时返回空列表，空行跳过。
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
            throw new RuntimeException("cannot read " + file, e);
        }
        return result;
    }
}
