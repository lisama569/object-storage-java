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
import com.github.lisama569.objectstoragejava.exception.InvalidRangeException;
import com.github.lisama569.objectstoragejava.exception.RangeNotSatisfiableException;
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

    /** Read the current (latest committed) version; optional Range header for a byte window. */
    @GetMapping("/{objectName}")
    public ResponseEntity<StreamingResponseBody> get(@PathVariable String objectName,
                                                     @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                             String rangeHeader) {
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            RangeReadResult result = storageService.readObjectRange(objectName, rangeHeader);
            return rangeResponse(objectName, result);
        }
        // Metadata and bytes come from ONE service-side resolution + lease, so response headers
        // and body can never describe different versions under a concurrent publication.
        RangeReadResult result = storageService.readCurrentFully(objectName);
        return streamResponse(result.version(), result.content());
    }

    /** Metadata of the current version. */
    @GetMapping("/{objectName}/meta")
    public StoredObject head(@PathVariable String objectName) {
        return storageService.headObject(objectName);
    }

    /** List all committed versions (ascending version numbers). */
    @GetMapping("/{objectName}/versions")
    public List<VersionSummary> listVersions(@PathVariable String objectName) {
        return storageService.listVersions(objectName);
    }

    /** Read a specific historical version (old versions are never overwritten). */
    @GetMapping("/{objectName}/versions/{version}")
    public ResponseEntity<StreamingResponseBody> getVersion(@PathVariable String objectName,
                                                            @PathVariable long version,
                                                            @RequestHeader(value = HttpHeaders.RANGE, required = false)
                                                                    String rangeHeader) {
        if (rangeHeader != null && !rangeHeader.isBlank()) {
            RangeReadResult result = storageService.readVersionRange(objectName, version, rangeHeader);
            return rangeResponse(objectName, result);
        }
        RangeReadResult result = storageService.readVersionFully(objectName, version);
        return streamResponse(result.version(), result.content());
    }

    /**
     * Retire historical versions, keeping only the newest {@code retain} (current always kept).
     * Blobs are not deleted immediately; the next GC pass reclaims unreferenced ones.
     */
    @DeleteMapping("/{objectName}/versions")
    public PruneResult prune(@PathVariable String objectName,
                             @RequestParam(name = "retain", defaultValue = "1") int retain) {
        return storageService.pruneVersions(objectName, retain);
    }

    /** Delete the whole object (metadata + pointer); its blobs become GC candidates. */
    @DeleteMapping("/{objectName}")
    public ResponseEntity<Void> deleteObject(@PathVariable String objectName) {
        storageService.deleteObject(objectName);
        return ResponseEntity.noContent().build();
    }

    /**
     * Run one safe, retryable garbage-collection pass over unreferenced blobs.
     * {@code minAgeMillis} optionally overrides the un-referenced-age grace (default 60 s);
     * even with 0, quarantine and physical deletion always happen on separate passes.
     */
    @PostMapping("/gc/run")
    public GcReport runGc(@RequestParam(name = "minAgeMillis", required = false) Long minAgeMillis) {
        return minAgeMillis == null
                ? reclamationService.collectGarbage()
                : reclamationService.collectGarbage(minAgeMillis);
    }

    private ResponseEntity<StreamingResponseBody> rangeResponse(String objectName, RangeReadResult result) {
        StoredObject meta = result.version();
        StreamingResponseBody body = out -> {
            try (InputStream stream = result.content()) {
                stream.transferTo(out);
            }
        };
        String filename = URLEncoder.encode(objectName, StandardCharsets.UTF_8).replace("+", "%20");
        String contentRange = "bytes " + result.range().start() + "-" + result.range().end()
                + "/" + meta.size();
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(result.range().length()))
                .header(HttpHeaders.CONTENT_RANGE, contentRange)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Object-Version", Long.toString(meta.version()))
                .header("X-Checksum-SHA256", meta.checksum())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(body);
    }

    private ResponseEntity<StreamingResponseBody> streamResponse(StoredObject meta, InputStream in) {
        StreamingResponseBody body = out -> {
            try (InputStream stream = in) {
                stream.transferTo(out);
            }
        };
        String filename = URLEncoder.encode(meta.objectName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(meta.size()))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Object-Version", Long.toString(meta.version()))
                .header("X-Checksum-SHA256", meta.checksum())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .body(body);
    }
}
