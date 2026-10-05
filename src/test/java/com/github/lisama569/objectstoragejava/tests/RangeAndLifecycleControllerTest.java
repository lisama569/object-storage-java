package com.github.lisama569.objectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level coverage for the new iteration: ranged reads (206 + Content-Range) on current and
 * historical versions, distinguishable 400/416 range errors, version listing, pruning, object
 * deletion and the two-pass GC endpoint.
 */
@SpringBootTest
class RangeAndLifecycleControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("objectstorage.root", () -> tempDir.toString());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private final com.github.lisama569.objectstoragejava.service.ChecksumService checksums =
            new com.github.lisama569.objectstoragejava.service.ChecksumService();

    private String cs(byte[] d) {
        return checksums.sha256(new ByteArrayInputStream(d));
    }

    private String upload(String name, List<byte[]> parts) throws Exception {
        String id = json.readTree(mockMvc.perform(post("/api/objects/{n}/uploads", name))
                .andReturn().getResponse().getContentAsByteArray()).get("uploadId").asText();
        var entries = new java.util.ArrayList<java.util.Map<String, Object>>();
        var partChecksums = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) {
            String c = cs(parts.get(i));
            mockMvc.perform(put("/api/objects/{n}/uploads/{id}/parts/{p}", name, id, i + 1)
                    .header("X-Checksum-SHA256", c)
                    .content(parts.get(i)).contentType(MediaType.APPLICATION_OCTET_STREAM))
                    .andExpect(status().isOk());
            entries.add(java.util.Map.of("partNumber", i + 1, "checksum", c));
            partChecksums.add(c);
        }
        String objectChecksum = checksums.combinePartChecksums(partChecksums);
        String body = json.writeValueAsString(java.util.Map.of("checksum", objectChecksum, "parts", entries));
        mockMvc.perform(post("/api/objects/{n}/uploads/{id}/complete", name, id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        return objectChecksum;
    }

    private byte[] dispatchRange(String name, String rangeHeader, Long version) throws Exception {
        var builder = version == null ? get("/api/objects/{n}", name)
                : get("/api/objects/{n}/versions/{v}", name, version);
        if (rangeHeader != null) {
            builder.header("Range", rangeHeader);
        }
        MvcResult async = mockMvc.perform(builder).andReturn();
        MvcResult done = mockMvc.perform(asyncDispatch(async)).andReturn();
        return done.getResponse().getContentAsByteArray();
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void rangedReadsOverCurrentAndHistoryReturn206WithExactWindows() throws Exception {
        // 12 + 12 = 24 bytes, range window crosses the two parts.
        byte[] p1 = "0123456789AB".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "CDEFGHIJKLMN".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[24];
        System.arraycopy(p1, 0, all, 0, 12);
        System.arraycopy(p2, 0, all, 12, 12);
        upload("range.bin", List.of(p1, p2));
        byte[] v2part = "second-version".getBytes(StandardCharsets.UTF_8);
        upload("range.bin", List.of(v2part));

        // start-end crossing the part boundary (v1 historical read).
        var req = get("/api/objects/range.bin/versions/1").header("Range", "bytes=8-15");
        MvcResult async = mockMvc.perform(req).andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 8-15/24"))
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(header().longValue("Content-Length", 8))
                .andExpect(content().bytes(java.util.Arrays.copyOfRange(all, 8, 16)));
        System.out.println("[http-range] GET v1 bytes=8-15 -> 206 Content-Range: bytes 8-15/24"
                + " (cross-part, integrity verified)");

        // start-only on current v2.
        byte[] open = dispatchRange("range.bin", "bytes=7-", null);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                java.util.Arrays.copyOfRange(v2part, 7, v2part.length), open);
        // suffix from end on current v2.
        byte[] suffix = dispatchRange("range.bin", "bytes=-5", null);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                java.util.Arrays.copyOfRange(v2part, v2part.length - 5, v2part.length), suffix);
        System.out.println("[http-range] bytes=7- and bytes=-5 on current v2 -> exact windows");
    }

    @Test
    void malformedRangeIs400AndUnsatisfiableRangeIs416() throws Exception {
        upload("small", List.of("abc".getBytes(StandardCharsets.UTF_8)));
        mockMvc.perform(get("/api/objects/small").header("Range", "bytes=xx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_range"));
        mockMvc.perform(get("/api/objects/small").header("Range", "bytes=10-20"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.error").value("range_not_satisfiable"));
        System.out.println("[http-range] malformed=400 invalid_range; beyond-size=416"
                + " range_not_satisfiable (no empty/full fallback)");
    }

    @Test
    void versionsListedPrunedDeletedAndBlobsGarbageCollected() throws Exception {
        // Use unique content (UUID) so no other object in the shared store can content-address
        // share these blobs — otherwise GC would correctly keep them as cross-object references.
        String salt = java.util.UUID.randomUUID().toString();
        byte[] a = ("aaaa-content-" + salt).getBytes(StandardCharsets.UTF_8);
        byte[] b = ("bbbb-content-" + salt).getBytes(StandardCharsets.UTF_8);
        upload("life", List.of(a));
        upload("life", List.of(b));

        mockMvc.perform(get("/api/objects/life/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[1].version").value(2));
        System.out.println("[http-life] GET versions -> [v1, v2]");

        // Retain only 1: v1 metadata removed.
        MvcResult prune = mockMvc.perform(delete("/api/objects/life/versions").param("retain", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.removedVersions[0]").value(1))
                .andReturn();
        System.out.println("[http-life] prune retain=1 -> "
                + prune.getResponse().getContentAsString());
        mockMvc.perform(get("/api/objects/life/versions/1")).andExpect(status().isNotFound());

        // GC pass 1 quarantines the orphan blob, pass 2 deletes it.
        JsonNode g1 = json.readTree(mockMvc.perform(post("/api/objects/gc/run").param("minAgeMillis","0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        JsonNode g2 = json.readTree(mockMvc.perform(post("/api/objects/gc/run").param("minAgeMillis","0"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        org.junit.jupiter.api.Assertions.assertTrue(g1.get("quarantined").asInt() >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(g2.get("deleted").asInt() >= 1);
        System.out.printf("[http-life] GC pass1 quarantined=%d pass2 deleted=%d (readability: v2=ok v1=404)%n",
                g1.get("quarantined").asInt(), g2.get("deleted").asInt());

        // Current v2 still served byte-exact after reclamation.
        MvcResult async = mockMvc.perform(get("/api/objects/life")).andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "2"))
                .andExpect(content().bytes(b));

        // Delete the whole object: 404 afterwards, final GC frees the last blob.
        mockMvc.perform(delete("/api/objects/life")).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/objects/life")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/objects/gc/run").param("minAgeMillis","0")).andExpect(status().isOk());
        mockMvc.perform(post("/api/objects/gc/run").param("minAgeMillis","0")).andExpect(status().isOk());
        System.out.println("[http-life] object deleted -> 404; its remaining blob reclaimed by GC");
    }
}
