package com.github.lisama569.objectstoragejava.tests;

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
import java.util.Map;

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
 * End-to-end HTTP coverage of the new capabilities: ranged downloads (206), historical reads,
 * version listing/retirement (409/404), and the maintenance GC endpoint.
 */
@SpringBootTest
class ExtendedHttpFeaturesTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("objectstorage.root", () -> tempDir.toString());
        // Disable the grace window so GC reclaims immediately in this test.
        registry.add("objectstorage.gc.min-age", () -> "0s");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();
    private final com.github.lisama569.objectstoragejava.service.ChecksumService checksums =
            new com.github.lisama569.objectstoragejava.service.ChecksumService();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String uploadAndComplete(String name, List<byte[]> parts) throws Exception {
        MvcResult init = mockMvc.perform(post("/api/objects/" + name + "/uploads")).andReturn();
        String uploadId = json.readTree(init.getResponse().getContentAsByteArray()).get("uploadId").asText();
        var entryList = new java.util.ArrayList<Map<String, Object>>();
        var partCs = new java.util.ArrayList<String>();
        for (int i = 0; i < parts.size(); i++) {
            String c = checksums.sha256(new ByteArrayInputStream(parts.get(i)));
            mockMvc.perform(put("/api/objects/" + name + "/uploads/{id}/parts/{n}", uploadId, i + 1)
                    .header("X-Checksum-SHA256", c)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(parts.get(i))).andExpect(status().isOk());
            entryList.add(Map.of("partNumber", i + 1, "checksum", c));
            partCs.add(c);
        }
        String objectCs = checksums.combinePartChecksums(partCs);
        String body = json.writeValueAsString(Map.of("checksum", objectCs, "parts", entryList));
        mockMvc.perform(post("/api/objects/" + name + "/uploads/{id}/complete", uploadId)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        return objectCs;
    }

    @Test
    void rangedDownloadReturns206WithContentRangeAndExactBytes() throws Exception {
        byte[] p1 = "0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "abcdefghij".getBytes(StandardCharsets.UTF_8);
        byte[] p3 = "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        uploadAndComplete("ranged.bin", List.of(p1, p2, p3));

        // Cross-chunk range bytes=7-22 over the current version.
        MvcResult async = mockMvc.perform(get("/api/objects/ranged.bin").header("Range", "bytes=7-22"))
                .andReturn();
        byte[] expected = "789abcdefghijABC".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 7-22/30"))
                .andExpect(header().longValue("Content-Length", 16))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(content().bytes(expected));

        // Same range works on a historical version endpoint after v2 is published.
        uploadAndComplete("ranged.bin", List.of("second-version-bytes-here!!!".getBytes(StandardCharsets.UTF_8)));
        MvcResult hist = mockMvc.perform(get("/api/objects/ranged.bin/versions/1")
                .header("Range", "bytes=0-4")).andReturn();
        mockMvc.perform(asyncDispatch(hist))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(header().string("Content-Range", "bytes 0-4/30"))
                .andExpect(content().bytes("01234".getBytes(StandardCharsets.UTF_8)));

        // Unsatisfiable -> 416 with Content-Range bytes */30.
        mockMvc.perform(get("/api/objects/ranged.bin/versions/1").header("Range", "bytes=999-"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(header().string("Content-Range", "bytes */30"))
                .andExpect(jsonPath("$.error").value("range_not_satisfiable"));
        System.out.println("[http-range] 206 cross-chunk + historical range + 416 verified");
    }

    @Test
    void retireVersionAndRunGcOverHttp() throws Exception {
        byte[] v1 = "v1-only-bytes!!".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "v2-totally-other".getBytes(StandardCharsets.UTF_8);
        uploadAndComplete("life.bin", List.of(v1));
        uploadAndComplete("life.bin", List.of(v2));

        // Listing reports CURRENT/LIVE.
        mockMvc.perform(get("/api/objects/life.bin/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].state").value("LIVE"))
                .andExpect(jsonPath("$[1].state").value("CURRENT"));

        // Retiring current is a conflict.
        mockMvc.perform(delete("/api/objects/life.bin/versions/2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("version_conflict"));

        // Retire v1, then it is 404 and listed RETIRED.
        mockMvc.perform(delete("/api/objects/life.bin/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RETIRED"));
        mockMvc.perform(get("/api/objects/life.bin/versions/1"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/objects/life.bin/versions"))
                .andExpect(jsonPath("$[0].state").value("RETIRED"));

        // GC endpoint reclaims v1's exclusive blob; v2 stays served.
        mockMvc.perform(post("/api/maintenance/gc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedBlobs").value(1))
                .andExpect(jsonPath("$.deletionFailures").value(0));
        MvcResult cur = mockMvc.perform(get("/api/objects/life.bin")).andReturn();
        mockMvc.perform(asyncDispatch(cur))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "2"))
                .andExpect(content().bytes(v2));
        System.out.println("[http-retire-gc] retire v1 -> 404/RETIRED; GC deletedBlobs=1; current v2 still served");
    }
}
