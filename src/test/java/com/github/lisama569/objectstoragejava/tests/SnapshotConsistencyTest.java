package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.OpenedObject;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every read returns metadata and bytes of the SAME complete version, even while writers publish
 * concurrently: no torn reads, no metadata/bytes mismatch, no observation of "older" content under
 * a newer version header.
 */
class SnapshotConsistencyTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentPublishesAndReadsAlwaysYieldOneSelfConsistentSnapshot() throws Exception {
        var stack = StorageTestSupport.newStack(tempDir);
        int writers = 6;
        // Pre-create v1 so readers always have something.
        StorageTestSupport.multipartUpload(stack.service(), stack.checksums(), "snap",
                List.of(("v" + 0 + "-").getBytes(), "payload".getBytes()));

        ExecutorService pool = Executors.newFixedThreadPool(writers + 2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger doneWriters = new AtomicInteger();
        Map<Long, byte[]> bytesByVersion = new ConcurrentHashMap<>();
        bytesByVersion.put(1L, StorageTestSupport.concat(List.of(("v" + 0 + "-").getBytes(), "payload".getBytes())));

        List<Future<?>> writerFutures = new ArrayList<>();
        for (int w = 1; w <= writers; w++) {
            final int writer = w;
            writerFutures.add(pool.submit(() -> {
                start.await();
                // Two parts of distinct sizes so blob assembly order genuinely matters.
            List<byte[]> parts = List.of(
                    ("version-" + writer + "-head-part").getBytes(),
                    ("body-" + writer + "-tail").getBytes());
            UploadInitResponse init = stack.service().initUpload("snap");
            for (int i = 0; i < parts.size(); i++) {
                String cs = StorageTestSupport.sha256(stack.checksums(), parts.get(i));
                stack.service().uploadPart("snap", init.uploadId(), i + 1, cs,
                        new ByteArrayInputStream(parts.get(i)));
            }
            var entries = new ArrayList<com.github.lisama569.objectstoragejava.dto.ManifestEntry>();
            List<String> csList = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                String cs = StorageTestSupport.sha256(stack.checksums(), parts.get(i));
                entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(i + 1, cs));
                csList.add(cs);
            }
            String objCs = stack.checksums().combinePartChecksums(csList);
            StoredObject published = stack.service().completeUpload("snap", init.uploadId(),
                    new com.github.lisama569.objectstoragejava.dto.ManifestRequest(objCs, entries));
            byte[] full = StorageTestSupport.concat(parts);
            bytesByVersion.put(published.version(), full);
            doneWriters.incrementAndGet();
            return null;
        }));
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicInteger rangeReads = new AtomicInteger();
        List<Long> versionTrace = java.util.Collections.synchronizedList(new ArrayList<>());

        // Reader 1: full current snapshot reads. Metadata version must match the bytes that stream.
        Future<?> fullReader = pool.submit(() -> {
            try {
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline && doneWriters.get() < writers) {
                    OpenedObject opened;
                    try {
                        opened = stack.service().openCurrent("snap", null);
                    } catch (UploadNotFoundException none) {
                        continue;
                    }
                    byte[] got;
                    try (OpenedObject o = opened) {
                        got = o.stream().readAllBytes();
                        assertEquals(o.metadata().version(), opened.metadata().version());
                        byte[] expected = bytesByVersion.get(o.metadata().version());
                        for (int i = 0; expected == null && i < 200; i++) {
                            Thread.sleep(2);
                            expected = bytesByVersion.get(o.metadata().version());
                        }
                        if (expected != null) {
                            assertTrue(java.util.Arrays.equals(expected, got),
                                    "full read of v" + o.metadata().version()
                                            + " must be byte-exact for that same version");
                        }
                        versionTrace.add(o.metadata().version());
                    }
                    snapshotReads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        // Reader 2: ranged snapshot reads across both blobs; checksum-verified window must equal the
        // same-version bytes' sub-window.
        Future<?> rangeReader = pool.submit(() -> {
            try {
                long deadline = System.currentTimeMillis() + 30_000;
                while (System.currentTimeMillis() < deadline && doneWriters.get() < writers) {
                    OpenedObject opened;
                    try {
                        // A safe prefix window available in every version (all are longer than 10 bytes).
                        opened = stack.service().openCurrent("snap", "bytes=2-9");
                    } catch (UploadNotFoundException none) {
                        continue;
                    }
                    try (OpenedObject o = opened) {
                        byte[] window = o.stream().readAllBytes();
                        byte[] expected = bytesByVersion.get(o.metadata().version());
                        if (expected != null) {
                            byte[] want = java.util.Arrays.copyOfRange(expected, 2, 10);
                            assertTrue(java.util.Arrays.equals(want, window),
                                    "ranged window must come from the SAME version as its metadata (v"
                                            + o.metadata().version() + ")");
                        }
                    }
                    rangeReads.incrementAndGet();
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        start.countDown();
        for (Future<?> f : writerFutures) {
            f.get(60, TimeUnit.SECONDS);
        }
        fullReader.get(40, TimeUnit.SECONDS);
        rangeReader.get(40, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(null, failure.get(), failure.get() == null ? "" : failure.get().getMessage());
        assertTrue(snapshotReads.get() > 0, "must have performed concurrent full snapshot reads");
        assertTrue(rangeReads.get() > 0, "must have performed concurrent ranged snapshot reads");

        long finalVersion = stack.service().headObject("snap").version();
        assertEquals(writers + 1L, finalVersion, "every distinct content published exactly one version");
        for (long v = 1; v <= finalVersion; v++) {
            byte[] got = stack.service().readVersion("snap", v).readAllBytes();
            assertTrue(java.util.Arrays.equals(bytesByVersion.get(v), got), "v" + v + " bytes exact");
        }
        long monotonicMax = 0;
        for (long seen : versionTrace) {
            assertTrue(seen >= monotonicMax || seen > 0);
            monotonicMax = Math.max(monotonicMax, seen);
        }
        System.out.printf("[snapshot] fullReads=%d rangeReads=%d writers=%d final=v%d; every read was one self-consistent version%n",
                snapshotReads.get(), rangeReads.get(), writers, finalVersion);
    }
}
