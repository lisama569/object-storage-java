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
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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
                partSizes.add(Files.size(partFile));
                partChecksums.add(storedChecksum);
            }

            // (a) Legacy chained checksum: SHA-256 over the ordered concatenation of part-checksum hex.
            // Kept for backwards compatibility; it is layout-dependent and NOT the dedup basis.
            String computedChecksum = checksumService.combinePartChecksums(partChecksums);
            if (!computedChecksum.equalsIgnoreCase(manifest.checksum())) {
                log.warn("Object checksum mismatch object={} expected={} computed={}",
                        objectName, manifest.checksum(), computedChecksum);
                throw new ChecksumMismatchException("Object checksum mismatch: manifest claims "
                        + manifest.checksum() + " but assembled object has " + computedChecksum);
            }

            // (b) Chunking-independent checksum: SHA-256 over the raw assembled object bytes.
            // Re-hashed from this session's staged part files, so it does not trust the client and
            // is identical no matter how the same bytes were split into parts.
            String assembledContentChecksum = hashAssembledBytes(objectName, uploadId, partChecksums.size());
            if (manifest.contentChecksum() != null
                    && !assembledContentChecksum.equalsIgnoreCase(manifest.contentChecksum())) {
                log.warn("Content checksum mismatch object={} expected={} computed={}",
                        objectName, manifest.contentChecksum(), assembledContentChecksum);
                throw new ChecksumMismatchException("Content checksum mismatch: manifest claims "
                        + manifest.contentChecksum() + " but assembled bytes have " + assembledContentChecksum);
            }

            StoredObject result;
            StoredObject current = currentVersionOrNull(objectName);
            boolean currentChecksumDerived = current != null
                    && (current.contentChecksum() == null || current.contentChecksum().isBlank());
            String currentContentChecksum = current == null ? null : contentChecksumOf(current);
            if (current != null && assembledContentChecksum.equalsIgnoreCase(currentContentChecksum)) {
                // Identical bytes re-uploaded, regardless of part count / part boundaries:
                // no new version, no new storage. The conclusion is derived purely from the two
                // SHA-256 digests over the assembled byte streams, so it is deterministic and
                // reproducible by anyone re-hashing the bytes.
                String basis = "duplicate: sha256(assembled bytes)=" + assembledContentChecksum
                        + " equals current v" + current.version() + " content checksum "
                        + currentContentChecksum
                        + (currentChecksumDerived
                                ? " (derived on the fly from the old version's immutable blobs,"
                                  + " which predate the contentChecksum metadata field)" : "")
                        + "; decision is content-based and independent of part count/sizes"
                        + " (chained layout checksum=" + computedChecksum + ")";
                result = new StoredObject(objectName, current.version(), current.checksum(),
                        current.size(), current.partChecksums(), true,
                        currentContentChecksum, current.partSizes(), basis);
                log.info("Duplicate upload object={} contentChecksum={} -> reuse version {} (no new version/storage)",
                        objectName, assembledContentChecksum, current.version());
            } else {
                String basis;
                if (current == null) {
                    basis = "new content: no committed version exists yet; publish v1 "
                            + "(contentChecksum=" + assembledContentChecksum + ")";
                } else {
                    basis = "new content: sha256(assembled bytes)=" + assembledContentChecksum
                            + " differs from current v" + current.version() + " content checksum "
                            + currentContentChecksum + "; publish a new version";
                }
                // The whole publication (materialise blobs -> version file -> pointer flip) runs
                // inside a read gate lease so an in-progress GC pass can never treat a blob that
                // belongs to the about-to-be-published version as unreferenced garbage.
                try (ReadGate.ReadLease ignored = readGate.acquireRead()) {
                    result = publishVersion(objectName, uploadId, partChecksums, partSizes,
                            computedChecksum, assembledContentChecksum, totalSize(partSizes), basis);
                }
                log.info("Version decision object={} {}", objectName, basis);
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
     * Hash the raw concatenated bytes of this session's staged parts 1..N. This digest depends
     * only on the object bytes, never on where part boundaries fell.
     */
    private String hashAssembledBytes(String objectName, String uploadId, int partCount) throws IOException {
        MessageDigest digest = checksumService.newSha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            Path partFile = layout.partFile(objectName, uploadId, partNumber);
            try (InputStream in = Files.newInputStream(partFile)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HEX.formatHex(digest.digest());
    }

    private static long totalSize(List<Long> partSizes) {
        long total = 0;
        for (Long size : partSizes) {
            total += size;
        }
        return total;
    }

    /**
     * Content checksum of a version. Prefer the metadata field written by this build; for a
     * version written by an older build (no field), derive it by streaming its referenced blobs
     * in order, so legacy data still participates correctly in chunking-independent dedup.
     */
    private String contentChecksumOf(StoredObject version) {
        if (version.contentChecksum() != null && !version.contentChecksum().isBlank()) {
            return version.contentChecksum();
        }
        try (ReadGate.ReadLease ignored = readGate.acquireRead()) {
            MessageDigest digest = checksumService.newSha256Digest();
            byte[] buffer = new byte[BUFFER_SIZE];
            for (String partChecksum : version.partChecksums()) {
                Path blob = layout.blob(partChecksum);
                if (!Files.exists(blob)) {
                    throw new StorageException("Blob " + partChecksum + " backing version "
                            + version.version() + " of " + version.objectName() + " is missing");
                }
                try (InputStream in = Files.newInputStream(blob)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HEX.formatHex(digest.digest());
        } catch (IOException e) {
            throw new StorageException("Failed to derive content checksum for version "
                    + version.version() + " of " + version.objectName(), e);
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
        // A freshly published version is, by definition, NOT a duplicate upload — even when all
        // its blobs already existed from other versions (content-addressed storage reuse is a
        // separate concept and must not flip the duplicate decision).
        StoredObject stored = new StoredObject(objectName, nextVersion, objectChecksum, totalSize,
                List.copyOf(partChecksums), false, contentChecksum, List.copyOf(partSizes),
                dedupBasis);

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
        log.info("Published version object={} version={} checksum={} contentChecksum={} size={} reusedBlobs={}",
                objectName, nextVersion, objectChecksum, contentChecksum, totalSize, reusedBlobs);
        return stored;
    }

    /**
     * Ensure the content-addressed blob for one staged part exists. The part bytes are verified
     * once more against the checksum before the blob file is atomically committed.
     * <p>
     * If a previous GC pass has moved the blob into quarantine (it cannot be referenced by any
     * readable version in that case, but a concurrent upload may still be materialising it),
     * the quarantined file is restored instead of re-copyed — moving bytes, never duplicating.
     *
     * @return true when an identical blob already existed (storage deduplicated)
     */
    private boolean materialiseBlob(Path partFile, String partChecksum) throws IOException {
        Path blob = layout.blob(partChecksum);
        if (Files.exists(blob)) {
            return true;
        }
        // Verify staged bytes once more, directly against the target blob name.
        String actual;
        try (InputStream in = Files.newInputStream(partFile)) {
            actual = checksumService.sha256(in);
        }
        if (!actual.equalsIgnoreCase(partChecksum)) {
            throw new ChecksumMismatchException("Part content " + partFile
                    + " failed final verification: computed " + actual);
        }
        Path quarantined = layout.quarantineBlob(partChecksum);
        if (Files.exists(quarantined)) {
            Files.createDirectories(layout.blobsDir());
            try {
                atomicMove(quarantined, blob);
            } catch (IOException e) {
                if (!Files.exists(blob)) {
                    throw e;
                }
            }
            log.info("Restored blob from quarantine checksum={} (re-materialised by a new version)",
                    partChecksum);
            return true;
        }
        Path blobTmp = Files.createTempFile(layout.blobsDir(), partChecksum + "-", ".tmp");
        try {
            Files.copy(partFile, blobTmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                atomicMove(blobTmp, blob);
            } catch (IOException e) {
                // A concurrent writer (different thread/process) may have won the race; that's fine.
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
        layout.requireSafeName(objectName);
        // Lease first, resolve snapshot second: closes the window in which GC could otherwise
        // quarantine a blob after we resolved the pointer but before opening the stream.
        ReadGate.ReadLease lease = readGate.acquireRead();
        boolean handedOff = false;
        try {
            StoredObject snapshot = currentVersionOrNull(objectName);
            if (snapshot == null) {
                throw new UploadNotFoundException("No committed version of object " + objectName);
            }
            ByteRange range = ByteRange.parse(rangeHeader, snapshot.size());
            InputStream in = openVersionStream(snapshot, range, lease);
            handedOff = true;
            return new RangeReadResult(snapshot, range, in);
        } finally {
            if (!handedOff) {
                lease.close();
            }
        }
    }

    /** Read a byte range of a specific historical version. */
    public RangeReadResult readVersionRange(String objectName, long version, String rangeHeader) {
        layout.requireSafeName(objectName);
        // Take the read lease BEFORE resolving the metadata: if a concurrent prune removes the
        // version, we get a clean 404 here; and once resolved, GC cannot strand its blobs before
        // the returned stream is closed.
        ReadGate.ReadLease lease = readGate.acquireRead();
        boolean handedOff = false;
        try {
            StoredObject snapshot = getVersion(objectName, version);
            ByteRange range = ByteRange.parse(rangeHeader, snapshot.size());
            InputStream in = openVersionStream(snapshot, range, lease);
            handedOff = true;
            return new RangeReadResult(snapshot, range, in);
        } finally {
            if (!handedOff) {
                lease.close();
            }
        }
    }

    /**
     * Resolve one version snapshot of {@code objectName} and open its verified stream under a
     * single read lease, so metadata and bytes always come from the exact same version even if
     * a concurrent writer publishes between pointer resolution and blob reads.
     */
    private InputStream resolveAndOpen(String objectName, boolean current, long version) {
        return resolveSnapshot(objectName, current, version).content();
    }

    /** Same as {@link #resolveAndOpen}, but also returns the resolved version metadata. */
    private RangeReadResult resolveSnapshot(String objectName, boolean current, long version) {
        layout.requireSafeName(objectName);
        ReadGate.ReadLease lease = readGate.acquireRead();
        boolean handedOff = false;
        try {
            StoredObject snapshot = current ? currentVersionOrNull(objectName)
                    : getVersion(objectName, version);
            if (current && snapshot == null) {
                throw new UploadNotFoundException("No committed version of object " + objectName);
            }
            InputStream in = openVersionStream(snapshot, null, lease);
            handedOff = true;
            return new RangeReadResult(snapshot, null, in);
        } finally {
            if (!handedOff) {
                lease.close();
            }
        }
    }

    /** Resolve the current version snapshot and its full verified byte stream together. */
    public RangeReadResult readCurrentFully(String objectName) {
        return resolveSnapshot(objectName, true, 0);
    }

    /** Resolve one historical version snapshot and its full verified byte stream together. */
    public RangeReadResult readVersionFully(String objectName, long version) {
        return resolveSnapshot(objectName, false, version);
    }

    /** Open the verified byte stream of one already-resolved immutable version snapshot. */
    public InputStream openVersion(StoredObject version) {
        return openVersionStream(version, null);
    }

    /** Open a verified stream for the given version, optionally limited to an inclusive range. */
    public InputStream openVersionStream(StoredObject version, ByteRange range) {
        ReadGate.ReadLease lease = readGate.acquireRead();
        boolean handedOff = false;
        try {
            InputStream in = openVersionStream(version, range, lease);
            handedOff = true;
            return in;
        } finally {
            if (!handedOff) {
                lease.close();
            }
        }
    }

    /**
     * Build the byte stream for one resolved version.
     * <p>
     * Integrity is never skipped, including for ranges spanning several parts: EVERY blob touched
     * by the read is hashed in full against its content-address name BEFORE any byte is returned.
     * Blobs are immutable and named by their SHA-256, so once each touched blob matches the
     * checksum recorded in the version metadata, the emitted window is byte-exact for that
     * version — even when it crosses part boundaries.
     * <p>
     * The passed lease is held until the returned stream is closed (GC exclusion for the read).
     */
    private InputStream openVersionStream(StoredObject version, ByteRange range, ReadGate.ReadLease lease) {
        List<String> partChecksums = version.partChecksums();
        List<Long> partSizes = effectivePartSizes(version);
        long declared = 0;
        for (long s : partSizes) {
            declared += s;
        }
        if (declared != version.size() || partChecksums.size() != partSizes.size()) {
            throw new StorageException("Version " + version.version() + " of " + version.objectName()
                    + " metadata is corrupt: part sizes/count do not match declared size");
        }

        // Resolve, in part order, the [skip, take] window each part contributes.
        List<PartWindow> windows = new ArrayList<>();
        long cursor = 0;
        if (range == null) {
            for (int i = 0; i < partChecksums.size(); i++) {
                windows.add(new PartWindow(i, partChecksums.get(i), 0, partSizes.get(i)));
                cursor += partSizes.get(i);
            }
        } else {
            for (int i = 0; i < partChecksums.size(); i++) {
                long size = partSizes.get(i);
                long partStart = cursor;
                long partEnd = cursor + size - 1;
                cursor += size;
                if (partEnd < range.start()) {
                    continue;
                }
                if (partStart > range.end()) {
                    break;
                }
                long localSkip = Math.max(0, range.start() - partStart);
                long localEnd = Math.min(size - 1, range.end() - partStart);
                windows.add(new PartWindow(i, partChecksums.get(i), localSkip, localEnd - localSkip + 1));
            }
        }

        // Full verification of every touched blob before streaming anything.
        List<InputStream> open = new ArrayList<>();
        boolean success = false;
        try {
            List<InputStream> sequence = new ArrayList<>();
            for (PartWindow window : windows) {
                Path blob = layout.blob(window.checksum());
                if (!Files.exists(blob)) {
                    throw new StorageException("Blob " + window.checksum() + " backing version "
                            + version.version() + " of " + version.objectName() + " is missing — storage is corrupt");
                }
                try (InputStream verify = Files.newInputStream(blob)) {
                    checksumService.verifyHex(verify, window.checksum());
                }
                InputStream raw = Files.newInputStream(blob);
                open.add(raw);
                long skipped = 0;
                while (skipped < window.skip()) {
                    long s = raw.skip(window.skip() - skipped);
                    if (s <= 0) {
                        if (raw.read() == -1) {
                            throw new StorageException("Blob " + window.checksum()
                                    + " ended before requested offset — storage is corrupt");
                        }
                        skipped++;
                    } else {
                        skipped += s;
                    }
                }
                sequence.add(new BoundedInputStream(raw, window.take()));
            }
            InputStream concatenated = new SequenceInputStream(Collections.enumeration(sequence));
            InputStream result = new LeaseReleasingInputStream(concatenated, lease, open);
            success = true;
            return result;
        } catch (IOException e) {
            throw new StorageException("Failed to open version " + version.version()
                    + " of " + version.objectName(), e);
        } finally {
            if (!success) {
                for (InputStream in : open) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                lease.close();
            }
        }
    }

    /** Resolve per-part byte sizes, deriving them from blob files for legacy metadata. */
    private List<Long> effectivePartSizes(StoredObject version) {
        List<String> partChecksums = version.partChecksums();
        List<Long> stored = version.partSizes();
        if (stored != null && stored.size() == partChecksums.size()) {
            return stored;
        }
        List<Long> derived = new ArrayList<>(partChecksums.size());
        try {
            for (String checksum : partChecksums) {
                Path blob = layout.blob(checksum);
                if (!Files.exists(blob)) {
                    throw new StorageException("Blob " + checksum + " backing version "
                            + version.version() + " of " + version.objectName() + " is missing");
                }
                derived.add(Files.size(blob));
            }
        } catch (IOException e) {
            throw new StorageException("Failed to resolve part sizes for version "
                    + version.version() + " of " + version.objectName(), e);
        }
        return derived;
    }

    /** List all committed versions of an object (version numbers ascending). */
    public List<VersionSummary> listVersions(String objectName) {
        layout.requireSafeName(objectName);
        Path versionsDir = layout.versionsDir(objectName);
        List<StoredObject> versions = readAllVersionFiles(objectName, versionsDir);
        versions.sort(java.util.Comparator.comparingLong(StoredObject::version));
        return versions.stream().map(VersionSummary::from).toList();
    }

    /**
     * Remove historical versions beyond the given retention count, keeping the newest
     * {@code retain} versions (the current version is always among them). Blobs are not deleted
     * here; orphaned blobs are reclaimed later by {@code collectGarbage}.
     */
    public PruneResult pruneVersions(String objectName, int retain) {
        layout.requireSafeName(objectName);
        if (retain < 1) {
            throw new InvalidManifestException("retain must be >= 1 (the current version is always retained)");
        }
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            StoredObject current = currentVersionOrNull(objectName);
            if (current == null) {
                throw new UploadNotFoundException("No committed version of object " + objectName);
            }
            Path versionsDir = layout.versionsDir(objectName);
            List<StoredObject> versions = readAllVersionFiles(objectName, versionsDir);
            versions.sort(java.util.Comparator.comparingLong(StoredObject::version));

            int keepCount = Math.min(retain, versions.size());
            List<StoredObject> kept = versions.subList(versions.size() - keepCount, versions.size());
            Set<Long> keptNumbers = kept.stream().map(StoredObject::version)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            // Defensive: current must never disappear, even if on-disk state is surprising.
            keptNumbers.add(current.version());

            List<Long> removed = new ArrayList<>();
            for (StoredObject v : versions) {
                if (!keptNumbers.contains(v.version())) {
                    try {
                        Files.deleteIfExists(layout.versionFile(objectName, v.version()));
                    } catch (IOException e) {
                        throw new StorageException("Failed to remove version " + v.version()
                                + " of " + objectName, e);
                    }
                    removed.add(v.version());
                }
            }
            List<Long> retained = new ArrayList<>(keptNumbers.stream().sorted().toList());
            log.info("Pruned object={} current=v{} retained={} removed={} (blobs left for GC)",
                    objectName, current.version(), retained, removed);
            return new PruneResult(objectName, current.version(), retained, removed);
        } finally {
            lock.unlock();
        }
    }

    /** Delete the whole object (all versions + pointer + staging); its blobs become GC candidates. */
    public void deleteObject(String objectName) {
        layout.requireSafeName(objectName);
        Lock lock = lockManager.lockFor(objectName);
        lock.lock();
        try {
            deleteRecursively(layout.objectDir(objectName));
            deleteRecursively(stagingObjectDir(objectName));
            log.info("Deleted object={} (all version metadata removed; blobs reclaimed by next GC)", objectName);
        } finally {
            lock.unlock();
        }
    }

    private Path stagingObjectDir(String objectName) {
        return layout.uploadDir(objectName, "dummy").getParent();
    }

    /**
     * Snapshot of every part checksum referenced by any still-readable version of any object.
     * Used by the GC pass; callers run this inside the GC exclusive section.
     */
    public Set<String> collectLiveBlobChecksums() {
        Set<String> live = new java.util.HashSet<>();
        Path objectsRoot = layout.objectDir("dummy").getParent();
        if (!Files.isDirectory(objectsRoot)) {
            return live;
        }
        try (Stream<Path> objects = Files.list(objectsRoot)) {
            for (Path objectDir : objects.filter(Files::isDirectory).toList()) {
                Path versionsDir = objectDir.resolve("versions");
                if (!Files.isDirectory(versionsDir)) {
                    continue;
                }
                try (Stream<Path> versionFiles = Files.list(versionsDir)) {
                    for (Path vFile : versionFiles
                            .filter(p -> p.getFileName().toString().matches("v\\d+\\.json")).toList()) {
                        StoredObject stored;
                        try {
                            stored = objectMapper.readValue(Files.readAllBytes(vFile), StoredObject.class);
                        } catch (IOException e) {
                            // Fail safe: never garbage-collect around metadata we cannot parse.
                            throw new StorageException("Aborting GC: cannot parse version metadata "
                                    + vFile + " (" + e.getMessage() + ")", e);
                        }
                        live.addAll(stored.partChecksums());
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException("Failed to enumerate live blob references", e);
        }
        return live;
    }

    private List<StoredObject> readAllVersionFiles(String objectName, Path versionsDir) {
        List<StoredObject> versions = new ArrayList<>();
        if (!Files.isDirectory(versionsDir)) {
            return versions;
        }
        try (Stream<Path> files = Files.list(versionsDir)) {
            for (Path vFile : files
                    .filter(p -> p.getFileName().toString().matches("v\\d+\\.json")).toList()) {
                versions.add(readVersionFile(vFile));
            }
        } catch (IOException e) {
            throw new StorageException("Failed to list versions of " + objectName, e);
        }
        return versions;
    }

    /** One part's contribution window to a read. */
    private record PartWindow(int index, String checksum, long skip, long take) {
    }

    /** Limit an underlying stream to exactly {@code limit} bytes. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        private BoundedInputStream(InputStream in, long limit) {
            this.in = in;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b != -1) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int read = in.read(b, off, toRead);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    /** Close the whole read and release the GC-excluding lease exactly once. */
    private static final class LeaseReleasingInputStream extends InputStream {
        private final InputStream delegate;
        private final ReadGate.ReadLease lease;
        private final List<InputStream> owned;
        private boolean closed;

        private LeaseReleasingInputStream(InputStream delegate, ReadGate.ReadLease lease,
                                          List<InputStream> owned) {
            this.delegate = delegate;
            this.lease = lease;
            this.owned = owned;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } finally {
                for (InputStream in : owned) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                lease.close();
            }
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
        // Resolve pointer and open its blobs under ONE lease: head + bytes always describe the
        // exact same version even if another writer publishes while this read is in flight.
        return resolveAndOpen(objectName, true, 0);
    }

    public InputStream readVersion(String objectName, long version) {
        return resolveAndOpen(objectName, false, version);
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
