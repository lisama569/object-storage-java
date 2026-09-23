package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.exception.StorageException;
import com.github.lisama569.objectstoragejava.model.CompleteResult;
import com.github.lisama569.objectstoragejava.model.StoredChunk;
import com.github.lisama569.objectstoragejava.model.UploadSessionMeta;
import com.github.lisama569.objectstoragejava.model.VersionManifest;
import com.github.lisama569.objectstoragejava.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多个写入者并发操作同一对象。
 *
 * <p>确定性判据（可复现）：
 * <ol>
 *   <li>N 个写入者写入 N 份互不相同的内容，全部成功后当前版本号恰为 N；</li>
 *   <li>发布过程中观测到的版本号序列单调不减、绝不回退；</li>
 *   <li>每个版本号 1..N 的内容都与某个写入者的内容一一对应（无覆盖丢失）；</li>
 *   <li>并发读取线程每次拿到的字节都能通过该版本清单的 SHA-256 校验（服务端已强校验），
 *       绝不读到写一半的内容。</li>
 * </ol>
 */
class ConcurrentWriteTest extends AbstractStorageTest {

    @TempDir
    Path tempDir;

    private static final int WRITERS = 8;
    private static final int READERS = 4;

    @Test
    void concurrentWritersProduceMonotonicCompleteVersions() throws Exception {
        ObjectStorageService svc = newService(tempDir.toString());
        String key = "concurrent/object.bin";

        // 每个写入者的内容互不相同（且内部由多个分块组成）
        byte[][] contents = new byte[WRITERS][];
        for (int i = 0; i < WRITERS; i++) {
            contents[i] = buildDistinctContent(i, 50_000 + i * 37, (byte) ('A' + i));
        }
        String[] expectedChecksums = new String[WRITERS];
        for (int i = 0; i < WRITERS; i++) {
            expectedChecksums[i] = sha256Hex(contents[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(WRITERS + READERS);
        CyclicBarrier start = new CyclicBarrier(WRITERS + READERS);
        Map<Integer, String> versionToChecksum = new ConcurrentHashMap<>();
        AtomicInteger tornReads = new AtomicInteger();
        // 每个读取线程独立的观测轨迹：版本号单调性必须在单个读取者视角内成立。
        // 不能用跨线程共享轨迹——各读取线程结束时刻不同，追加顺序不构成全局时间序，
        // 否则会把"A 线程晚到的旧观测排在 B 线程新观测之后"误判为版本回退。
        List<List<Integer>> readerTraces = Collections.synchronizedList(new ArrayList<>());
        // 全局观测：元素为 [读取开始时间纳秒, 版本号, 读取者 id]
        List<long[]> globalTrace = Collections.synchronizedList(new ArrayList<>());

        // 先为每个写入者建好会话并传好分块（把竞争集中在"发布"这一步）
        List<String> sessionIds = new ArrayList<>();
        List<List<UploadedPart>> sessionParts = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            UploadSessionMeta session = svc.initiateUpload(key);
            sessionIds.add(session.sessionId());
            List<UploadedPart> parts = stageParts(svc, session.sessionId(), contents[i], 4096);
            sessionParts.add(parts);
        }

        List<Future<?>> futures = new ArrayList<>();

        // 写入者：同时发布
        for (int i = 0; i < WRITERS; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    List<Integer> nums = sessionParts.get(idx).stream().map(UploadedPart::number).toList();
                    List<String> sums = sessionParts.get(idx).stream().map(UploadedPart::checksum).toList();
                    CompleteResult result = svc.completeUpload(
                            sessionIds.get(idx), nums, sums, expectedChecksums[idx]);
                    versionToChecksum.put(result.manifest().version(), result.manifest().checksum());
                    System.out.printf("[concurrent] writer-%d published version %d (checksum=%s, dedup=%s)%n",
                            idx, result.manifest().version(), result.manifest().checksum(),
                            result.deduplicated());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        // 读取者：在发布窗口内反复读取当前版本，校验返回字节完整（服务端按清单强校验）
        for (int r = 0; r < READERS; r++) {
            final int readerId = r;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    List<Integer> trace = new ArrayList<>();
                    readerTraces.add(trace);
                    long deadline = System.currentTimeMillis() + 3000;
                    while (System.currentTimeMillis() < deadline) {
                        try {
                            // 记录读区间 [startNanos, endNanos]：只有区间严格不重叠时
                            // （前一个读完之后后一个才开始）才存在版本先后约束。
                            long tStart = System.nanoTime();
                            // 单次指针读取的一致快照：清单与字节同版本
                            var snapshot = svc.readCurrentSnapshot(key);
                            long tEnd = System.nanoTime();
                            VersionManifest manifest = snapshot.manifest();
                            byte[] bytes = snapshot.bytes(); // 读时整体 SHA-256 校验
                            String actual = sha256Hex(bytes);
                            if (!actual.equals(manifest.checksum())) {
                                tornReads.incrementAndGet();
                            }
                            trace.add(manifest.version());
                            globalTrace.add(new long[]{tStart, tEnd, manifest.version(), readerId});
                        } catch (StorageException notYet) {
                            // 首个版本发布前对象尚不存在，属正常窗口
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // 判据 1：最终版本号恰为 WRITERS
        VersionManifest finalVersion = svc.currentVersion(key);
        System.out.println("[concurrent] FINAL current version=" + finalVersion.version()
                + " checksum=" + finalVersion.checksum());
        assertEquals(WRITERS, finalVersion.version(),
                "final version must equal number of distinct successful writers");

        // 判据 2：版本号集合恰为 1..N，且每个版本对应唯一内容，无覆盖、无回退
        Set<Integer> versions = versionToChecksum.keySet();
        for (int v = 1; v <= WRITERS; v++) {
            assertTrue(versions.contains(v), "missing version " + v);
        }
        assertEquals(WRITERS, new HashSet<>(versionToChecksum.values()).size(),
                "each version must carry a distinct checksum");

        // 判据 3：单个读取者视角内观测版本号单调不减；
        //         再按"读取开始时间"排序全局观测，版本号同样不得回退（检测真实指针回退）
        int totalObservations = 0;
        for (List<Integer> trace : readerTraces) {
            int last = 0;
            for (int observed : trace) {
                assertTrue(observed >= last,
                        "per-reader version went backwards: " + last + " -> " + observed);
                last = observed;
                totalObservations++;
            }
        }
        // 判据 3b：按读区间的真实先后关系校验线性一致性（线性算法）。
        // 按开始时间排序；用一个最小堆按结束时间弹出"已在当前读开始前结束"的读，
        // 维护这些更早读的最大版本 maxCompletedVersion。当前读版本不得小于它。
        // 并发重叠的读区间之间不存在先后约束，不会被误判。
        List<long[]> byStart = new ArrayList<>(globalTrace);
        byStart.sort(Comparator.comparingLong((long[] o) -> o[0]).thenComparingLong(o -> o[1]));
        PriorityQueue<long[]> byEnd = new PriorityQueue<>(Comparator.comparingLong(o -> o[1]));
        int maxCompletedVersion = 0;
        int strictOrderViolations = 0;
        for (long[] cur : byStart) {
            while (!byEnd.isEmpty() && byEnd.peek()[1] <= cur[0]) {
                maxCompletedVersion = Math.max(maxCompletedVersion, (int) byEnd.poll()[2]);
            }
            if (cur[2] < maxCompletedVersion) {
                strictOrderViolations++;
                System.out.printf("[concurrent] VIOLATION read(v%d) but a strictly earlier read saw v%d%n",
                        cur[2], maxCompletedVersion);
            }
            byEnd.add(cur);
        }
        assertEquals(0, strictOrderViolations,
                "a read fully completed earlier must not observe a higher version than a later read");

        // 判据 4：无任何撕裂读取
        assertEquals(0, tornReads.get(), "no reader must ever see torn content");
        System.out.println("[concurrent] reader observations=" + totalObservations
                + ", tornReads=" + tornReads.get());

        // 判据 5：每个版本都可独立读取，内容确定且可复现
        for (int v = 1; v <= WRITERS; v++) {
            byte[] bytes = svc.readVersionBytes(key, v);
            assertEquals(versionToChecksum.get(v), sha256Hex(bytes),
                    "version " + v + " bytes must match its manifest checksum");
        }
        System.out.println("[concurrent] all " + WRITERS + " versions verified individually");
    }

    /** 分块上传但不完成，返回分块引用清单。 */
    private List<UploadedPart> stageParts(ObjectStorageService svc, String sessionId,
                                          byte[] content, int chunkSize) {
        List<UploadedPart> parts = new ArrayList<>();
        int number = 1;
        for (int offset = 0; offset < content.length; offset += chunkSize, number++) {
            int end = Math.min(offset + chunkSize, content.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(content, offset, chunk, 0, chunk.length);
            StoredChunk stored = svc.uploadPart(sessionId, number,
                    new ByteArrayInputStream(chunk), sha256Hex(chunk));
            parts.add(new UploadedPart(number, stored.checksum()));
        }
        return parts;
    }

    private static byte[] buildDistinctContent(int writerId, int length, byte fill) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (fill + (i % 5));
        }
        byte[] header = ("writer=" + writerId + ";").getBytes();
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }
}
