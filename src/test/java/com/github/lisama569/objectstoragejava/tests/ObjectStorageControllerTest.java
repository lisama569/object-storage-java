package com.github.lisama569.objectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end HTTP tests through the real Spring MVC stack against a temp data directory. */
@SpringBootTest
class ObjectStorageControllerTest {

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

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private String cs(byte[] data) {
        return checksums.sha256(new ByteArrayInputStream(data));
    }

    @Test
    void fullMultipartLifecycleOverHttp() throws Exception {
        byte[] part1 = "http-part-1-".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "http-part-2-fin".getBytes(StandardCharsets.UTF_8);
        String cs1 = cs(part1);
        String cs2 = cs(part2);
        String objectChecksum = checksums.combinePartChecksums(List.of(cs1, cs2));

        // 1. init
        MvcResult initResult = mockMvc.perform(post("/api/objects/http-demo.bin/uploads"))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = json.readTree(initResult.getResponse().getContentAsByteArray()).get("uploadId").asText();

        // 2. upload parts with checksum headers
        mockMvc.perform(put("/api/objects/http-demo.bin/uploads/{id}/parts/1", uploadId)
                        .header("X-Checksum-SHA256", cs1)
                        .content(part1).contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPresent").value(false));
        mockMvc.perform(put("/api/objects/http-demo.bin/uploads/{id}/parts/2", uploadId)
                        .header("X-Checksum-SHA256", cs2)
                        .content(part2).contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isOk());

        // 3. status shows two parts (resumption support)
        mockMvc.perform(get("/api/objects/http-demo.bin/uploads/{id}", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parts.length()").value(2));

        // 4. complete
        String manifest = json.writeValueAsString(java.util.Map.of(
                "checksum", objectChecksum,
                "parts", List.of(java.util.Map.of("partNumber", 1, "checksum", cs1),
                        java.util.Map.of("partNumber", 2, "checksum", cs2))));
        mockMvc.perform(post("/api/objects/http-demo.bin/uploads/{id}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.checksum").value(objectChecksum));

        // 5. read back with version/checksum headers and exact bytes (streaming -> async dispatch)
        byte[] expected = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, expected, 0, part1.length);
        System.arraycopy(part2, 0, expected, part1.length, part2.length);
        MvcResult asyncGet = mockMvc.perform(get("/api/objects/http-demo.bin"))
                .andExpect(request -> org.junit.jupiter.api.Assertions.assertTrue(
                        request.getRequest().isAsyncStarted(), "streaming response must be async"))
                .andReturn();
        mockMvc.perform(asyncDispatch(asyncGet))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(header().string("X-Checksum-SHA256", objectChecksum))
                .andExpect(content().bytes(expected));

        // 6. metadata
        mockMvc.perform(get("/api/objects/http-demo.bin/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        System.out.printf("[http] full lifecycle ok object=http-demo.bin version=1 checksum=%s%n",
                objectChecksum);
    }

    @Test
    void duplicateUploadOverHttpReusesVersion() throws Exception {
        byte[] body = "same-same-same".getBytes(StandardCharsets.UTF_8);
        String checksum = cs(body);
        String objectCs = checksums.combinePartChecksums(List.of(checksum));
        for (int round = 1; round <= 2; round++) {
            MvcResult init = mockMvc.perform(post("/api/objects/dup-http/uploads")).andReturn();
            String uploadId = json.readTree(init.getResponse().getContentAsByteArray()).get("uploadId").asText();
            mockMvc.perform(put("/api/objects/dup-http/uploads/{id}/parts/1", uploadId)
                            .header("X-Checksum-SHA256", checksum).content(body))
                    .andExpect(status().isOk());
            String manifest = json.writeValueAsString(java.util.Map.of(
                    "checksum", objectCs,
                    "parts", List.of(java.util.Map.of("partNumber", 1, "checksum", checksum))));
            MvcResult completed = mockMvc.perform(post("/api/objects/dup-http/uploads/{id}/complete", uploadId)
                            .contentType(MediaType.APPLICATION_JSON).content(manifest))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn();
            JsonNode node = json.readTree(completed.getResponse().getContentAsByteArray());
            System.out.printf("[http] duplicate round=%d version=%d deduplicated=%s checksum=%s%n",
                    round, node.get("version").asInt(), node.get("deduplicated").asBoolean(), objectCs);
        }
    }

    @Test
    void errorReasonsAreDistinguishableOverHttp() throws Exception {
        byte[] part = "some-bytes".getBytes(StandardCharsets.UTF_8);

        // Wrong part checksum -> 422 UNPROCESSABLE_ENTITY / checksum_mismatch
        MvcResult init = mockMvc.perform(post("/api/objects/err/uploads")).andReturn();
        String uploadId = json.readTree(init.getResponse().getContentAsByteArray()).get("uploadId").asText();
        mockMvc.perform(put("/api/objects/err/uploads/{id}/parts/1", uploadId)
                        .header("X-Checksum-SHA256", "a".repeat(64)).content(part))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("checksum_mismatch"));

        // Missing upload -> 404 / not_found
        mockMvc.perform(get("/api/objects/err/uploads/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        // Manifest referencing an absent part -> 400 / invalid_manifest
        String real = cs(part);
        mockMvc.perform(put("/api/objects/err/uploads/{id}/parts/1", uploadId)
                        .header("X-Checksum-SHA256", real).content(part))
                .andExpect(status().isOk());
        String badManifest = json.writeValueAsString(java.util.Map.of(
                "checksum", checksums.combinePartChecksums(List.of(real, real)),
                "parts", List.of(java.util.Map.of("partNumber", 1, "checksum", real),
                        java.util.Map.of("partNumber", 2, "checksum", real))));
        mockMvc.perform(post("/api/objects/err/uploads/{id}/complete", uploadId)
                        .contentType(MediaType.APPLICATION_JSON).content(badManifest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_manifest"));

        // Nothing committed -> object read returns 404.
        mockMvc.perform(get("/api/objects/err"))
                .andExpect(status().isNotFound());

        System.out.println("[http] error reasons distinguished: 422 checksum_mismatch / 404 not_found / 400 invalid_manifest");
    }

    @Test
    void abortKeepsOldVersionReadableOverHttp() throws Exception {
        byte[] good = "v1-good".getBytes(StandardCharsets.UTF_8);
        String goodCs = cs(good);
        String goodObj = checksums.combinePartChecksums(List.of(goodCs));

        MvcResult init = mockMvc.perform(post("/api/objects/keep/uploads")).andReturn();
        String id = json.readTree(init.getResponse().getContentAsByteArray()).get("uploadId").asText();
        mockMvc.perform(put("/api/objects/keep/uploads/{id}/parts/1", id)
                .header("X-Checksum-SHA256", goodCs).content(good)).andExpect(status().isOk());
        String manifest = json.writeValueAsString(java.util.Map.of(
                "checksum", goodObj,
                "parts", List.of(java.util.Map.of("partNumber", 1, "checksum", goodCs))));
        mockMvc.perform(post("/api/objects/keep/uploads/{id}/complete", id)
                .contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(jsonPath("$.version").value(1));

        // Start a second upload, then abort it.
        MvcResult init2 = mockMvc.perform(post("/api/objects/keep/uploads")).andReturn();
        String id2 = json.readTree(init2.getResponse().getContentAsByteArray()).get("uploadId").asText();
        mockMvc.perform(delete("/api/objects/keep/uploads/{id}", id2))
                .andExpect(status().isNoContent());

        // Old version still served.
        MvcResult asyncKeep = mockMvc.perform(get("/api/objects/keep")).andReturn();
        mockMvc.perform(asyncDispatch(asyncKeep))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(content().bytes(good));
        System.out.printf("[http] aborted second upload; current checksum=%s remains%n", goodObj);
    }

    /** Complete a one-part upload over HTTP; returns the object-level checksum. */
    private String publishOnePart(String name, byte[] body) throws Exception {
        String checksum = cs(body);
        String objectCs = checksums.combinePartChecksums(List.of(checksum));
        MvcResult init = mockMvc.perform(post("/api/objects/{name}/uploads", name)).andReturn();
        String uploadId = json.readTree(init.getResponse().getContentAsByteArray()).get("uploadId").asText();
        mockMvc.perform(put("/api/objects/{name}/uploads/{id}/parts/1", name, uploadId)
                        .header("X-Checksum-SHA256", checksum)
                        .content(body).contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(status().isOk());
        String manifest = json.writeValueAsString(java.util.Map.of(
                "checksum", objectCs,
                "parts", List.of(java.util.Map.of("partNumber", 1, "checksum", checksum))));
        mockMvc.perform(post("/api/objects/{name}/uploads/{id}/complete", name, uploadId)
                        .contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(status().isOk());
        return objectCs;
    }

    @Test
    void rangeReadReturnsPartialContentWithContentRangeAndExactSlice() throws Exception {
        byte[] body = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8); // 20 bytes
        publishOnePart("ranged-http", body);

        // start-end
        MvcResult async = mockMvc.perform(get("/api/objects/ranged-http").header("Range", "bytes=5-9"))
                .andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 5-9/20"))
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(content().bytes("56789".getBytes(StandardCharsets.UTF_8)));

        // open-ended on a historical version
        MvcResult async2 = mockMvc.perform(
                get("/api/objects/ranged-http/versions/1").header("Range", "bytes=15-")).andReturn();
        mockMvc.perform(asyncDispatch(async2))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range", "bytes 15-19/20"))
                .andExpect(content().bytes("fghij".getBytes(StandardCharsets.UTF_8)));

        // suffix
        MvcResult async3 = mockMvc.perform(
                get("/api/objects/ranged-http").header("Range", "bytes=-4")).andReturn();
        mockMvc.perform(asyncDispatch(async3))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes("ghij".getBytes(StandardCharsets.UTF_8)));
        System.out.println("[http-range] 206 partial content + Content-Range served for all three forms");
    }

    @Test
    void badRangeReturns416WithDistinguishableError() throws Exception {
        publishOnePart("small-http", "0123456789".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/objects/small-http").header("Range", "bytes=100-200"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.error").value("invalid_range"));
        mockMvc.perform(get("/api/objects/small-http").header("Range", "bytes=9-2"))
                .andExpect(status().isRequestedRangeNotSatisfiable())
                .andExpect(jsonPath("$.error").value("invalid_range"));
        System.out.println("[http-range] malformed/unsatisfiable ranges -> 416 invalid_range");
    }

    @Test
    void versionsListingPruneAndGcWorkOverHttp() throws Exception {
        publishOnePart("lifecycle-http", "version-one-only".getBytes(StandardCharsets.UTF_8));
        publishOnePart("lifecycle-http", "version-two-only".getBytes(StandardCharsets.UTF_8));
        publishOnePart("lifecycle-http", "version-three!!".getBytes(StandardCharsets.UTF_8));

        // listing
        mockMvc.perform(get("/api/objects/lifecycle-http/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].version").value(3));

        // prune keeps newest 1
        mockMvc.perform(post("/api/objects/lifecycle-http/prune").param("retain", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retainedVersions[0]").value(3))
                .andExpect(jsonPath("$.removedVersions[0]").value(1))
                .andExpect(jsonPath("$.removedVersions[1]").value(2));

        // removed version 404s, current still served
        mockMvc.perform(get("/api/objects/lifecycle-http/versions/1"))
                .andExpect(status().isNotFound());
        MvcResult async = mockMvc.perform(get("/api/objects/lifecycle-http")).andReturn();
        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "3"))
                .andExpect(content().bytes("version-three!!".getBytes(StandardCharsets.UTF_8)));

        // GC reclaims the orphaned blobs and is repeatable
        mockMvc.perform(post("/api/objects/admin/gc").param("minAgeMillis", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").isNumber());
        mockMvc.perform(post("/api/objects/admin/gc").param("minAgeMillis", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantined").value(0))
                .andExpect(jsonPath("$.deleted").value(0));
        System.out.println("[http-lifecycle] versions list/prune/GC over HTTP; second GC deletes nothing");
    }
}
