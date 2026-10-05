package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.RangeReadResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Readers (full + range, current + historical) running concurrently with publishing writers:
 * every result's version metadata and bytes must come from the SAME complete version snapshot;
 * range bytes must equal the expected slice; no torn reads and no version/content mismatch.
 */
class ConcurrentReadWriteTest {

    @TempDir
    Path tempDir;

    private final Map<Long, byte[]> bytesByVersion = new ConcurrentHashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger checks = new AtomicInteger();
    private final List<String> sample = Collections.synchronizedList(new ArrayList<>());

    private StorageTestSupport.Stack stack;
    private String object;
    private CountDownLatch start;
    private volatile long writersDoneAt;
    private int writerCount;

    @Test
    void concurrentPublicationsNeverTearSnapshotOrRangeReads() throws Exception {
        stack = StorageTestSupport.newStack(tempDir);
        object = "hot.log";
        int writers = 6;
        List<byte[]> payloads = new ArrayList<>();
        for (int v = 1; v <= writers; v++) {
            payloads.add(("payload-for-writer-" + v + ":").repeat(200).getBytes(StandardCharsets.UTF_8));
        }

        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        this.start = new CountDownLatch(1);
        this.writerCount = writers;
        List<Future<?>> futures = new ArrayList<>();

        for (int v = 1; v <= writers; v++) {
            final int writerIndex = v - 1;
            futures.add(pool.submit(() -> {
                start.await();
                StoredObject published = StorageTestSupport.multipartUpload(stack.service(),
                        stack.checksums(), object, List.of(payloads.get(writerIndex)));
                bytesByVersion.put(published.version(), payloads.get(writerIndex));
                System.out.printf("[rwx] writer %d published version=%d%n", writerIndex + 1, published.version());
                return null;
            }));
        }
        futures.add(pool.submit(this::currentReaderLoop));
        futures.add(pool.submit(this::historicalRangeReaderLoop));

        start.countDown();
        for (int i = 0; i < writerCount; i++) {
            futures.get(i).get(60, TimeUnit.SECONDS);
        }
        writersDoneAt = System.currentTimeMillis();
        for (int i = writerCount; i < futures.size(); i++) {
            futures.get(i).get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertNull(failure.get(), failure.get() == null ? "" : failure.get().getMessage());
        StoredObject head = stack.service().headObject(object);
        assertEquals(writers, head.version(), "all distinct versions published, current is the last");
        for (long v = 1; v <= writers; v++) {
            try (InputStream in = stack.service().readVersion(object, v)) {
                assertTrue(Arrays.equals(bytesByVersion.get(v), in.readAllBytes()));
            }
        }
        assertEquals(writers, bytesByVersion.size());
        System.out.printf("[rwx] %d consistent snapshot/range checks; samples=%s; final current=v%d%n",
                checks.get(), sample, head.version());
    }

    private Void currentReaderLoop() throws InterruptedException {
        startReading();
        long deadline = System.currentTimeMillis() + 30_000;
        while (readersStillActive(deadline)) {
            RangeReadResult r;
            try {
                r = stack.service().readObjectRange(object, null);
            } catch (UploadNotFoundException none) {
                Thread.yield();
                continue;
            }
            byte[] expected = awaitExpected(r.version().version());
            if (expected == null) {
                return null;
            }
            byte[] actual;
            try (InputStream in = r.content()) {
                actual = in.readAllBytes();
            } catch (Exception e) {
                fail(e);
                return null;
            }
            if (!Arrays.equals(expected, actual)) {
                fail(new AssertionError("snapshot mismatch: header v" + r.version().version()
                        + " but bytes differ"));
                return null;
            }
            checks.incrementAndGet();
        }
        return null;
    }

    private Void historicalRangeReaderLoop() throws InterruptedException {
        startReading();
        long deadline = System.currentTimeMillis() + 30_000;
        while (readersStillActive(deadline)) {
            for (long v = 1; v <= writerCount; v++) {
                byte[] expected = awaitExpected(v);
                if (expected == null) {
                    continue;
                }
                int from = Math.min(10, expected.length - 1);
                int to = Math.min(expected.length - 1, from + 49);
                RangeReadResult r;
                try {
                    r = stack.service().readVersionRange(object, v, "bytes=" + from + "-" + to);
                } catch (UploadNotFoundException early) {
                    continue; // that version has not been published yet
                }
                byte[] expectedSlice = Arrays.copyOfRange(expected,
                        (int) r.range().start(), (int) r.range().end() + 1);
                byte[] actual;
                try (InputStream in = r.content(); ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
                    in.transferTo(sink);
                    actual = sink.toByteArray();
                } catch (Exception e) {
                    fail(e);
                    return null;
                }
                if (r.version().version() != v || !Arrays.equals(expectedSlice, actual)) {
                    fail(new AssertionError("historical range mismatch: requested v" + v + " got v"
                            + r.version().version() + " len=" + actual.length));
                    return null;
                }
                if (checks.incrementAndGet() <= 5) {
                    sample.add("v" + v + " bytes=" + r.range().start() + "-" + r.range().end());
                }
            }
        }
        return null;
    }

    private void startReading() throws InterruptedException {
        start.await();
    }

    /** Readers run until 500 ms after all writers finished (bounded by the hard deadline). */
    private boolean readersStillActive(long hardDeadline) {
        long now = System.currentTimeMillis();
        if (now >= hardDeadline) {
            return false;
        }
        return writersDoneAt == 0 || now < writersDoneAt + 500;
    }

    private byte[] awaitExpected(Long version) throws InterruptedException {
        byte[] expected = bytesByVersion.get(version);
        for (int i = 0; expected == null && i < 500; i++) {
            Thread.sleep(2);
            expected = bytesByVersion.get(version);
        }
        return expected;
    }

    private void fail(Throwable t) {
        failure.compareAndSet(null, t);
    }
}
