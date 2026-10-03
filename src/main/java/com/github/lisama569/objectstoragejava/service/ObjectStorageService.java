package com.github.lisama569.objectstoragejava.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.dto.ChunkUploadResponse;
import com.github.lisama569.objectstoragejava.dto.ManifestEntry;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.PruneResult;
import com.github.lisama569.objectstoragejava.dto.RangeReadResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
import com.github.lisama569.objectstoragejava.dto.VersionSummary;
import com.github.lisama569.objectstoragejava.exception.ChecksumMismatchException;
import com.github.lisama569.objectstoragejava.exception.InvalidManifestException;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.exception.UploadNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

/**
 * Core local object-storage operations.
 * <p>
 * Concurrency contract: all mutating operations for one object take the per-object
 * {@link LockManager} lock; publication of a new version is a single atomic rename of the
 * {@code current} pointer file, so readers only ever observe a fully committed version and
 * version numbers never roll back.
 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);
    private static final HexFormat HEX = HexFormat.of();
    private static final int BUFFER_SIZE = 8192;

    private final StorageLayout layout;
    private final ChecksumService checksumService;
    private final LockManager lockManager;
    private final ReadGate readGate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ObjectStorageService(StorageLayout layout, ChecksumService checksumService,
                                LockManager lockManager, ReadGate readGate) {
        this.layout = layout;
        this.checksumService = checksumService;
        this.lockManager = lockManager;
        this.readGate = readGate;
    }

    // ---------------------------------------------------------------- multipart sessions

    public UploadInitResponse initUpload(String objectName) {
        layout.requireSafeName(objectName);
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        Path dir = layout.uploadDir(objectName, uploadId);
        try {
            Files.createDirectories(dir.resolve("parts"));
        } catch (IOException e) {
            throw new StorageException("Failed to create upload session for " + objectName, e);
        }
        log.info("Init upload object={} uploadId={}", objectName, uploadId);
        return new UploadInitResponse(objectName, uploadId);
    }

    public ChunkUploadResponse uploadPart(String objectName, String uploadId, int partNumber,
                                          String expectedChecksum, InputStream content) {
        layout.requireSafeName(objectName);
        if (partNumber < 1 || partNumber > 100_000) {
            throw new InvalidManifestException("partNumber must be between 1 and 100000, got " + partNumber);
        }
        Path uploadDir = layout.uploadDir(objectName, uploadId);
        if (!Files.isDirectory(uploadDir)) {
            throw new UploadNotFoundException("No upload " + uploadId + " for object " + objectName);
        }

        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            // Re-upload of the same part is idempotent and enables resumption.
            Path partFile = layout.partFile(objectName, uploadId, partNumber);
            Path checksumFile = partFile.resolveSibling("part-" + partNumber + ".sha256");
            if (Files.exists(partFile) && Files.exists(checksumFile)) {
                try {
                    String existing = Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
                    if (expectedChecksum == null || expectedChecksum.equalsIgnoreCase(existing)) {
                        long size = Files.size(partFile);
                        log.info("Part already present object={} part={} checksum={} (resume/idempotent)",
                                objectName, partNumber, existing);
                        return new ChunkUploadResponse(objectName, uploadId, partNumber, existing, true, size);
                    }
                } catch (IOException e) {
                    throw new StorageException("Failed to read existing part " + partNumber, e);
                }
            }

            // Stream to a temporary file while hashing; commit only after checksum verification.
            Path partsDir = partFile.getParent();
            Path tmp;
            try {
                Files.createDirectories(partsDir);
                tmp = Files.createTempFile(partsDir, "part-" + partNumber + "-", ".tmp");
            } catch (IOException e) {
                throw new StorageException("Failed to prepare part " + partNumber, e);
            }
            String actualChecksum;
            long size;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[BUFFER_SIZE];
                long written = 0;
                try (InputStream in = new BufferedInputStream(content);
                     java.io.OutputStream out = Files.newOutputStream(tmp)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        written += read;
                    }
                }
                actualChecksum = HEX.formatHex(digest.digest());
                size = written;
            } catch (IOException | NoSuchAlgorithmException e) {
                deleteQuietly(tmp);
                throw new StorageException("Failed to store part " + partNumber, e);
            }

            if (expectedChecksum != null && !expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                deleteQuietly(tmp);
                log.warn("Checksum mismatch object={} part={} expected={} actual={}",
                        objectName, partNumber, expectedChecksum, actualChecksum);
                throw new ChecksumMismatchException("Part " + partNumber + " checksum mismatch: expected "
                        + expectedChecksum + " but computed " + actualChecksum);
            }

            try {
                atomicMove(tmp, partFile);
                Files.writeString(checksumFile, actualChecksum, StandardCharsets.UTF_8);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw new StorageException("Failed to commit part " + partNumber, e);
            }
            log.info("Stored part object={} part={} size={} checksum={}",
                    objectName, partNumber, size, actualChecksum);
            return new ChunkUploadResponse(objectName, uploadId, partNumber, actualChecksum, false, size);
        } finally {
            lock.unlock();
        }
    }

    public UploadStatusResponse uploadStatus(String objectName, String uploadId) {
        layout.requireSafeName(objectName);
        Path uploadDir = layout.uploadDir(objectName, uploadId);
        if (!Files.isDirectory(uploadDir)) {
            throw new UploadNotFoundException("No upload " + uploadId + " for object " + objectName);
        }
        List<UploadStatusResponse.PartStatus> parts = new ArrayList<>();
        Path partsDir = uploadDir.resolve("parts");
        if (Files.isDirectory(partsDir)) {
            try (Stream<Path> files = Files.list(partsDir)) {
                files.filter(p -> p.getFileName().toString().matches("part-\\d+")).forEach(p -> {
                    try {
                        int n = Integer.parseInt(p.getFileName().toString().substring("part-".length()));
                        Path sidecar = p.resolveSibling("part-" + n + ".sha256");
                        String checksum = Files.exists(sidecar)
                                ? Files.readString(sidecar, StandardCharsets.UTF_8).trim() : null;
                        parts.add(new UploadStatusResponse.PartStatus(n, checksum, Files.size(p)));
                    } catch (IOException e) {
                        throw new StorageException("Failed to read part status", e);
                    }
                });
            } catch (IOException e) {
                throw new StorageException("Failed to list upload parts", e);
            }
        }
        parts.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        return new UploadStatusResponse(objectName, uploadId, parts);
    }

    // -------------------------------------------------------------- completion / abort

    public StoredObject completeUpload(String objectName, String uploadId, ManifestRequest manifest) {
        layout.requireSafeName(objectName);
        if (manifest == null || manifest.parts() == null || manifest.parts().isEmpty()) {
            throw new InvalidManifestException("Manifest is missing or contains no parts");
        }
        if (manifest.checksum() == null || manifest.checksum().isBlank()) {
            throw new InvalidManifestException("Manifest is missing the expected object checksum");
        }

        Path uploadDir = layout.uploadDir(objectName, uploadId);
        if (!Files.isDirectory(uploadDir)) {
            throw new UploadNotFoundException("No upload " + uploadId + " for object " + objectName);
        }

        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            // Validate ordering and absence of duplicate part numbers.
            List<ManifestEntry> entries = new ArrayList<>(manifest.parts());
            for (int i = 0; i < entries.size(); i++) {
                ManifestEntry entry = entries.get(i);
                if (entry.partNumber() != i + 1) {
                    throw new InvalidManifestException(
                            "Manifest parts must be numbered 1..N in order; expected part " + (i + 1)
                                    + " but got " + entry.partNumber());
                }
                if (entry.checksum() == null || entry.checksum().isBlank()) {
                    throw new InvalidManifestException("Part " + entry.partNumber() + " is missing its checksum");
                }
            }

            // Every referenced part must exist on disk AND match the checksum claimed in the manifest.
            List<String> partChecksums = new ArrayList<>();
            long totalSize = 0;
            for (ManifestEntry entry : entries) {
                Path partFile = layout.partFile(objectName, uploadId, entry.partNumber());
                Path checksumFile = partFile.resolveSibling("part-" + entry.partNumber() + ".sha256");
                if (!Files.exists(partFile) || !Files.exists(checksumFile)) {
                    throw new InvalidManifestException("Referenced part " + entry.partNumber()
                            + " has not been uploaded (resume the upload before completing)");
                }
                String storedChecksum = Files.readString(checksumFile, StandardCharsets.UTF_8).trim();
                if (!storedChecksum.equalsIgnoreCase(entry.checksum())) {
                    throw new ChecksumMismatchException("Manifest checksum for part " + entry.partNumber()
                            + " (" + entry.checksum() + ") does not match stored part (" + storedChecksum + ")");
                }
                totalSize += Files.size(partFile);
                partChecksums.add(storedChecksum);
            }

            // Object-level checksum: chained digest over the ordered part checksums.
            String computedChecksum = checksumService.combinePartChecksums(partChecksums);
            if (!computedChecksum.equalsIgnoreCase(manifest.checksum())) {
                log.warn("Object checksum mismatch object={} expected={} computed={}",
                        objectName, manifest.checksum(), computedChecksum);
                throw new ChecksumMismatchException("Object checksum mismatch: manifest claims "
                        + manifest.checksum() + " but assembled object has " + computedChecksum);
            }

            StoredObject result;
            StoredObject current = currentVersionOrNull(objectName);
            if (current != null && current.checksum().equalsIgnoreCase(computedChecksum)) {
                // Identical content re-upload: no new version, no extra storage.
                result = new StoredObject(objectName, current.version(), current.checksum(),
                        current.size(), current.partChecksums(), true);
                log.info("Duplicate upload object={} checksum={} -> reuse version {} (no new storage)",
                        objectName, computedChecksum, current.version());
            } else {
                result = publishVersion(objectName, uploadId, partChecksums, computedChecksum, totalSize);
            }

            // Staging is only removed after publication; a failure before here leaves it resumable.
            deleteRecursively(uploadDir);
            return result;
        } catch (IOException e) {
            throw new StorageException("Failed to complete upload " + uploadId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Materialise blobs for each part (content-addressed, write-once), then write the new
     * version metadata and flip the {@code current} pointer atomically.
     * <p>
     * Part bytes are always read from THIS upload session's staging directory (by part number),
     * never located by scanning other sessions: a different session may be completing or aborting
     * concurrently.
     */
    private StoredObject publishVersion(String objectName, String uploadId,
                                        List<String> partChecksums, String objectChecksum,
                                        long totalSize) throws IOException {
        // Ensure every referenced content blob exists exactly once (deduplicated).
        int reusedBlobs = 0;
        for (int i = 0; i < partChecksums.size(); i++) {
            int partNumber = i + 1;
            Path partFile = layout.partFile(objectName, uploadId, partNumber);
            if (materialiseBlob(partFile, partChecksums.get(i))) {
                reusedBlobs++;
            }
        }

        long nextVersion = nextVersionNumber(objectName);
        boolean allBlobsReused = reusedBlobs == partChecksums.size();
        StoredObject stored = new StoredObject(objectName, nextVersion, objectChecksum, totalSize,
                List.copyOf(partChecksums), allBlobsReused);

        // Write version metadata to a temp file, then atomically rename into versions/.
        Path versionsDir = layout.versionsDir(objectName);
        Files.createDirectories(versionsDir);
        Path vFile = layout.versionFile(objectName, nextVersion);
        Path tmpMeta = Files.createTempFile(versionsDir, "v" + nextVersion + "-", ".json.tmp");
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(stored);
            Files.write(tmpMeta, json);
            atomicMove(tmpMeta, vFile);
        } finally {
            deleteQuietly(tmpMeta);
        }

        // Flip the pointer atomically. Old version files and blobs are never touched.
        Path pointer = layout.currentPointer(objectName);
        Files.createDirectories(pointer.getParent());
        Path tmpPointer = Files.createTempFile(pointer.getParent(), "current-", ".tmp");
        try {
            Files.writeString(tmpPointer, Long.toString(nextVersion), StandardCharsets.UTF_8);
            atomicMove(tmpPointer, pointer);
        } finally {
            deleteQuietly(tmpPointer);
        }
        log.info("Published version object={} version={} checksum={} size={} reusedBlobs={}",
                objectName, nextVersion, objectChecksum, totalSize, reusedBlobs);
        return stored;
    }

    /**
     * Ensure the content-addressed blob for one staged part exists. The part bytes are verified
     * once more against the checksum before the blob file is atomically committed.
     *
     * @return true when an identical blob already existed (storage deduplicated)
     */
    private boolean materialiseBlob(Path partFile, String partChecksum) throws IOException {
        Path blob = layout.blob(partChecksum);
        if (Files.exists(blob)) {
            return true;
        }
        Path blobTmp = Files.createTempFile(layout.blobsDir(), partChecksum + "-", ".tmp");
        try {
            String actual;
            try (InputStream in = Files.newInputStream(partFile)) {
                actual = checksumService.sha256(in);
            }
            if (!actual.equalsIgnoreCase(partChecksum)) {
                throw new ChecksumMismatchException("Part content " + partFile
                        + " failed final verification: computed " + actual);
            }
            Files.copy(partFile, blobTmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                atomicMove(blobTmp, blob);
            } catch (IOException e) {
                // A concurrent writer (different JVM/process) may have won the race; that's fine.
                if (!Files.exists(blob)) {
                    throw e;
                }
            }
            return false;
        } finally {
            deleteQuietly(blobTmp);
        }
    }

    private long nextVersionNumber(String objectName) {
        Path versionsDir = layout.versionsDir(objectName);
        long max = 0;
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> files = Files.list(versionsDir)) {
                for (Path p : files.toList()) {
                    String name = p.getFileName().toString();
                    if (name.matches("v\\d+\\.json")) {
                        long n = Long.parseLong(name.substring(1, name.length() - ".json".length()));
                        max = Math.max(max, n);
                    }
                }
            } catch (IOException e) {
                throw new StorageException("Failed to determine next version for " + objectName, e);
            }
        }
        return max + 1;
    }

    public void abortUpload(String objectName, String uploadId) {
        layout.requireSafeName(objectName);
        Path uploadDir = layout.uploadDir(objectName, uploadId);
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            if (!Files.isDirectory(uploadDir)) {
                throw new UploadNotFoundException("No upload " + uploadId + " for object " + objectName);
            }
            deleteRecursively(uploadDir);
            log.info("Aborted upload object={} uploadId={} (committed versions untouched)", objectName, uploadId);
        } finally {
            lock.unlock();
        }
    }

    // --------------------------------------------------------- new read / lifecycle APIs

    /** Read a byte range of the current version from a single consistent metadata snapshot. */
    public RangeReadResult readObjectRange(String objectName, String rangeHeader) {
        return null;
    }

    /** Read a byte range of a specific historical version. */
    public RangeReadResult readVersionRange(String objectName, long version, String rangeHeader) {
        return null;
    }

    /** Open the verified byte stream of one already-resolved immutable version snapshot. */
    public InputStream openVersion(StoredObject version) {
        return openVersionStream(version, null);
    }

    /** Open a verified stream for the given version, optionally limited to an inclusive range. */
    public InputStream openVersionStream(StoredObject version, ByteRange range) {
        return null;
    }

    /** List all committed versions of an object (version numbers ascending). */
    public java.util.List<VersionSummary> listVersions(String objectName) {
        return java.util.List.of();
    }

    /** Remove historical versions beyond the given retention count; current is never removed. */
    public PruneResult pruneVersions(String objectName, int retain) {
        return null;
    }

    /** Delete the whole object (all versions + pointer); its blobs become GC candidates. */
    public void deleteObject(String objectName) {
    }

    /** Snapshot of every part checksum referenced by any still-readable version of any object. */
    public java.util.Set<String> collectLiveBlobChecksums() {
        return java.util.Set.of();
    }

    // ------------------------------------------------------------------ reads (legacy)

    public StoredObject headObject(String objectName) {        layout.requireSafeName(objectName);
        StoredObject current = currentVersionOrNull(objectName);
        if (current == null) {
            throw new UploadNotFoundException("No committed version of object " + objectName);
        }
        return current;
    }

    public StoredObject getVersion(String objectName, long version) {
        layout.requireSafeName(objectName);
        Path vFile = layout.versionFile(objectName, version);
        if (!Files.exists(vFile)) {
            throw new UploadNotFoundException("Version " + version + " of object " + objectName + " does not exist");
        }
        return readVersionFile(vFile);
    }

    public InputStream readObject(String objectName) {
        return openVersion(headObject(objectName));
    }

    public InputStream readVersion(String objectName, long version) {
        return openVersion(getVersion(objectName, version));
    }

    private StoredObject currentVersionOrNull(String objectName) {
        Path pointer = layout.currentPointer(objectName);
        if (!Files.exists(pointer)) {
            return null;
        }
        try {
            String text = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            long version = Long.parseLong(text);
            Path vFile = layout.versionFile(objectName, version);
            if (!Files.exists(vFile)) {
                throw new StorageException("Current pointer for " + objectName + " references missing version "
                        + version + " — storage is corrupt");
            }
            return readVersionFile(vFile);
        } catch (IOException e) {
            throw new StorageException("Failed to read current version of " + objectName, e);
        }
    }

    private StoredObject readVersionFile(Path vFile) {
        try {
            return objectMapper.readValue(Files.readAllBytes(vFile), StoredObject.class);
        } catch (IOException e) {
            throw new StorageException("Failed to parse version metadata " + vFile, e);
        }
    }

    // ------------------------------------------------------------------ utilities

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new StorageException("Failed to delete " + dir, e);
        }
    }
}
