package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.dto.ChunkUploadResponse;
import com.github.lisama569.objectstoragejava.dto.GcReport;
import com.github.lisama569.objectstoragejava.dto.ManifestRequest;
import com.github.lisama569.objectstoragejava.dto.PruneResult;
import com.github.lisama569.objectstoragejava.dto.RangeReadResult;
import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
import com.github.lisama569.objectstoragejava.dto.VersionSummary;
import com.github.lisama569.objectstoragejava.service.ObjectStorageService;
import com.github.lisama569.objectstoragejava.service.StorageReclamationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** HTTP entry point for the local object-storage service. */
@RestController
@RequestMapping("/api/objects")
public class ObjectStorageController {

    private final ObjectStorageService storageService;
    private final StorageReclamationService reclamationService;

    public ObjectStorageController(ObjectStorageService storageService,
                                   StorageReclamationService reclamationService) {
        this.storageService = storageService;
        this.reclamationService = reclamationService;
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

    /** Read the current (latest committed) version; optional single byte range. */
    @GetMapping("/{objectName}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String objectName,
                                                     @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                             String rangeHeader) {
        // One single snapshot: metadata and bytes are resolved together, so a concurrent
        // publication can never pair a new version header with old bytes (or vice versa).
        RangeReadResult result = storageService.readObjectRange(objectName, rangeHeader);
        return streamResponse(result);
    }

    /** Metadata of the current version. */
    @GetMapping("/{objectName}/meta")
    public StoredObject head(@PathVariable String objectName) {
        return storageService.headObject(objectName);
    }

    /** List all committed version numbers/metadata (ascending). */
    @GetMapping("/{objectName}/versions")
    public List<VersionSummary> versions(@PathVariable String objectName) {
        return storageService.listVersions(objectName);
    }

    /** Read a specific historical version (old versions are never overwritten); optional range. */
    @GetMapping("/{objectName}/versions/{version}")
    public ResponseEntity<StreamingResponseBody> getVersion(@PathVariable String objectName,
                                                            @PathVariable long version,
                                                            @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                                    String rangeHeader) {
        RangeReadResult result = storageService.readVersionRange(objectName, version, rangeHeader);
        return streamResponse(result);
    }

    /** Retire historical versions, keeping the newest {@code retain} (current always survives). */
    @PostMapping("/{objectName}/prune")
    public PruneResult prune(@PathVariable String objectName,
                             @RequestParam(defaultValue = "1") int retain) {
        return storageService.pruneVersions(objectName, retain);
    }

    /** Delete the whole object (version metadata + pointer + staging); blobs become GC work. */
    @DeleteMapping("/{objectName}")
    public ResponseEntity<Void> deleteObject(@PathVariable String objectName) {
        storageService.deleteObject(objectName);
        return ResponseEntity.noContent().build();
    }

    /** Run one safe, retryable blob reclamation pass. */
    @PostMapping("/admin/gc")
    public GcReport collectGarbage(@RequestParam(value = "minAgeMillis", required = false)
                                   Long minAgeMillis) {
        return minAgeMillis == null
                ? reclamationService.collectGarbage()
                : reclamationService.collectGarbage(minAgeMillis);
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(RangeReadResult result) {
        StoredObject meta = result.version();
        InputStream in = result.content();
        StreamingResponseBody body = out -> {
            try (InputStream stream = in) {
                stream.transferTo(out);
            }
        };
        String filename = URLEncoder.encode(meta.objectName(), StandardCharsets.UTF_8).replace("+", "%20");
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(
                        result.range() == null ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH,
                        Long.toString(result.range() == null ? meta.size() : result.range().length()))
                .header("X-Object-Version", Long.toString(meta.version()))
                .header("X-Checksum-SHA256", meta.checksum())
                .header("X-Content-Checksum-SHA256",
                        meta.contentChecksum() == null ? "" : meta.contentChecksum())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename);
        if (result.range() != null) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + result.range().start() + "-" + result.range().end() + "/" + meta.size());
        }
        return builder.body(body);
    }
}
