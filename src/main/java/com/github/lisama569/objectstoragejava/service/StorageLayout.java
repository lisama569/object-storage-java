package com.github.lisama569.objectstoragejava.service;

import com.github.lisama569.objectstoragejava.config.StorageProperties;
import com.github.lisama569.objectstoragejava.exception.StorageException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * On-disk layout:
 * <pre>
 *   root/
 *     blobs/{sha256}.dat                 content-addressed, write-once, deduplicated
 *     objects/{name}/versions/vN.json    immutable version metadata
 *     objects/{name}/current             pointer file containing the current version number
 *     staging/{name}/{uploadId}/parts/part-N   in-progress multipart upload parts
 * </pre>
 */
@Component
public class StorageLayout {

    /** Object names are single safe path segments; this keeps path traversal impossible. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final Path root;
    private final Path blobsDir;
    private final Path objectsDir;
    private final Path stagingDir;

    public StorageLayout(StorageProperties properties) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.blobsDir = this.root.resolve("blobs");
        this.objectsDir = this.root.resolve("objects");
        this.stagingDir = this.root.resolve("staging");
    }

    /** Validate an object name and return it unchanged. */
    public String requireSafeName(String objectName) {
        if (objectName == null || !SAFE_NAME.matcher(objectName).matches()) {
            throw new StorageException("Invalid object name: " + objectName
                    + " (allowed: letters, digits, '.', '_', '-', up to 128 chars)");
        }
        return objectName;
    }

    public Path root() {
        return root;
    }

    public Path blobsDir() {
        return blobsDir;
    }

    public Path objectDirRoot() {
        return objectsDir;
    }

    public Path blob(String checksum) {
        return blobsDir.resolve(checksum + ".dat");
    }

    public Path objectDir(String objectName) {
        return objectsDir.resolve(requireSafeName(objectName));
    }

    public Path versionsDir(String objectName) {
        return objectDir(objectName).resolve("versions");
    }

    public Path versionFile(String objectName, long version) {
        return versionsDir(objectName).resolve("v" + version + ".json");
    }

    public Path currentPointer(String objectName) {
        return objectDir(objectName).resolve("current");
    }

    /** Marker file placed next to vN.json when a version is explicitly retired. */
    public Path retiredMarker(String objectName, long version) {
        return versionsDir(objectName).resolve("v" + version + ".retired");
    }

    public Path uploadDir(String objectName, String uploadId) {
        return stagingDir.resolve(requireSafeName(objectName)).resolve(uploadId);
    }

    public Path partFile(String objectName, String uploadId, int partNumber) {
        return uploadDir(objectName, uploadId).resolve("parts").resolve("part-" + partNumber);
    }

    /** Create the top-level directories. Called once at application startup. */
    public void initialize() {
        try {
            Files.createDirectories(blobsDir);
            Files.createDirectories(objectsDir);
            Files.createDirectories(stagingDir);
        } catch (IOException e) {
            throw new StorageException("Failed to initialise storage root: " + root, e);
        }
    }
}

