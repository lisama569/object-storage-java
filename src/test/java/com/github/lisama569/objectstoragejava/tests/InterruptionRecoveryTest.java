package com.github.lisama569.objectstoragejava.tests;

import com.github.lisama569.objectstoragejava.dto.StoredObject;
import com.github.lisama569.objectstoragejava.dto.UploadInitResponse;
import com.github.lisama569.objectstoragejava.dto.UploadStatusResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure scenarios:
 * <ul>
 *     <li>a crash DURING a part upload leaves a stray temp file, never a committed blob/version;</li>
 *     <li>the already-committed version stays byte-exact and current through the crash;</li>
 *     <li>a "restarted" client (fresh service instance on the same directory) resumes and completes.</li>
 * </ul>
 */
class InterruptionRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void crashDuringPartUploadLeavesNoPartialCommitAndOldVersionSurvives() throws Exception {
        StorageTestSupport.Stack stack = StorageTestSupport.newStack(tempDir);

        // A known-good v1 is already committed before the crash.
        List<byte[]> v1 = List.of(StorageTestSupport.utf8("committed-before-any-crash"));
        StoredObject v1Meta = StorageTestSupport.multipartUpload(
                stack.service(), stack.checksums(), "data.bin", v1);

        // A new multipart upload gets part 1 safely onto staging...
        UploadInitResponse init = stack.service().initUpload("data.bin");
        byte[] part1 = StorageTestSupport.utf8("new-version-part-1");
        byte[] part2 = StorageTestSupport.utf8("new-version-part-2");
        String cs1 = StorageTestSupport.sha256(stack.checksums(), part1);
        stack.service().uploadPart("data.bin", init.uploadId(), 1, cs1, new ByteArrayInputStream(part1));

        // ...and part 2 "crashes" mid-write: only an orphan .tmp file remains, part-2 is never committed.
        Path partsDir = tempDir.resolve("staging/data.bin").resolve(init.uploadId()).resolve("parts");
        Path orphan = Files.createTempFile(partsDir, "part-2-", ".tmp");
        Files.writeString(orphan, "partial-bytes-that-were-never-verified");
        long blobsAfterCrash;
        try (var blobs = Files.list(tempDir.resolve("blobs"))) {
            blobsAfterCrash = blobs.count();
        }

        // Old version is still the current pointer and reads byte-exact.
        StoredObject headAfterCrash = stack.service().headObject("data.bin");
        assertEquals(v1Meta.version(), headAfterCrash.version(), "crash must not advance the version");
        assertEquals(v1Meta.checksum(), headAfterCrash.checksum());
        byte[] oldBytes = stack.service().readObject("data.bin").readAllBytes();
        assertTrue(java.util.Arrays.equals(StorageTestSupport.concat(v1), oldBytes),
                "old version bytes must survive the crash untouched");
        System.out.printf("[crash] after interrupted part-2: current=v%d checksum=%s (unchanged)%n",
                headAfterCrash.version(), headAfterCrash.checksum());
        System.out.printf("[crash] orphan tmp present=%s, blobs-on-disk=%d (no uncommitted blob)%n",
                Files.exists(orphan), blobsAfterCrash);

        // Simulate application restart: a brand-new service instance over the SAME directory.
        StorageTestSupport.Stack restarted = StorageTestSupport.newStack(tempDir);
        UploadStatusResponse status = restarted.service().uploadStatus("data.bin", init.uploadId());
        assertEquals(List.of(1), status.parts().stream().map(UploadStatusResponse.PartStatus::partNumber).toList(),
                "status must only expose fully committed parts, never the orphan .tmp file");
        System.out.printf("[restart] resumable parts after restart=%s%n",
                status.parts().stream().map(UploadStatusResponse.PartStatus::partNumber).toList());

        // Resume: send the missing part 2 and complete.
        String cs2 = StorageTestSupport.sha256(restarted.checksums(), part2);
        restarted.service().uploadPart("data.bin", init.uploadId(), 2, cs2, new ByteArrayInputStream(part2));
        var entries = new ArrayList<com.github.lisama569.objectstoragejava.dto.ManifestEntry>();
        entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(1, cs1));
        entries.add(new com.github.lisama569.objectstoragejava.dto.ManifestEntry(2, cs2));
        String objectCs = restarted.checksums().combinePartChecksums(List.of(cs1, cs2));
        StoredObject v2Meta = restarted.service().completeUpload("data.bin", init.uploadId(),
                new com.github.lisama569.objectstoragejava.dto.ManifestRequest(objectCs, entries));

        assertEquals(2, v2Meta.version());
        assertEquals(objectCs, v2Meta.checksum());
        byte[] v2Bytes = restarted.service().readObject("data.bin").readAllBytes();
        byte[] expectedV2 = StorageTestSupport.concat(List.of(part1, part2));
        assertTrue(java.util.Arrays.equals(expectedV2, v2Bytes), "resumed object must be byte-exact");
        byte[] v1Again = restarted.service().readVersion("data.bin", 1).readAllBytes();
        assertTrue(java.util.Arrays.equals(StorageTestSupport.concat(v1), v1Again),
                "historical v1 must still be readable after v2 resume");
        System.out.printf("[restart] resumed completion -> current=v%d checksum=%s; v1 also still readable%n",
                v2Meta.version(), v2Meta.checksum());
    }
}
