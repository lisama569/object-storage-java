package com.github.lisama569.objectstoragejava.storage;

import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.ObjectSnapshot;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.VersionManifest;

import java.io.InputStream;
import java.util.List;

/**
 * 本地对象存储服务：分块写入、内容寻址去重、原子版本发布与并发控制。
 */
public interface ObjectStorageService {

    /** 初始化一个分片上传会话。 */
    UploadSessionMeta initiateUpload(String objectKey);

    /**
     * 上传一个分块。服务端重新计算 SHA-256，与客户端期望值（可选）比对，
     * 一致后以内容寻址方式落盘（相同分块只存一份）。
     */
    StoredChunk uploadPart(String sessionId, int partNumber, InputStream content, String expectedChecksum);

    /**
     * 完成上传：校验清单完整性与对象整体校验和后，原子发布一个新版本；
     * 若内容与当前版本完全一致则不产生新版本。
     */
    CompleteResult completeUpload(String sessionId, List<Integer> orderedPartNumbers,
                                  List<String> orderedChecksums, String expectedObjectChecksum);

    /** 中止上传：会话标记为 ABORTED，其临时分块被清理，已存在版本不受影响。 */
    void abortUpload(String sessionId);

    /** 单次整对象上传（内部等价于单分块的分片上传）。 */
    CompleteResult putObject(String objectKey, InputStream content, String expectedObjectChecksum);

    /** 读取对象当前版本的清单。 */
    VersionManifest currentVersion(String objectKey);

    /** 读取指定版本清单。 */
    VersionManifest getVersion(String objectKey, int version);

    /**
     * 原子读取当前版本的"清单 + 字节"快照：指针只读一次，保证字节与清单属于同一版本。
     * 若分别调用 {@link #currentVersion} 与 {@link #readCurrentBytes}，两次读取之间
     * 可能恰好发布新版本，得到"旧清单 + 新字节"的错配。
     */
    ObjectSnapshot readCurrentSnapshot(String objectKey);

    /** 读取当前版本全部字节并在读完后校验整体 SHA-256（主要供测试断言）。 */
    byte[] readCurrentBytes(String objectKey);

    /** 读取指定版本全部字节并校验整体 SHA-256（历史版本不可变，内容确定）。 */
    byte[] readVersionBytes(String objectKey, int version);
}
