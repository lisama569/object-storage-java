package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.dto.GcReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Safe, retryable reclamation of content-addressed blobs no longer referenced by any readable
 * object version.
 * <p>
 * Protocol (one pass, entirely under {@link ReadGate}'s exclusive lock, so no reader or writer
 * is touching blob files during any move):
 * <ol>
 *   <li><b>Live scan.</b> Snapshot the checksum set referenced by every {@code vN.json} on disk.
 *       Everything else in {@code blobs/} is a candidate.</li>
 *   <li><b>Phase A — quarantine (rename, never delete).</b> Candidates older than
 *       {@code minAgeMillis} are atomically moved to {@code quarantine/<sha>.dat.q<ts>-<n>}.
 *       A rename is crash-safe: nothing is ever unlinked while it might be referenced. A retry
 *       after a mid-pass crash simply finds fewer candidates.</li>
 *   <li><b>Phase B — reconciliation + sweep.</b> Quarantine entries are checked again against the
 *       live set: entries whose checksum is referenced again (a concurrent upload landed between
 *       phases, or a previous pass mis-quarantined after a crash) are RESTORED; entries still
 *       unreferenced and older than {@code minAgeMillis} are deleted. Every quarantine entry is
 *       therefore resolved exactly once and retries cannot double-delete or delete live data.</li>
 * </ol>
 */
@Service
public class StorageReclamationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReclamationService.class);

    /** Default grace period: blobs must have been unreferenced (by mtime) for this long. */
    public static final long DEFAULT_MIN_AGE_MILLIS = 10_000L;

    private final StorageLayout layout;
    private final ReadGate readGate;
    private final ObjectStorageService storageService;

    public StorageReclamationService(StorageLayout layout, ReadGate readGate,
                                     ObjectStorageService storageService) {
        this.layout = layout;
        this.readGate = readGate;
        this.storageService = storageService;
    }

    /** Run one complete garbage-collection pass with the default grace period. */
    public GcReport collectGarbage() {
        return collectGarbage(DEFAULT_MIN_AGE_MILLIS);
    }

    /** Run one pass with an explicit minimum un-referenced age (milliseconds); {@code 0} = immediate. */
    public GcReport collectGarbage(long minAgeMillis) {
        long started = System.currentTimeMillis();
        Counters counters = new Counters();
        readGate.runExclusive(() -> {
            try {
                Files.createDirectories(layout.quarantineDir());

                // Live-reference snapshot from all currently readable version metadata.
                Set<String> live = new HashSet<>(storageService.collectLiveBlobChecksumsUnlocked());
                counters.liveReferences = live.size();

                // Phase B first: resolve leftovers from any earlier (possibly crashed) pass before
                // creating new quarantined entries, so restoration always beats deletion.
                reconcileQuarantine(counters, live, minAgeMillis, started);

                // Phase A: move old unreferenced blobs into quarantine.
                long now = System.currentTimeMillis();
                List<Path> blobFiles = listRegularFiles(layout.blobsDir(), ".dat");
                counters.blobsExamined = blobFiles.size();
                long unique = 0;
                for (Path blob : blobFiles) {
                    String fileName = blob.getFileName().toString();
                    String checksum = fileName.substring(0, fileName.length() - ".dat".length());
                    if (live.contains(checksum)) {
                        unique++;
                        continue;
                    }
                    long age = now - Files.getLastModifiedTime(blob).toMillis();
                    if (age < minAgeMillis) {
                        continue; // too young: a just-published blob whose metadata we must not race
                    }
                    Path target = layout.quarantineFile(checksum, now, unique);
                    try {
                        try {
                            Files.move(blob, target, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException atomicUnsupported) {
                            Files.move(blob, target);
                        }
                        counters.quarantined++;
                        log.info("GC quarantine blob={} ageMs={}", checksum, age);
                    } catch (IOException e) {
                        // Lost a rename race (should not happen under the exclusive lock); the
                        // file is simply revisited on the next pass. No deletion is attempted.
                        log.warn("GC failed to quarantine {}: {}", checksum, e.getMessage());
                    }
                    unique++;
                }
                counters.candidatesIdentified = counters.quarantined;

                // Phase B for entries created in THIS pass: with minAgeMillis == 0 they can be
                // deleted immediately (the exclusive lock guarantees the live snapshot taken
                // above is still exact); with a positive grace period they stay parked and are
                // resolved by a future pass's reconciliation.
                sweepAgedQuarantine(counters, live, minAgeMillis, started);
            } catch (IOException e) {
                throw new com.github.lisama569.objectstoragejava.exception.StorageException(
                        "Garbage collection pass failed; no data was deleted", e);
            }
        });
        long duration = System.currentTimeMillis() - started;
        GcReport report = new GcReport(counters.blobsExamined, counters.liveReferences,
                counters.candidatesIdentified, counters.quarantined, counters.deleted,
                counters.restored, counters.staleQuarantineSwept, duration);
        log.info("GC pass complete: examined={} liveRefs={} quarantined={} restored={} deleted={} durationMs={}",
                report.blobsExamined(), report.liveReferences(), report.quarantined(),
                report.restored(), report.deleted(), duration);
        return report;
    }

    /** Resolve quarantine entries carried over from previous passes. */
    private void reconcileQuarantine(Counters counters, Set<String> live, long minAgeMillis, long passStart)
            throws IOException {
        for (Path q : listRegularFiles(layout.quarantineDir(), null)) {
            String fileName = q.getFileName().toString();
            final String checksum;
            try {
                checksum = StorageLayout.checksumFromQuarantineFileName(fileName);
            } catch (IllegalArgumentException notOurs) {
                counters.staleQuarantineSwept += deleteQuietly(q);
                continue;
            }
            if (live.contains(checksum) || Files.exists(layout.blob(checksum))) {
                // Referenced again (or an identical live blob exists): restore instead of delete.
                restore(q, checksum, counters);
            } else if (passStart - Files.getLastModifiedTime(q).toMillis() >= minAgeMillis) {
                counters.staleQuarantineSwept += deleteQuietly(q);
            }
        }
    }

    /** Delete this pass's quarantined entries once they are old enough and still unreferenced. */
    private void sweepAgedQuarantine(Counters counters, Set<String> live, long minAgeMillis, long passStart)
            throws IOException {
        if (minAgeMillis > 0) {
            return; // parked entries age out on a future pass
        }
        for (Path q : listRegularFiles(layout.quarantineDir(), null)) {
            final String checksum;
            try {
                checksum = StorageLayout.checksumFromQuarantineFileName(q.getFileName().toString());
            } catch (IllegalArgumentException notOurs) {
                continue;
            }
            if (live.contains(checksum) || Files.exists(layout.blob(checksum))) {
                restore(q, checksum, counters);
            } else if (passStart - Files.getLastModifiedTime(q).toMillis() >= minAgeMillis) {
                counters.deleted += deleteQuietly(q);
            }
        }
    }

    /** Move a quarantined blob back to its content-addressed name; tolerate an identical winner. */
    private void restore(Path quarantined, String checksum, Counters counters) {
        Path target = layout.blob(checksum);
        try {
            try {
                Files.move(quarantined, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(quarantined, target);
            }
            counters.restored++;
            log.info("GC restored blob={} (referenced again)", checksum);
        } catch (IOException e) {
            if (Files.exists(target)) {
                // An identical blob already exists (concurrent publication); drop the duplicate.
                counters.deleted += deleteQuietly(quarantined);
            } else {
                log.warn("GC failed to restore {}: {}", checksum, e.getMessage());
            }
        }
    }

    private static long deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p) ? 1 : 0;
        } catch (IOException e) {
            log.warn("GC failed to delete {}: {}", p, e.getMessage());
            return 0;
        }
    }

    private static List<Path> listRegularFiles(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> suffix == null || p.getFileName().toString().endsWith(suffix))
                    .toList();
        }
    }

    private static final class Counters {
        long blobsExamined;
        long liveReferences;
        long candidatesIdentified;
        long quarantined;
        long deleted;
        long restored;
        long staleQuarantineSwept;
    }
}
