package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.ManifestEntry;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency scenarios for multiple writers of the SAME object.
 * <p>
 * The observable contract under test is deterministic regardless of thread scheduling:
 * <ul>
 *     <li>every successful completion of distinct content creates exactly one new version;</li>
 *     <li>version numbers are strictly increasing and the current pointer never rolls back;</li>
 *     <li>a reader at any instant only observes a fully committed, byte-exact version;</li>
 *     <li>identical concurrent uploads collapse onto one version with one blob per unique part.</li>
 * </ul>
 */
class ConcurrentWritersTest {

    @TempDir
    Path tempDir;

    private static List<byte[]> payload(int writer, int parts) {
        List<byte[]> result = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            result.add(StorageTestSupport.utf8("writer=" + writer + ";part=" + p + ";payload-".repeat(200)));
        }
        return result;
    }

    @Test
    void concurrentDistinctWritersProduceOrderedVersionsAndNoTornReads() throws Exception {
        StorageTestSupport.Stack stack = StorageTestSupport.newStack(tempDir);
        int writers = 8;
        int partsPerWriter = 3;

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StoredObject>> futures = new ArrayList<>();

        // Every distinct full payload, keyed by object checksum -> expected raw bytes.
        Map<String, byte[]> payloadByChecksum = new ConcurrentHashMap<>();
        // Version number -> expected bytes, populated as writers publish (monotonic publication).
        Map<Long, byte[]> payloadByVersion = new ConcurrentHashMap<>();
        Set<String> uniquePartChecksums = ConcurrentHashMap.newKeySet();

        for (int w = 0; w < writers; w++) {
            final int writer = w;
            futures.add(pool.submit(() -> {
                start.await();
                String object = "shared.log";
                List<byte[]> parts = payload(writer, partsPerWriter);
                UploadInitResponse init = stack.service().initUpload(object);
                List<ManifestEntry> entries = new ArrayList<>();
                List<String> partChecksums = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    String cs = StorageTestSupport.sha256(stack.checksums(), parts.get(i));
                    // Interleave: each writer uploads its parts with tiny scheduling nudges.
                    stack.service().uploadPart(object, init.uploadId(), i + 1, cs,
                            new ByteArrayInputStream(parts.get(i)));
                    entries.add(new ManifestEntry(i + 1, cs));
                    partChecksums.add(cs);
                    uniquePartChecksums.add(cs);
                }
                String objectChecksum = stack.checksums().combinePartChecksums(partChecksums);
                byte[] fullBytes = StorageTestSupport.concat(parts);
                payloadByChecksum.put(objectChecksum, fullBytes);
                StoredObject published = stack.service().completeUpload(object, init.uploadId(),
                        new ManifestRequest(objectChecksum, entries));
                payloadByVersion.put(published.version(), fullBytes);
                System.out.printf("[concurrent] writer=%d published-or-reused version=%d checksum=%s%n",
                        writer, published.version(), published.checksum());
                return published;
            }));
        }

        // Concurrent reader: every read must be one of the known complete payloads.
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        List<Integer> observedVersionTrace = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger completions =
                new java.util.concurrent.atomic.AtomicInteger();
        Future<?> reader = pool.submit(() -> {
            try {
                long lastSeenVersion = 0;
                long deadline = System.currentTimeMillis() + 30_000;
                // Stop shortly after every writer published (or after 30 s at the latest).
                while (System.currentTimeMillis() < deadline
                        && (completions.get() < writers || lastSeenVersion < writers)) {
                    StoredObject head;
                    try {
                        head = stack.service().headObject("shared.log");
                    } catch (com.github.lisama569.objectstoragejava.exception.UploadNotFoundException noneYet) {
                        Thread.yield();
                        continue; // before first commit there is simply nothing to read
                    }
                    // Wait briefly for the publishing writer to register its expected bytes.
                    byte[] expected = payloadByVersion.get(head.version());
                    for (int i = 0; expected == null && i < 500; i++) {
                        Thread.sleep(2);
                        expected = payloadByVersion.get(head.version());
                    }
                    if (expected != null) {
                        // Read from the SAME metadata snapshot: head() and readObject() each resolve
                        // the current pointer independently, so between them a newer version could
                        // have been published. Here we stream exactly the blobs of `head`.
                        byte[] bytes;
                        try (var in = stack.service().readVersion("shared.log", head.version())) {
                            bytes = in.readAllBytes();
                        }
                        if (!java.util.Arrays.equals(bytes, expected)) {
                            String actualCs = StorageTestSupport.sha256(stack.checksums(), bytes);
                            String expectedCs = StorageTestSupport.sha256(stack.checksums(), expected);
                            throw new AssertionError("torn/corrupt read of version " + head.version()
                                    + " actualLen=" + bytes.length + " expectedLen=" + expected.length
                                    + " actualCs=" + actualCs + " expectedCs=" + expectedCs
                                    + " metaCs=" + head.checksum() + " metaParts=" + head.partChecksums());
                        }
                    }
                    if (head.version() < lastSeenVersion) {
                        throw new AssertionError("version rolled back from " + lastSeenVersion
                                + " to " + head.version());
                    }
                    lastSeenVersion = head.version();
                    observedVersionTrace.add((int) head.version());
                }
            } catch (Throwable t) {
                readerFailure.set(t);
            }
        });

        start.countDown();
        Set<Long> publishedVersions = new HashSet<>();
        for (Future<StoredObject> f : futures) {
            StoredObject r = f.get(60, TimeUnit.SECONDS);
            publishedVersions.add(r.version());
            completions.incrementAndGet();
        }
        reader.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(null, readerFailure.get(),
                readerFailure.get() == null ? "" : readerFailure.get().getMessage());

        // Deterministic outcome: 8 distinct payloads -> 8 versions, versions 1..8 all exist & readable.
        assertEquals(writers, publishedVersions.size(), "every distinct writer must publish one version");
        for (long v = 1; v <= writers; v++) {
            StoredObject meta = stack.service().getVersion("shared.log", v);
            byte[] bytes = stack.service().readVersion("shared.log", v).readAllBytes();
            byte[] expected = payloadByChecksum.get(meta.checksum());
            assertTrue(java.util.Arrays.equals(bytes, expected), "version " + v + " bytes must be exact");
        }

        // Content-addressing: exactly one blob per unique part (8 writers x 3 unique parts = 24).
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            long blobCount = blobs.count();
            assertEquals(uniquePartChecksums.size(), blobCount,
                    "no redundant blob storage across concurrent writers");
            System.out.printf("[concurrent] unique parts=%d blobs-on-disk=%d%n",
                    uniquePartChecksums.size(), blobCount);
        }

        // Deterministic final state: publish a known sentinel afterwards and assert exact final version.
        List<byte[]> sentinel = List.of(
                StorageTestSupport.utf8("sentinel-final-"),
                StorageTestSupport.utf8("state"));
        StoredObject finalVersion = StorageTestSupport.multipartUpload(
                stack.service(), stack.checksums(), "shared.log", sentinel);
        assertEquals(writers + 1L, finalVersion.version(), "sentinel must land at a deterministic version");
        byte[] finalBytes = stack.service().readObject("shared.log").readAllBytes();
        assertTrue(java.util.Arrays.equals(StorageTestSupport.concat(sentinel), finalBytes),
                "final bytes must be exactly the sentinel content");
        System.out.printf("[concurrent] FINAL decision: current=v%d checksum=%s (deterministic sentinel)%n",
                finalVersion.version(), finalVersion.checksum());
        System.out.printf("[concurrent] reader observed non-decreasing version trace sample=%s%n",
                observedVersionTrace.stream().limit(20).toList());
    }

    @Test
    void concurrentIdenticalWritersCollapseToSingleVersion() throws Exception {
        StorageTestSupport.Stack stack = StorageTestSupport.newStack(tempDir);
        int writers = 6;
        List<byte[]> sharedParts = List.of(
                StorageTestSupport.utf8("identical-content-block-A"),
                StorageTestSupport.utf8("identical-content-block-B"));
        String part1 = StorageTestSupport.sha256(stack.checksums(), sharedParts.get(0));
        String part2 = StorageTestSupport.sha256(stack.checksums(), sharedParts.get(1));
        String objectChecksum = stack.checksums().combinePartChecksums(List.of(part1, part2));

        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StoredObject>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            futures.add(pool.submit(() -> {
                start.await();
                UploadInitResponse init = stack.service().initUpload("same.bin");
                stack.service().uploadPart("same.bin", init.uploadId(), 1, part1,
                        new ByteArrayInputStream(sharedParts.get(0)));
                stack.service().uploadPart("same.bin", init.uploadId(), 2, part2,
                        new ByteArrayInputStream(sharedParts.get(1)));
                StoredObject result = stack.service().completeUpload("same.bin", init.uploadId(),
                        new ManifestRequest(objectChecksum,
                                List.of(new ManifestEntry(1, part1), new ManifestEntry(2, part2))));
                System.out.printf("[dedup-storm] writer result version=%d deduplicated=%s checksum=%s%n",
                        result.version(), result.deduplicated(), result.checksum());
                return result;
            }));
        }
        start.countDown();
        Set<Long> versions = new HashSet<>();
        for (Future<StoredObject> f : futures) {
            versions.add(f.get(60, TimeUnit.SECONDS).version());
        }
        pool.shutdownNow();

        assertEquals(Set.of(1L), versions, "identical concurrent content must resolve to exactly one version");
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            long blobCount = blobs.count();
            assertEquals(2, blobCount, "identical parts must share a single blob each");
            System.out.printf("[dedup-storm] decision: all %d writers reuse v1; blobs=%d checksum=%s%n",
                    writers, blobCount, objectChecksum);
        }
        byte[] read = stack.service().readObject("same.bin").readAllBytes();
        assertTrue(java.util.Arrays.equals(StorageTestSupport.concat(sharedParts), read));
    }
}
