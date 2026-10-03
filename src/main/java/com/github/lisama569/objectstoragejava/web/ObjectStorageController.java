package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.dto.ChunkUploadResponse;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
import com.github.lisama569.objectstoragejava.service.ObjectStorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** HTTP entry point for the local object-storage service. */
@RestController
@RequestMapping("/api/objects")
public class ObjectStorageController {

    private final ObjectStorageService storageService;

    public ObjectStorageController(ObjectStorageService storageService) {
        this.storageService = storageService;
    }

    /** 1. Initialise a multipart upload. */
    @PostMapping("/{objectName}/uploads")
    public UploadInitResponse initUpload(@PathVariable String objectName) {
        return storageService.initUpload(objectName);
    }

    /** 2. Upload one part (idempotent; re-PUT of the same part is a resume no-op). */
    @PutMapping("/{objectName}/uploads/{uploadId}/parts/{partNumber}")
    public ChunkUploadResponse uploadPart(@PathVariable String objectName,
                                          @PathVariable String uploadId,
                                          @PathVariable int partNumber,
                                          @RequestHeader(value = "X-Checksum-SHA256", required = false)
                                                  String expectedChecksum,
                                          HttpServletRequest request) throws java.io.IOException {
        // Read the raw, unbuffered request body. A bare InputStream method parameter is resolved
        // to an empty stream by Spring; the servlet request's input stream is the actual body.
        try (InputStream content = request.getInputStream()) {
            return storageService.uploadPart(objectName, uploadId, partNumber, expectedChecksum, content);
        }
    }

    /** 3. Discover which parts already landed (for resumption after interruption). */
    @GetMapping("/{objectName}/uploads/{uploadId}")
    public UploadStatusResponse uploadStatus(@PathVariable String objectName,
                                             @PathVariable String uploadId) {
        return storageService.uploadStatus(objectName, uploadId);
    }

    /** 4. Complete: validate the manifest, verify checksums, atomically publish a version. */
    @PostMapping("/{objectName}/uploads/{uploadId}/complete")
    public StoredObject complete(@PathVariable String objectName,
                                 @PathVariable String uploadId,
                                 @RequestBody ManifestRequest manifest) {
        return storageService.completeUpload(objectName, uploadId, manifest);
    }

    /** 5. Abort: drop staging without touching committed versions. */
    @DeleteMapping("/{objectName}/uploads/{uploadId}")
    public ResponseEntity<Void> abort(@PathVariable String objectName,
                                      @PathVariable String uploadId) {
        storageService.abortUpload(objectName, uploadId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Read the current (latest committed) version; supports a single {@code bytes=} range.
     * Metadata headers and the streamed bytes come from the SAME snapshot resolved in the service.
     */
    @GetMapping("/{objectName}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String objectName,
                                                     @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                     String rangeHeader) {
        return toResponse(objectName, storageService.openCurrent(objectName, rangeHeader));
    }

    /** Metadata of the current version. */
    @GetMapping("/{objectName}/meta")
    public StoredObject head(@PathVariable String objectName) {
        return storageService.headObject(objectName);
    }

    /** Read a specific historical version (old versions are never overwritten); range supported. */
    @GetMapping("/{objectName}/versions/{version}")
    public ResponseEntity<StreamingResponseBody> getVersion(@PathVariable String objectName,
                                                            @PathVariable long version,
                                                            @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                            String rangeHeader) {
        return toResponse(objectName, storageService.openVersionSnapshot(objectName, version, rangeHeader));
    }

    /** List versions and their state (CURRENT/LIVE/RETIRED). */
    @GetMapping("/{objectName}/versions")
    public java.util.List<com.github.lisama569.objectstoragejava.dto.VersionInfo> listVersions(
            @PathVariable String objectName) {
        return storageService.listVersions(objectName);
    }

    /** Retire a historical version; its unreferenced blobs become GC candidates. */
    @DeleteMapping("/{objectName}/versions/{version}")
    public com.github.lisama569.objectstoragejava.dto.VersionInfo retireVersion(
            @PathVariable String objectName,
            @PathVariable long version) {
        return storageService.retireVersion(objectName, version);
    }

    private ResponseEntity<StreamingResponseBody> toResponse(
            String objectName,
            com.github.lisama569.objectstoragejava.dto.OpenedObject opened) {
        StoredObject meta = opened.metadata();
        StreamingResponseBody body = out -> {
            // Closing the stream releases its blob-protection lease (also on client disconnect).
            try (InputStream stream = opened.stream()) {
                stream.transferTo(out);
            }
        };
        String filename = URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20");
        var builder = (opened.ranged()
                        ? ResponseEntity.status(org.springframework.http.HttpStatus.PARTIAL_CONTENT)
                        : ResponseEntity.ok())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header("X-Object-Version", Long.toString(meta.version()))
                .header("X-Checksum-SHA256", meta.checksum())
                .header("X-Content-Checksum-SHA256",
                        meta.contentChecksum() == null ? "" : meta.contentChecksum())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename);

        if (opened.ranged()) {
            var range = opened.range();
            builder.header(HttpHeaders.CONTENT_LENGTH, Long.toString(range.length()))
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + range.start() + "-" + range.end() + "/" + meta.size());
        } else {
            builder.header(HttpHeaders.CONTENT_LENGTH, Long.toString(meta.size()));
        }
        return builder.body(body);
    }
}
