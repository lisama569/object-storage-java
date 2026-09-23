package com.github.lisama569.objectstoragejava.web;

import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.ManifestPart;
import com.github.lisama569.objectstoragejava.model.ObjectSnapshot;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import com.github.lisama569.objectstoragejava.web.dto.CompleteUploadRequest;
import com.github.lisama569.objectstoragejava.web.dto.CompleteUploadResponse;
import com.github.lisama569.objectstoragejava.web.dto.InitiateUploadResponse;
import com.github.lisama569.objectstoragejava.web.dto.ObjectInfoResponse;
import com.github.lisama569.objectstoragejava.web.dto.PartRefDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储 HTTP 入口。对象键通过查询参数 {@code key} 传递（可含 "/"、"." 等字符，
 * 服务端再做安全校验与十六进制编码落盘）。
 *
 * <ul>
 *   <li>POST   /api/objects/uploads?key=...                      初始化分片上传</li>
 *   <li>PUT    /api/objects/uploads/{sid}/parts/{n}              上传分块（Header X-Content-SHA256）</li>
 *   <li>POST   /api/objects/uploads/{sid}/complete               完成上传（清单 + 整体校验和）</li>
 *   <li>DELETE /api/objects/uploads/{sid}                        中止上传</li>
 *   <li>PUT    /api/objects?key=...                              单次整对象上传</li>
 *   <li>GET    /api/objects?key=...&amp;version=N                下载（默认当前版本）</li>
 *   <li>GET    /api/objects/info?key=...&amp;version=N           版本信息</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/objects")
public class ObjectStorageController {

    private final ObjectStorageService service;

    public ObjectStorageController(ObjectStorageService service) {
        this.service = service;
    }

    @PostMapping("/uploads")
    public ResponseEntity<InitiateUploadResponse> initiate(@RequestParam("key") String objectKey) {
        UploadSessionMeta meta = service.initiateUpload(objectKey);
        return ResponseEntity.ok(new InitiateUploadResponse(
                meta.sessionId(), meta.objectKey(), meta.state().name(), meta.createdAt()));
    }

    @PutMapping(value = "/uploads/{sessionId}/parts/{partNumber}",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StoredChunk> uploadPart(@PathVariable String sessionId,
                                                  @PathVariable int partNumber,
                                                  @RequestHeader(value = "X-Content-SHA256", required = false)
                                                  String expectedChecksum,
                                                  InputStream body) {
        StoredChunk stored = service.uploadPart(sessionId, partNumber, body, expectedChecksum);
        return ResponseEntity.ok(stored);
    }

    @PostMapping("/uploads/{sessionId}/complete")
    public ResponseEntity<CompleteUploadResponse> complete(@PathVariable String sessionId,
                                                           @RequestBody CompleteUploadRequest request) {
        List<Integer> numbers = new ArrayList<>();
        List<String> checksums = new ArrayList<>();
        if (request.parts() != null) {
            for (PartRefDto part : request.parts()) {
                numbers.add(part.partNumber());
                checksums.add(part.checksum());
            }
        }
        CompleteResult result = service.completeUpload(
                sessionId, numbers, checksums, request.checksum());
        return ResponseEntity.ok(toResponse(result));
    }

    @DeleteMapping("/uploads/{sessionId}")
    public ResponseEntity<Void> abort(@PathVariable String sessionId) {
        service.abortUpload(sessionId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<CompleteUploadResponse> put(@RequestParam("key") String objectKey,
                                                      @RequestHeader(value = "X-Content-SHA256", required = false)
                                                      String expectedChecksum,
                                                      InputStream body) {
        CompleteResult result = service.putObject(objectKey, body, expectedChecksum);
        return ResponseEntity.ok(toResponse(result));
    }

    @GetMapping
    public ResponseEntity<byte[]> getObject(@RequestParam("key") String objectKey,
                                            @RequestParam(value = "version", required = false) Integer version) {
        // 读取时按版本清单拼装 blob 并整体校验 SHA-256：任何不匹配都返回可区分错误，
        // 绝不把损坏字节发给客户端。当前版本走"单次指针读取"快照，保证清单与字节同版本。
        final VersionManifest manifest;
        final byte[] body;
        if (version == null) {
            ObjectSnapshot snapshot = service.readCurrentSnapshot(objectKey);
            manifest = snapshot.manifest();
            body = snapshot.bytes();
        } else {
            manifest = service.getVersion(objectKey, version);
            body = service.readVersionBytes(objectKey, version);
        }
        String fileName = URLEncoder.encode(objectKey, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(manifest.size()))
                .header("X-Object-Key", objectKey)
                .header("X-Object-Version", Integer.toString(manifest.version()))
                .header("X-Content-SHA256", manifest.checksum())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + fileName)
                .body(body);
    }

    @GetMapping("/info")
    public ResponseEntity<ObjectInfoResponse> info(@RequestParam("key") String objectKey,
                                                   @RequestParam(value = "version", required = false) Integer version) {
        VersionManifest manifest = version == null
                ? service.currentVersion(objectKey)
                : service.getVersion(objectKey, version);
        return ResponseEntity.ok(new ObjectInfoResponse(
                manifest.objectKey(), manifest.version(), manifest.checksum(),
                manifest.size(), manifest.parts().size(), manifest.createdAt()));
    }

    private CompleteUploadResponse toResponse(CompleteResult result) {
        VersionManifest m = result.manifest();
        List<PartRefDto> parts = new ArrayList<>();
        for (ManifestPart part : m.parts()) {
            parts.add(new PartRefDto(part.partNumber(), part.checksum()));
        }
        return new CompleteUploadResponse(m.objectKey(), m.version(), m.checksum(), m.size(),
                result.deduplicated(), result.decision(), parts);
    }
}
