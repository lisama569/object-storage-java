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
import java.io.FilterInputStream;
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
import java.util.Set;
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
            List<Long> partSizes = new ArrayList<>();
            List<Path> partFiles = new ArrayList<>();
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
                long partSize = Files.size(partFile);
                totalSize += partSize;
                partChecksums.add(storedChecksum.toLowerCase());
                partSizes.add(partSize);
                partFiles.add(partFile);
            }

            // Object-level checksum: chained digest over the ordered part checksums.
            String computedChecksum = checksumService.combinePartChecksums(partChecksums);
            if (!computedChecksum.equalsIgnoreCase(manifest.checksum())) {
                log.warn("Object checksum mismatch object={} expected={} computed={}",
                        objectName, manifest.checksum(), computedChecksum);
                throw new ChecksumMismatchException("Object checksum mismatch: manifest claims "
                        + manifest.checksum() + " but assembled object has " + computedChecksum);
            }

            // Chunking-independent identity: SHA-256 over the assembled raw BYTES. Re-chunking the
            // same byte content into any number/size of parts yields exactly the same digest.
            String computedContentChecksum = digestAssembledBytes(partFiles);
            if (manifest.contentChecksum() != null && !manifest.contentChecksum().isBlank()
                    && !computedContentChecksum.equalsIgnoreCase(manifest.contentChecksum())) {
                log.warn("Content checksum mismatch object={} expected={} computed={}",
                        objectName, manifest.contentChecksum(), computedContentChecksum);
                throw new ChecksumMismatchException("Content checksum mismatch: manifest claims "
                        + manifest.contentChecksum() + " but assembled bytes have " + computedContentChecksum);
            }
            String contentChecksum = computedContentChecksum;

            StoredObject result;
            StoredObject current = currentVersionOrNull(objectName);
            String currentContentChecksum = current == null ? null
                    : (current.contentChecksum() != null ? current.contentChecksum()
                    : digestVersionContent(current));
            if (current != null
                    && current.size() == totalSize
                    && currentContentChecksum.equalsIgnoreCase(contentChecksum)) {
                // Same raw bytes, regardless of how the upload was split into parts: reuse the
                // current version AND its blobs. No version file, no blob and no storage is added.
                String basis = "duplicate: SHA-256 over assembled " + totalSize + " bytes is "
                        + contentChecksum + ", identical to current v" + current.version()
                        + "; decision is independent of part count/part sizes";
                result = new StoredObject(objectName, current.version(), current.checksum(),
                        current.size(), current.partChecksums(), true,
                        currentContentChecksum.toLowerCase(), current.partSizes(), basis);
                log.info("Duplicate upload object={} contentChecksum={} -> reuse version {} (no new version, no new storage)",
                        objectName, contentChecksum, current.version());
            } else {
                String basis;
                if (current == null) {
                    basis = "new version: no current version exists; first publication";
                } else {
                    basis = "new version: assembled SHA-256 " + contentChecksum + " (" + totalSize
                            + " bytes) differs from current v" + current.version() + " ("
                            + currentContentChecksum + ", " + current.size() + " bytes)";
                }
                // Publication runs under the shared read gate so a concurrent GC pass cannot move
                // a blob between materialisation and the live-reference scan.
                final long finalTotalSize = totalSize;
                final String finalBasis = basis;
                result = readGate.runWithReadLock(() -> {
                    try {
                        return publishVersion(objectName, uploadId, partChecksums, partSizes,
                                computedChecksum, contentChecksum, finalTotalSize, finalBasis);
                    } catch (IOException e) {
                        throw new StorageException("Failed to publish version for " + objectName, e);
                    }
                });
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
                                        List<String> partChecksums, List<Long> partSizes,
                                        String objectChecksum, String contentChecksum,
                                        long totalSize, String dedupBasis) throws IOException {
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
        StoredObject stored = new StoredObject(objectName, nextVersion, objectChecksum, totalSize,
                List.copyOf(partChecksums), false, contentChecksum, List.copyOf(partSizes), dedupBasis);

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
     * SHA-256 over the raw bytes of one upload session's ordered parts. This digest depends only
     * on the assembled content, never on how it was split into parts.
     */
    private String digestAssembledBytes(List<Path> orderedPartFiles) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            for (Path partFile : orderedPartFiles) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(partFile))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new StorageException("Failed to compute content checksum from staged parts", e);
        }
    }

    /**
     * SHA-256 over the raw bytes of an already-committed version, streaming its referenced blobs
     * in order. Used to backfill {@code contentChecksum} for metadata written before that field
     * existed. The caller is responsible for holding the read gate when necessary.
     */
    String digestVersionContent(StoredObject version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            for (String partChecksum : version.partChecksums()) {
                Path blob = layout.blob(partChecksum);
                if (!Files.exists(blob)) {
                    throw new StorageException("Version v" + version.version() + " of object "
                            + version.objectName() + " references missing blob " + partChecksum
                            + " — storage is corrupt");
                }
                try (InputStream in = new BufferedInputStream(Files.newInputStream(blob))) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HEX.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new StorageException("Failed to compute content checksum for version v"
                    + version.version(), e);
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

    // --------------------------------------------------------- new read / lifecycle APIs

    /**
     * Read the current version, optionally restricted to one validated byte range.
     * The metadata snapshot and the byte stream are resolved as ONE unit: the current pointer is
     * read once under the shared read gate, and every byte in the returned stream comes from that
     * exact version's blobs. A version published concurrently afterwards can neither change the
     * reported version nor the bytes, and garbage collection cannot reclaim those blobs before
     * the stream is closed.
     */
    public RangeReadResult readObjectRange(String objectName, String rangeHeader) {
        layout.requireSafeName(objectName);
        readGate.acquireRead();
        boolean handedOff = false;
        try {
            StoredObject snapshot = currentVersionOrNull(objectName);
            if (snapshot == null) {
                throw new UploadNotFoundException("No committed version of object " + objectName);
            }
            ByteRange range = ByteRange.parse(rangeHeader, snapshot.size());
            InputStream in = openVerifiedStream(snapshot, range);
            handedOff = true;
            return new RangeReadResult(snapshot, range, in);
        } finally {
            if (!handedOff) {
                readGate.releaseRead();
            }
        }
    }

    /**
     * Read one specific historical version, optionally restricted to one validated byte range.
     * The version metadata is snapshotted once under the read gate; the stream serves exactly
     * that version regardless of later publications or pruning of OTHER versions.
     */
    public RangeReadResult readVersionRange(String objectName, long version, String rangeHeader) {
        layout.requireSafeName(objectName);
        readGate.acquireRead();
        boolean handedOff = false;
        try {
            StoredObject snapshot = getVersion(objectName, version);
            ByteRange range = ByteRange.parse(rangeHeader, snapshot.size());
            InputStream in = openVerifiedStream(snapshot, range);
            handedOff = true;
            return new RangeReadResult(snapshot, range, in);
        } finally {
            if (!handedOff) {
                readGate.releaseRead();
            }
        }
    }

    /** Open the verified byte stream of one already-resolved immutable version snapshot. */
    public InputStream openVersion(StoredObject version) {
        return openVersionStream(version, null);
    }

    /**
     * Open a verified stream for the given version snapshot, optionally limited to an inclusive
     * range. Every blob that contributes bytes (including, for cross-part ranges, every part the
     * range spans) is checksum-verified in full while its bytes are served. The shared read gate
     * is held until the returned stream is closed.
     */
    public InputStream openVersionStream(StoredObject version, ByteRange range) {
        readGate.acquireRead();
        boolean handedOff = false;
        try {
            InputStream in = openVerifiedStream(version, range);
            handedOff = true;
            return in;
        } finally {
            if (!handedOff) {
                readGate.releaseRead();
            }
        }
    }

    /** List all committed versions of an object (version numbers ascending). */
    public List<VersionSummary> listVersions(String objectName) {
        layout.requireSafeName(objectName);
        return readGate.runWithReadLock(() -> {
            Path versionsDir = layout.versionsDir(objectName);
            if (!Files.isDirectory(versionsDir)) {
                throw new UploadNotFoundException("No committed version of object " + objectName);
            }
            List<StoredObject> versions = new ArrayList<>();
            try (Stream<Path> files = Files.list(versionsDir)) {
                files.filter(p -> p.getFileName().toString().matches("v\\d+\\.json"))
                        .forEach(p -> versions.add(readVersionFile(p)));
            } catch (IOException e) {
                throw new StorageException("Failed to list versions of " + objectName, e);
            }
            versions.sort(java.util.Comparator.comparingLong(StoredObject::version));
            return versions.stream().map(VersionSummary::from).toList();
        });
    }

    /**
     * Remove historical versions beyond {@code retain} (the current version always survives and
     * is part of the retained window). Only immutable version metadata files are deleted here;
     * blob bytes are reclaimed later by garbage collection once no readable version references
     * them. Returns exactly which versions remain readable and which were removed.
     */
    public PruneResult pruneVersions(String objectName, int retain) {
        layout.requireSafeName(objectName);
        if (retain < 1) {
            throw new InvalidManifestException("retain must be at least 1 (the current version is always kept)");
        }
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            return readGate.runWithReadLock(() -> {
                StoredObject current = currentVersionOrNull(objectName);
                if (current == null) {
                    throw new UploadNotFoundException("No committed version of object " + objectName);
                }
                List<VersionSummary> all = listVersions(objectName);
                List<Long> removed = new ArrayList<>();
                // Keep the newest `retain` versions, which always includes the current pointer's
                // target because pointer numbers never roll back.
                List<VersionSummary> kept = all.size() <= retain
                        ? all
                        : all.subList(all.size() - retain, all.size());
                java.util.Set<Long> keptNumbers = kept.stream().map(VersionSummary::version)
                        .collect(java.util.stream.Collectors.toSet());
                if (!keptNumbers.contains(current.version())) {
                    throw new StorageException("Refusing to prune: current pointer v" + current.version()
                            + " is outside the retained window — storage is corrupt", null);
                }
                for (VersionSummary v : all) {
                    if (!keptNumbers.contains(v.version())) {
                        try {
                            Files.deleteIfExists(layout.versionFile(objectName, v.version()));
                        } catch (IOException e) {
                            throw new StorageException("Failed to remove version file v" + v.version(), e);
                        }
                        removed.add(v.version());
                    }
                }
                List<Long> retained = kept.stream().map(VersionSummary::version).toList();
                log.info("Pruned object={} retained={} removed={} (blobs reclaimed by GC later)",
                        objectName, retained, removed);
                return new PruneResult(objectName, current.version(), retained, removed);
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * Delete the whole object: all version metadata, the current pointer and every staging
     * session. Blobs are not touched here; they become garbage-collection candidates once no
     * object references them, so an in-flight reader keeps working until it closes its stream.
     */
    public void deleteObject(String objectName) {
        layout.requireSafeName(objectName);
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            readGate.runWithReadLock(() -> {
                deleteRecursively(layout.objectDir(objectName));
                deleteRecursively(stagingDirFor(objectName));
                log.info("Deleted object={} (its blobs are GC candidates)", objectName);
            });
        } finally {
            lock.unlock();
        }
    }

    private Path stagingDirFor(String objectName) {
        return layout.root().resolve("staging").resolve(objectName);
    }

    /**
     * Snapshot of every part/blob checksum referenced by any still-readable version of any
     * object. Computed under the read gate so it is consistent with concurrent publications.
     */
    public Set<String> collectLiveBlobChecksums() {
        return readGate.runWithReadLock(this::collectLiveBlobChecksumsUnlocked);
    }

    /**
     * Live-reference scan WITHOUT acquiring the read gate. Callers must already hold the gate
     * (garbage collection holds the exclusive lock; publications hold a read permit).
     */
    Set<String> collectLiveBlobChecksumsUnlocked() {
        Set<String> live = new java.util.HashSet<>();
        for (Path objectDir : layout.objectDirectories()) {
            String objectName = objectDir.getFileName().toString();
            Path versionsDir = layout.versionsDir(objectName);
            if (!Files.isDirectory(versionsDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(versionsDir)) {
                for (Path vFile : files.filter(p -> p.getFileName().toString().matches("v\\d+\\.json"))
                        .toList()) {
                    StoredObject meta = readVersionFile(vFile);
                    live.addAll(meta.partChecksums());
                }
            } catch (IOException e) {
                throw new StorageException("Failed to scan versions of " + objectName, e);
            }
        }
        return live;
    }

    /**
     * Build the verified, range-restricted byte stream of one immutable version snapshot.
     * Called while holding one read-gate permit (the permit is released by the stream's close()).
     */
    private InputStream openVerifiedStream(StoredObject version, ByteRange range) {
        List<String> parts = version.partChecksums();
        List<Long> sizes = version.partSizes();
        long total = 0;
        for (int i = 0; i < parts.size(); i++) {
            long partSize = sizes != null && i < sizes.size() && sizes.get(i) != null
                    ? sizes.get(i) : sizeOfBlobQuietly(parts.get(i));
            total += partSize;
        }
        if (total != version.size()) {
            throw new StorageException("Version v" + version.version() + " of object " + version.objectName()
                    + " metadata size " + version.size() + " disagrees with referenced blob sizes "
                    + total + " — storage is corrupt", null);
        }
        // Absolute offsets of the first and last bytes to serve, across the concatenated parts.
        long first = range == null ? 0 : range.start();
        long last = range == null ? total - 1 : range.end();

        // Walk parts once to locate the slice window of every part that contributes bytes.
        // All such parts (including every part a cross-part range spans) are opened and verified.
        List<InputStream> slices = new ArrayList<>();
        try {
            long offset = 0;
            for (int i = 0; i < parts.size(); i++) {
                long partSize = sizes != null && i < sizes.size() && sizes.get(i) != null
                        ? sizes.get(i) : sizeOfBlobQuietly(parts.get(i));
                long partStart = offset;
                long partEnd = offset + partSize - 1;
                if (partEnd >= first && partStart <= last) {
                    long skip = Math.max(0, first - partStart);
                    long limit = Math.min(partEnd, last) - partStart - skip + 1;
                    slices.add(openVerifiedPartSlice(parts.get(i), skip, limit, i + 1, version));
                }
                offset += partSize;
            }
        } catch (RuntimeException | Error e) {
            for (InputStream s : slices) {
                try {
                    s.close();
                } catch (IOException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
        InputStream sequence = new ChainedSlicesInputStream(slices);
        return new GateReleasingInputStream(sequence, version, range, readGate);
    }

    private long sizeOfBlobQuietly(String checksum) {
        try {
            return Files.size(layout.blob(checksum));
        } catch (IOException e) {
            throw new StorageException("Failed to stat blob " + checksum + " — storage is corrupt", e);
        }
    }

    /**
     * Open one blob, skip {@code skip} bytes, expose exactly {@code limit} bytes to the reader
     * and hash the WHOLE blob (skipped tail drained on close) to verify it against its
     * content-addressed checksum. Slices are concatenated in {@link #openVerifiedStream}.
     */
    private InputStream openVerifiedPartSlice(String checksum, long skip, long limit, int partNumber,
                                              StoredObject version) {
        Path blob = layout.blob(checksum);
        if (!Files.exists(blob)) {
            throw new StorageException("Version v" + version.version() + " of object " + version.objectName()
                    + " references missing blob " + checksum + " — storage is corrupt", null);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream raw = new BufferedInputStream(Files.newInputStream(blob));
            // Discard the prefix that precedes the requested slice, but it is still part of the
            // blob and MUST be folded into the digest — otherwise a corrupt prefix would escape
            // verification on any non-zero-start range.
            byte[] prefix = new byte[BUFFER_SIZE];
            long remainingSkip = skip;
            while (remainingSkip > 0) {
                int n = raw.read(prefix, 0, (int) Math.min(prefix.length, remainingSkip));
                if (n < 0) {
                    raw.close();
                    throw new StorageException("Blob " + checksum + " ended before requested offset", null);
                }
                digest.update(prefix, 0, n);
                remainingSkip -= n;
            }
            return new VerifiedPartInputStream(raw, digest, checksum, limit, partNumber, version);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new StorageException("Failed to open blob " + checksum, e);
        }
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

    // ------------------------------------------------------------------ stream internals

    /**
     * Concatenation of the per-part verified slices. Unlike {@link SequenceInputStream},
     * {@code close()} drains and verifies EVERY part slice even when an earlier slice's
     * checksum fails (failures are aggregated as suppressed exceptions), so a torn blob can
     * never be silently hidden.
     */
    private static final class ChainedSlicesInputStream extends InputStream {

        private final List<InputStream> slices;
        private int index;
        private boolean closed;

        ChainedSlicesInputStream(List<InputStream> slices) {
            this.slices = slices;
        }

        @Override
        public int read() throws IOException {
            while (index < slices.size()) {
                int b = slices.get(index).read();
                if (b != -1) {
                    return b;
                }
                index++;
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (index < slices.size()) {
                int n = slices.get(index).read(b, off, len);
                if (n > 0) {
                    return n;
                }
                if (n == 0) {
                    return 0;
                }
                index++;
            }
            return -1;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException first = null;
            for (InputStream slice : slices) {
                try {
                    slice.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }

    /**
     * One verified slice of one content-addressed blob. At most {@code limit} bytes are exposed
     * to the reader, but the WHOLE underlying blob is hashed (the unread tail is drained on
     * close) so a cross-part range read still verifies every part it touches end to end.
     */
    private static final class VerifiedPartInputStream extends FilterInputStream {

        private final MessageDigest digest;
        private final String expectedChecksum;
        private final long limit;
        private final int partNumber;
        private final StoredObject version;
        private long exposed;
        private boolean verified;
        private boolean closed;

        VerifiedPartInputStream(InputStream in, MessageDigest digest, String expectedChecksum,
                                long limit, int partNumber, StoredObject version) {
            super(in);
            this.digest = digest;
            this.expectedChecksum = expectedChecksum;
            this.limit = limit;
            this.partNumber = partNumber;
            this.version = version;
        }

        @Override
        public int read() throws IOException {
            if (exposed >= limit) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                digest.update((byte) b);
                exposed++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            if (exposed >= limit) {
                return -1;
            }
            int toRead = (int) Math.min(len, limit - exposed);
            int n = in.read(b, off, toRead);
            if (n > 0) {
                digest.update(b, off, n);
                exposed += n;
            }
            return n;
        }

        /** Read without exposing (folds bytes into the digest only). */
        private int readSilently(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                // Drain the remainder of this blob so its full content is checksum-verified even
                // when the reader only asked for a prefix slice.
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = readSilently(buffer, 0, buffer.length)) != -1) {
                    digest.update(buffer, 0, n);
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                verified = true;
                if (!actual.equalsIgnoreCase(expectedChecksum)) {
                    throw new ChecksumMismatchException("Read-time verification failed for part "
                            + partNumber + " of object " + version.objectName() + " v" + version.version()
                            + ": blob " + expectedChecksum + " actually hashes to " + actual);
                }
            } finally {
                super.close();
            }
        }
    }

    /**
     * Wraps the concatenated verified part slices: on close it (1) closes every part slice,
     * which triggers their checksum comparison, and (2) releases the read-gate permit so a
     * blocked GC pass may proceed once this reader is truly done with the blobs.
     */
    private static final class GateReleasingInputStream extends FilterInputStream {

        private final ReadGate gate;
        private final StoredObject version;
        private final ByteRange range;
        private boolean closed;

        GateReleasingInputStream(InputStream in, StoredObject version, ByteRange range, ReadGate gate) {
            super(in);
            this.version = version;
            this.range = range;
            this.gate = gate;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                super.close(); // closes all part slices -> checksum verification happens here
            } catch (IOException e) {
                failure = e;
            } finally {
                gate.releaseRead();
            }
            if (failure != null) {
                throw failure;
            }
            if (range == null) {
                log.debug("Full read verified object={} version={} parts={}",
                        version.objectName(), version.version(), version.partChecksums().size());
            } else {
                log.debug("Range read verified object={} version={} range={}-{}",
                        version.objectName(), version.version(), range.start(), range.end());
            }
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
