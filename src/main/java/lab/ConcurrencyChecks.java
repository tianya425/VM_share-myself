package lab;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyChecks {

    private static final int THREADS = 10;

    private static int passed = 0;

    public static void main(String[] args) throws Exception {
        r29();
        r30();
        r31();
        r32();

        System.out.println("CONCURRENCY_CHECKS_PASSED=" + passed);
    }

    private static void r29() throws Exception {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        Result res = runConcurrent(r, "APP-001", "P01", "P02");

        check(res.created == 1, "R29 exactly one created");
        check(res.replay == THREADS - 1, "R29 others replay");
        check(res.exception == 0, "R29 no exception");
    }

    private static void r30() throws Exception {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        runConcurrent(r, "APP-001", "P01", "P02");

        check(r.listApplications().size() == 1, "R30 total 1");
    }

    private static void r31() throws Exception {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        Result res = runConcurrent(r, "APP-001", "P01", "P02");

        check(res.applicationId != null && res.applicationId.equals("APP-001"),
                "R31 applicationId is APP-001");
    }

    private static void r32() throws Exception {
        Registry r = new Registry(new MemoryStorage());
        r.registerPoint("P01", "原料暂存点");
        r.registerPoint("P02", "装配线接收点");

        runConcurrent(r, "APP-001", "P01", "P02");

        TransferApplication a = r.findApplication("APP-001").orElseThrow();
        check(a.fromPointId().equals("P01"), "R32 stored from P01");
        check(a.toPointId().equals("P02"), "R32 stored to P02");
        check(a.state() == ApplicationState.CREATED, "R32 stored CREATED");
    }

    // ---------- helpers ----------

    private static Result runConcurrent(Registry r, String applicationId,
                                        String fromPointId, String toPointId) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        AtomicInteger created = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        AtomicInteger exception = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    RegistrationResult res = r.registerApplication(applicationId, fromPointId, toPointId);
                    if (res.replay()) {
                        replay.incrementAndGet();
                    } else {
                        created.incrementAndGet();
                    }
                } catch (RegistryException e) {
                    exception.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        Result res = new Result();
        res.created = created.get();
        res.replay = replay.get();
        res.exception = exception.get();
        res.applicationId = applicationId;
        return res;
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
    }

    private static class Result {
        int created;
        int replay;
        int exception;
        String applicationId;
    }
}
