package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.dto.GcReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Safe, retryable reclamation of content-addressed blobs no longer referenced by any readable
 * object version.
 * <p>
 * Two-phase protocol, designed so a failed/repeated pass can never delete live data:
 * <ol>
 *   <li><b>identify + quarantine</b>: under the {@link ReadGate} exclusive lock (no reader or
 *       publisher active), enumerate every still-readable version's blob references, then move
 *       each unreferenced blob older than {@code minAgeMillis} from {@code blobs/} into
 *       {@code quarantine/}. Moving (rename) never destroys bytes and the move is atomic.</li>
 *   <li><b>sweep</b>: on each pass, quarantine entries that survived a full subsequent pass
 *       (i.e. still older than {@code minAgeMillis} AND no longer present as live blobs) are
 *       deleted. If an upload re-materialised the content meanwhile, publication restores the
 *       quarantined file back into {@code blobs/} (see
 *       {@code ObjectStorageService#materialiseBlob}); it then disappears from quarantine and
 *       is never swept.</li>
 * </ol>
 * Safety properties:
 * <ul>
 *   <li>every step is idempotent — rerunning a pass moves/deletes only what is still eligible;</li>
 *   <li>only unreferenced blobs are eligible: references are recomputed on every pass;</li>
 *   <li>readers/publishers never block for long: only the move phase is exclusive; scanning and
 *       sweeping happen outside it, and reads/writes in progress hold leases that the exclusive
 *       section waits for (their blobs are therefore always seen as referenced/protected);</li>
 *   <li>unparseable version metadata aborts the pass rather than risking a wrong deletion.</li>
 * </ul>
 */
@Service
public class StorageReclamationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReclamationService.class);
    private static final Pattern BLOB_FILE = Pattern.compile("[0-9a-f]{64}\\.dat");
    private static final long DEFAULT_MIN_AGE_MILLIS = 60_000L;

    private final StorageLayout layout;
    private final ReadGate readGate;
    private final ObjectStorageService storageService;

    public StorageReclamationService(StorageLayout layout, ReadGate readGate,
                                     ObjectStorageService storageService) {
        this.layout = layout;
        this.readGate = readGate;
        this.storageService = storageService;
    }

    /** Run one complete garbage-collection pass with the default un-referenced age threshold. */
    public GcReport collectGarbage() {
        return collectGarbage(DEFAULT_MIN_AGE_MILLIS);
    }

    /**
     * Run one pass with a minimum un-referenced age for candidate blobs (milliseconds).
     * The small threshold is package-accessible for fast deterministic tests.
     */
    public GcReport collectGarbage(long minAgeMillis) {
        long started = System.currentTimeMillis();

        long blobsExamined = countBlobFiles();

        // Phase 1: identify live references and move newly-unreferenced blobs to quarantine.
        // The exclusive section is short (metadata scan + atomic renames) and excludes both
        // active readers and in-flight publishers.
        Set<String> liveAtQuarantine = new HashSet<>();
        Set<String> quarantinedThisPass = new HashSet<>();
        long[] quarantined = {0};
        long[] candidates = {0};
        readGate.runExclusive(() -> {
            Set<String> live = storageService.collectLiveBlobChecksums();
            liveAtQuarantine.addAll(live);
            long now = System.currentTimeMillis();
            try (Stream<Path> files = Files.list(layout.blobsDir())) {
                for (Path blob : files.filter(Files::isRegularFile)
                        .filter(p -> BLOB_FILE.matcher(p.getFileName().toString()).matches())
                        .toList()) {
                    String checksum = blobFileNameToChecksum(blob);
                    if (live.contains(checksum)) {
                        continue;
                    }
                    candidates[0]++; // unreferenced by any readable version
                    if (now - Files.getLastModifiedTime(blob).toMillis() < minAgeMillis) {
                        continue; // too young: an in-progress publication may still reference it
                    }
                    Files.createDirectories(layout.quarantineDir());
                    Path target = layout.quarantineBlob(checksum);
                    try {
                        moveAtomically(blob, target);
                        quarantined[0]++;
                        quarantinedThisPass.add(checksum);
                        log.info("GC quarantine unreferenced blob checksum={}", checksum);
                    } catch (IOException e) {
                        // Target may already exist from an earlier interrupted pass: the content
                        // is identical (same checksum name), so the remaining source is redundant.
                        if (Files.exists(target)) {
                            deleteQuietly(blob);
                            quarantined[0]++;
                            quarantinedThisPass.add(checksum);
                        } else {
                            throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                                    "Failed to quarantine blob " + blob, e);
                        }
                    }
                }
            } catch (IOException e) {
                throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                        "GC quarantine phase failed", e);
            }
        });

        // Phase 2: sweep quarantine entries that survived at least one FULL later pass.
        // A file quarantined in THIS pass is never swept here, even with minAge=0 (its preserved
        // mtime would otherwise look old): quarantine and delete always happen on separate passes.
        // Liveness is recomputed, which is what makes retries safe after any interruption.
        long[] deleted = {0};
        long[] restored = {0};
        Path quarantineDir = layout.quarantineDir();
        if (Files.isDirectory(quarantineDir)) {
            long now = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            Set<String>[] liveHolder = (Set<String>[]) new Set<?>[1];
            readGate.runExclusive(() -> liveHolder[0] = storageService.collectLiveBlobChecksums());
            Set<String> liveNow = liveHolder[0];
            try (Stream<Path> files = Files.list(quarantineDir)) {
                for (Path q : files.filter(Files::isRegularFile)
                        .filter(p -> BLOB_FILE.matcher(p.getFileName().toString()).matches())
                        .toList()) {
                    String checksum = blobFileNameToChecksum(q);
                    if (quarantinedThisPass.contains(checksum)) {
                        continue; // two-phase guarantee: survives at least until the next pass
                    }
                    if (Files.exists(layout.blob(checksum))) {
                        // Restored into blobs/ by a publication: drop the stale duplicate.
                        deleteQuietly(q);
                        restored[0]++;
                        continue;
                    }
                    if (liveNow.contains(checksum)) {
                        // Referenced by a readable version yet missing from blobs/: put it back
                        // rather than delete (defensive; versions are immutable so this can only
                        // happen after an external/crash anomaly).
                        try {
                            moveAtomically(q, layout.blob(checksum));
                            restored[0]++;
                        } catch (IOException e) {
                            throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                                    "Failed to restore still-referenced blob " + q, e);
                        }
                        continue;
                    }
                    if (now - Files.getLastModifiedTime(q).toMillis() < minAgeMillis) {
                        continue;
                    }
                    deleteQuietly(q);
                    deleted[0]++;
                    log.info("GC deleted stale quarantined blob checksum={}", checksum);
                }
            } catch (IOException e) {
                throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                        "GC sweep phase failed", e);
            }
        }

        long duration = System.currentTimeMillis() - started;
        GcReport report = new GcReport(blobsExamined, liveAtQuarantine.size(), candidates[0],
                quarantined[0], deleted[0], restored[0], deleted[0] + restored[0], duration);
        log.info("GC pass complete: examined={} liveRefs={} candidates={} quarantined={} deleted={}"
                        + " restored={} quarantineSwept={} in {}ms",
                blobsExamined, liveAtQuarantine.size(), candidates[0], quarantined[0], deleted[0],
                restored[0], deleted[0] + restored[0], duration);
        return report;
    }

    private long countBlobFiles() {
        if (!Files.isDirectory(layout.blobsDir())) {
            return 0;
        }
        try (Stream<Path> files = Files.list(layout.blobsDir())) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> BLOB_FILE.matcher(p.getFileName().toString()).matches())
                    .count();
        } catch (IOException e) {
            throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                    "Failed to enumerate blobs", e);
        }
    }

    private static String blobFileNameToChecksum(Path blob) {
        String name = blob.getFileName().toString();
        return name.substring(0, name.length() - ".dat".length());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort — a leftover is re-evaluated on the next pass
        }
    }
}

