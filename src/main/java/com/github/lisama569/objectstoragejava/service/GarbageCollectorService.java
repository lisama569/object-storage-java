package com.github.lisama569.objectstoragejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.dto.GarbageCollectionReport;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reclaims content-addressed blobs that are no longer referenced by any READABLE (CURRENT or LIVE)
 * version of any object.
 * <p>
 * Safety contract:
 * <ul>
 *     <li><b>referenced blobs are never deleted</b> — the live set is computed first from every
 *     {@code vN.json} without a {@code .retired} marker, across ALL objects (blobs are shared);
 *     </li>
 *     <li><b>in-use blobs are skipped</b> — any blob held by a read lease or an in-flight version
 *     publication (see {@link BlobLeaseManager});</li>
 *     <li><b>young blobs are skipped</b> — blobs whose last-modified time is younger than the grace
 *     age are left alone, covering a crash in the materialise→publish window and clock margins;</li>
 *     <li><b>non-blocking</b> — the pass takes no object locks, so reads and writes proceed
 *     concurrently;</li>
 *     <li><b>retry-safe</b> — only physical deletion mutates state; a failed/missing blob is simply
 *     absent on the next pass, so retrying {@code collect()} cannot delete twice or wrongly;</li>
 *     <li>on POSIX, blobs already opened by a reader stay readable even if deleted; the lease makes
 *     that situation unreachable within this JVM.</li>
 * </ul>
 * What is reclaimable after a version is replaced/retired: blobs used only by versions that are
 * neither the current version nor another live, non-retired version.
 */
@Service
public class GarbageCollectorService {

    private static final Logger log = LoggerFactory.getLogger(GarbageCollectorService.class);
    private static final String BLOB_SUFFIX = ".dat";
    private static final String VERSION_SUFFIX = ".json";
    private static final String VERSION_PREFIX = "v";

    private final StorageLayout layout;
    private final BlobLeaseManager leaseManager;
    private final Duration minBlobAge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GarbageCollectorService(StorageLayout layout, BlobLeaseManager leaseManager,
                                   @Value("${objectstorage.gc.min-age:1m}") String minAge) {
        this.layout = layout;
        this.leaseManager = leaseManager;
        this.minBlobAge = DurationStyle.detectAndParse(minAge);
    }

    /** Run one reclaim pass over the blob directory. */
    public GarbageCollectionReport collect() {
        return collect(Instant.now());
    }

    /** Package-visible variant with an injectable clock for deterministic tests. */
    GarbageCollectionReport collect(Instant now) {
        Set<String> referenced = collectReferencedBlobs();

        long total = 0;
        long deleted = 0;
        long skippedInUse = 0;
        long skippedYoung = 0;
        long failures = 0;
        long reclaimedBytes = 0;
        List<String> deletedChecksums = new ArrayList<>();

        Path blobsDir = layout.blobsDir();
        if (!Files.isDirectory(blobsDir)) {
            return new GarbageCollectionReport(0, referenced.size(), 0, 0, 0, 0, 0, List.of());
        }

        Instant youngestAllowed = now.minus(minBlobAge);
        try (Stream<Path> blobs = Files.list(blobsDir)) {
            List<Path> snapshot = blobs
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(BLOB_SUFFIX))
                    .toList();
            for (Path blob : snapshot) {
                total++;
                String checksum = checksumOf(blob);

                if (referenced.contains(checksum)) {
                    continue; // reachable from some readable version — never touch
                }
                if (leaseManager.isProtected(checksum)) {
                    skippedInUse++;
                    continue;
                }
                Instant modified;
                long size;
                try {
                    modified = Files.getLastModifiedTime(blob).toInstant();
                    size = Files.size(blob);
                } catch (IOException e) {
                    failures++;
                    log.warn("[gc] cannot stat candidate blob {} — left for a retry: {}", checksum, e.getMessage());
                    continue;
                }
                if (modified.isAfter(youngestAllowed)) {
                    skippedYoung++;
                    continue;
                }
                try {
                    Files.deleteIfExists(blob);
                    deleted++;
                    reclaimedBytes += size;
                    deletedChecksums.add(checksum);
                    log.info("[gc] reclaimed unreferenced blob checksum={} bytes={}", checksum, size);
                } catch (IOException e) {
                    // On Windows an open file cannot be deleted; retry on the next pass.
                    failures++;
                    log.warn("[gc] failed to delete blob {} — will retry on next pass: {}",
                            checksum, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new StorageException("Garbage collection pass failed while listing " + blobsDir, e);
        }

        GarbageCollectionReport report = new GarbageCollectionReport(
                total, referenced.size(), deleted, skippedInUse, skippedYoung, failures,
                reclaimedBytes, List.copyOf(deletedChecksums));
        log.info("[gc] pass complete totalBlobs={} referenced={} deleted={} skippedInUse={} "
                        + "skippedYoung={} failures={} reclaimedBytes={}",
                total, referenced.size(), deleted, skippedInUse, skippedYoung, failures, reclaimedBytes);
        return report;
    }

    /**
     * Build the set of blob checksums referenced by every readable (non-retired) version of every
     * object. A retired {@code vN.json} (has a {@code .retired} marker) is excluded.
     */
    private Set<String> collectReferencedBlobs() {
        Set<String> referenced = new HashSet<>();
        Path objectsDir = layout.objectDirRoot();
        if (!Files.isDirectory(objectsDir)) {
            return referenced;
        }
        try (Stream<Path> objects = Files.list(objectsDir)) {
            for (Path objectDir : objects.filter(Files::isDirectory).toList()) {
                Path versionsDir = objectDir.resolve("versions");
                if (!Files.isDirectory(versionsDir)) {
                    continue;
                }
                String objectName = objectDir.getFileName().toString();
                try (Stream<Path> versionFiles = Files.list(versionsDir)) {
                    for (Path vFile : versionFiles
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString();
                                return n.startsWith(VERSION_PREFIX) && n.endsWith(VERSION_SUFFIX);
                            })
                            .toList()) {
                        String n = vFile.getFileName().toString();
                        long v = Long.parseLong(n.substring(VERSION_PREFIX.length(),
                                n.length() - VERSION_SUFFIX.length()));
                        if (Files.exists(versionsDir.resolve("v" + v + ".retired"))) {
                            continue;
                        }
                        StoredObject meta;
                        try {
                            meta = objectMapper.readValue(Files.readAllBytes(vFile), StoredObject.class);
                        } catch (IOException e) {
                            // Unparseable metadata: fail safe by refusing to touch anything it might
                            // reference; surface the corruption loudly rather than deleting.
                            throw new StorageException("Cannot parse version metadata " + vFile
                                    + " — aborting reference scan for safety", e);
                        }
                        if (meta.partChecksums() != null) {
                            referenced.addAll(meta.partChecksums());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException("Failed to scan referenced blobs under " + objectsDir, e);
        }
        return referenced;
    }

    private static String checksumOf(Path blob) {
        String name = blob.getFileName().toString();
        return name.substring(0, name.length() - BLOB_SUFFIX.length());
    }
}
