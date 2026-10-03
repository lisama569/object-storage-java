package com.github.lisama569.objectstoragejava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lisama569.objectstoragejava.dto.ChunkUploadResponse;
import com.github.lisama569.objectstoragejava.dto.ManifestEntry;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
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
    private final BlobLeaseManager leaseManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ObjectStorageService(StorageLayout layout, ChecksumService checksumService,
                                LockManager lockManager, BlobLeaseManager leaseManager) {
        this.layout = layout;
        this.checksumService = checksumService;
        this.lockManager = lockManager;
        this.leaseManager = leaseManager;
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

            // Content identity: SHA-256 over the raw concatenated bytes. Unlike the chained
            // manifest checksum above, this is independent of how the upload was split into parts.
            String contentChecksum = sha256AssembledBytes(objectName, uploadId, entries.size());

            StoredObject result;
            // Duplicate decision for THIS object: identical raw bytes already present on one of its
            // readable versions -> reuse that version (no new version, no new storage), regardless
            // of the part layout used this time. Genuinely changed bytes -> publish a new version.
            // Cross-object storage sharing is provided independently by content-addressed blobs
            // (materialiseBlob reuses any identical blob even when a new version is published here).
            StoredObject duplicate = findVersionByContent(objectName, contentChecksum);
            if (duplicate != null) {
                result = new StoredObject(objectName, duplicate.version(), duplicate.checksum(),
                        duplicate.contentChecksum(), duplicate.size(), duplicate.partChecksums(), true);
                log.info("[dedup] decision object={} contentChecksum={} -> reuse existing version {} "
                                + "(possibly different part layout; no new version, no new storage)",
                        objectName, contentChecksum, duplicate.version());
            } else {
                result = publishVersion(objectName, uploadId, partChecksums, computedChecksum,
                        contentChecksum, totalSize);
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
                                        String contentChecksum, long totalSize) throws IOException {
        // Protect every referenced blob for the whole materialise -> write metadata -> flip
        // pointer window, so a concurrent GC pass can never reclaim a not-yet-referenced blob.
        leaseManager.markPending(partChecksums);
        try {
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
            StoredObject stored = new StoredObject(objectName, nextVersion, objectChecksum, contentChecksum,
                    totalSize, List.copyOf(partChecksums), false);

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
            log.info("[version] decision object={} content differs -> publish version={} manifestChecksum={} "
                            + "contentChecksum={} size={} reusedBlobs={}",
                    objectName, nextVersion, objectChecksum, contentChecksum, totalSize, reusedBlobs);
            return stored;
        } finally {
            leaseManager.clearPending(partChecksums);
        }
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

    // ------------------------------------------------------------------ reads

    public StoredObject headObject(String objectName) {
        layout.requireSafeName(objectName);
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
        if (Files.exists(layout.retiredMarker(objectName, version))) {
            throw new UploadNotFoundException("Version " + version + " of object " + objectName
                    + " has been retired and is no longer readable");
        }
        return readVersionFile(vFile);
    }

    public InputStream readObject(String objectName) {
        return openCurrent(objectName, null).stream();
    }

    public InputStream readVersion(String objectName, long version) {
        return openVersionSnapshot(objectName, version, null).stream();
    }

    // ------------------------------------------------- snapshot reads / ranges / retirement

    /**
     * Open the current version against a SINGLE metadata snapshot and return its metadata together
     * with the stream. Metadata and bytes therefore always describe the same complete version, even
     * if another writer publishes concurrently: the snapshot is resolved once here and the returned
     * stream reads exactly that snapshot's blobs.
     */
    public com.github.lisama569.objectstoragejava.dto.OpenedObject openCurrent(
            String objectName, String rangeHeader) {
        layout.requireSafeName(objectName);
        return openSnapshot(headObject(objectName), rangeHeader);
    }

    /** Open one historical (or current, by number) version against a single metadata snapshot. */
    public com.github.lisama569.objectstoragejava.dto.OpenedObject openVersionSnapshot(
            String objectName, long version, String rangeHeader) {
        layout.requireSafeName(objectName);
        return openSnapshot(getVersion(objectName, version), rangeHeader);
    }

    private com.github.lisama569.objectstoragejava.dto.OpenedObject openSnapshot(
            StoredObject meta, String rangeHeader) {
        com.github.lisama569.objectstoragejava.dto.ByteRange range =
                com.github.lisama569.objectstoragejava.dto.ByteRange.parse(rangeHeader, meta.size());

        // Determine which part blobs the (optional) range touches. The lease protects exactly
        // those blobs from GC until the stream is closed; the blob boundaries come from this one
        // immutable metadata snapshot, so metadata and bytes can never disagree.
        List<String> referenced = meta.partChecksums();
        List<String> touched;
        if (range == null) {
            touched = referenced;
        } else {
            touched = touchedChecksums(meta, range.start(), range.end());
        }
        BlobLeaseManager.Lease lease = leaseManager.acquire(touched);
        boolean handedOff = false;
        try {
            InputStream stream = range == null
                    ? openVerifiedSequence(meta, 0, meta.size() - 1, lease)
                    : openVerifiedSequence(meta, range.start(), range.end(), lease);
            com.github.lisama569.objectstoragejava.dto.OpenedObject opened =
                    new com.github.lisama569.objectstoragejava.dto.OpenedObject(meta, stream, range);
            handedOff = true;
            return opened;
        } finally {
            // openVerifiedSequence wraps the lease into the stream; if construction failed before
            // the hand-off, release here to avoid leaking the protection counts.
            if (!handedOff) {
                lease.close();
            }
        }
    }

    /**
     * Return the checksums of the blobs overlapping the inclusive [from, to] byte window.
     * Blob boundaries are reconstructed by summing each blob's on-disk size; the version metadata
     * guarantees those sizes add up to {@code size}.
     */
    private List<String> touchedChecksums(StoredObject meta, long from, long to) {
        long offset = 0;
        List<String> result = new ArrayList<>();
        for (String checksum : meta.partChecksums()) {
            long blobSize;
            try {
                blobSize = Files.size(layout.blob(checksum));
            } catch (IOException e) {
                throw new StorageException("Blob " + checksum + " referenced by version "
                        + meta.version() + " is unreadable", e);
            }
            long blobEnd = offset + blobSize - 1;
            if (blobEnd >= from && offset <= to) {
                result.add(checksum);
            }
            offset += blobSize;
        }
        if (result.isEmpty()) {
            throw new StorageException("No blobs back range [" + from + "," + to + "] for version "
                    + meta.version() + " — storage is corrupt");
        }
        return result;
    }

    /**
     * Open the byte window [from, to] of a version, verifying the SHA-256 of EVERY blob the window
     * touches (including blobs a cross-chunk range starts/ends in the middle of). Bytes outside the
     * window are skipped but still hashed, so part-integrity checking is never weakened by ranging.
     * The returned stream closes the lease when closed.
     */
    private InputStream openVerifiedSequence(StoredObject meta, long from, long to,
                                             BlobLeaseManager.Lease lease) {
        java.util.List<InputStream> layers = new ArrayList<>();
        long offset = 0;
        try {
            for (String checksum : meta.partChecksums()) {
                Path blob = layout.blob(checksum);
                long blobSize = Files.size(blob);
                long blobEnd = offset + blobSize - 1;
                if (blobEnd >= from && offset <= to) {
                    long skipPrefix = Math.max(0, from - offset);
                    long takeUntil = Math.min(blobEnd, to);
                    long emitLength = takeUntil - offset - skipPrefix + 1;
                    InputStream verified = new VerifiedBlobInputStream(
                            Files.newInputStream(blob), blobSize, checksum,
                            offset, skipPrefix, emitLength, meta.version());
                    layers.add(verified);
                }
                offset += blobSize;
            }
        } catch (IOException e) {
            layers.forEach(in -> {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best effort
                }
            });
            lease.close();
            throw new StorageException("Failed to open version " + meta.version() + " for reading", e);
        }
        InputStream sequence = layers.isEmpty()
                ? InputStream.nullInputStream()
                : new java.io.SequenceInputStream(java.util.Collections.enumeration(layers));
        return new InputStream() {
            @Override
            public int read() throws IOException {
                return sequence.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return sequence.read(b, off, len);
            }

            @Override
            public void close() throws IOException {
                try {
                    sequence.close();
                } finally {
                    lease.close();
                }
            }
        };
    }

    /** List all versions of an object with their accessibility state (CURRENT/LIVE/RETIRED). */
    public java.util.List<com.github.lisama569.objectstoragejava.dto.VersionInfo> listVersions(
            String objectName) {
        layout.requireSafeName(objectName);
        Path objectDir = layout.objectDir(objectName);
        if (!Files.isDirectory(objectDir)) {
            throw new UploadNotFoundException("No committed version of object " + objectName);
        }
        long currentVersion = -1;
        Path pointer = layout.currentPointer(objectName);
        if (Files.exists(pointer)) {
            try {
                currentVersion = Long.parseLong(Files.readString(pointer, StandardCharsets.UTF_8).trim());
            } catch (IOException | NumberFormatException e) {
                throw new StorageException("Failed to read current pointer for " + objectName, e);
            }
        }
        List<com.github.lisama569.objectstoragejava.dto.VersionInfo> result = new ArrayList<>();
        final long currentV = currentVersion;
        Path versionsDir = layout.versionsDir(objectName);
        if (Files.isDirectory(versionsDir)) {
            try (Stream<Path> files = Files.list(versionsDir)) {
                files.filter(p -> p.getFileName().toString().matches("v\\d+\\.json"))
                        .sorted(java.util.Comparator.comparingLong(ObjectStorageService::versionNumberOf))
                        .forEach(vFile -> result.add(toVersionInfo(objectName, versionNumberOf(vFile),
                                readVersionFile(vFile), currentV)));
            } catch (IOException e) {
                throw new StorageException("Failed to list versions of " + objectName, e);
            }
        }
        return result;
    }

    /**
     * Retire a historical version. Retirement places an atomic tombstone marker next to the
     * immutable {@code vN.json}; bytes are never deleted here. A retired version:
     * <ul>
     *     <li>is no longer readable ({@code 404 not_found}) and is excluded from duplicate reuse;</li>
     *     <li>still appears in the version listing as {@code RETIRED} (its metadata is retained);</li>
     *     <li>makes its exclusively-held blobs garbage-collection candidates after the grace age.</li>
     * </ul>
     * The CURRENT version cannot be retired (409) — upload a replacement first.
     */
    public com.github.lisama569.objectstoragejava.dto.VersionInfo retireVersion(
            String objectName, long version) {
        layout.requireSafeName(objectName);
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            StoredObject stored = getVersion(objectName, version); // 404 if missing
            long currentVersion = currentVersionNumber(objectName);
            if (version == currentVersion) {
                throw new com.github.lisama569.objectstoragejava.exception.VersionConflictException(
                        "Version " + version + " is the current version of " + objectName
                                + " and cannot be retired while current");
            }
            Path marker = layout.retiredMarker(objectName, version);
            long now = System.currentTimeMillis();
            Path tmp;
            try {
                tmp = Files.createTempFile(marker.getParent(), "v" + version + "-", ".retired.tmp");
                Files.writeString(tmp, Long.toString(now), StandardCharsets.UTF_8);
                atomicMove(tmp, marker);
            } catch (IOException e) {
                throw new StorageException("Failed to retire version " + version + " of " + objectName, e);
            }
            // Protect blobs briefly so a GC already iterating cannot delete them before this call
            // returns to clients that may still hold the pre-retirement metadata snapshot.
            try (BlobLeaseManager.Lease ignored = leaseManager.acquire(stored.partChecksums())) {
                log.info("[retire] object={} version={} retired; its exclusive blobs are now GC candidates",
                        objectName, version);
            }
            return toVersionInfo(objectName, version, stored, currentVersion);
        } finally {
            lock.unlock();
        }
    }

    private com.github.lisama569.objectstoragejava.dto.VersionInfo toVersionInfo(
            String objectName, long version, StoredObject stored, long currentVersion) {
        boolean retired = Files.exists(layout.retiredMarker(objectName, version));
        Long retiredAt = null;
        if (retired) {
            retiredAt = readRetiredAt(objectName, version);
        }
        String state = retired
                ? com.github.lisama569.objectstoragejava.dto.VersionState.RETIRED.name()
                : (version == currentVersion
                        ? com.github.lisama569.objectstoragejava.dto.VersionState.CURRENT.name()
                        : com.github.lisama569.objectstoragejava.dto.VersionState.LIVE.name());
        return new com.github.lisama569.objectstoragejava.dto.VersionInfo(
                version, state, stored.checksum(), stored.contentChecksum(), stored.size(), retiredAt);
    }

    private Long readRetiredAt(String objectName, long version) {
        try {
            return Long.parseLong(Files.readString(layout.retiredMarker(objectName, version),
                    StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private long currentVersionNumber(String objectName) {
        StoredObject current = currentVersionOrNull(objectName);
        return current == null ? -1 : current.version();
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

    /**
     * Compute the content fingerprint: SHA-256 over the raw concatenated part bytes in part order.
     * This depends only on the object's actual bytes, never on how they were split into parts.
     */
    private String sha256AssembledBytes(String objectName, String uploadId, int partCount) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            for (int partNumber = 1; partNumber <= partCount; partNumber++) {
                try (InputStream in = Files.newInputStream(
                        layout.partFile(objectName, uploadId, partNumber))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HEX.formatHex(digest.digest());
        } catch (IOException e) {
            throw new StorageException("Failed to compute content checksum from staged parts", e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Find a still-readable (non-retired) version whose raw bytes have the given content fingerprint.
     * Every historical version is considered: re-uploading bytes of an old version (not just the
     * current one) reuses that version and adds no storage. Returns null when content is genuinely new.
     */
    private StoredObject findVersionByContent(String objectName, String contentChecksum) {
        Path versionsDir = layout.versionsDir(objectName);
        if (!Files.isDirectory(versionsDir)) {
            return null;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(versionsDir)) {
            stream.filter(p -> p.getFileName().toString().matches("v\\d+\\.json"))
                    .sorted(java.util.Comparator.comparingLong(
                            (Path p) -> versionNumberOf(p)).reversed())
                    .forEach(files::add);
        } catch (IOException e) {
            throw new StorageException("Failed to scan versions of " + objectName, e);
        }
        for (Path vFile : files) {
            long v = versionNumberOf(vFile);
            if (Files.exists(layout.retiredMarker(objectName, v))) {
                continue; // retired versions are not readable and must not be resurrected
            }
            StoredObject stored = readVersionFile(vFile);
            if (contentChecksum.equalsIgnoreCase(stored.contentChecksum())) {
                return stored;
            }
        }
        return null;
    }

    private static long versionNumberOf(Path versionFile) {
        String name = versionFile.getFileName().toString();
        return Long.parseLong(name.substring(1, name.length() - ".json".length()));
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
