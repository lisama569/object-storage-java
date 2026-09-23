package com.github.lisama569.objectstoragejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 端到端 HTTP 流程：初始化 → 分块上传（带校验头）→ 完成 → 下载校验 →
 * 重复上传去重 → 分块校验失败返回可区分错误码。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebApiIntegrationTest extends AbstractStorageTest {

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("objectstorage.root", () -> storageRoot.toString());
    }

    /** 上下文加载早于 JUnit 注入 @TempDir，故显式创建独立临时目录。 */
    private static final Path storageRoot = createRoot();

    private static Path createRoot() {
        try {
            Path dir = java.nio.file.Files.createTempDirectory("objstore-web-it-");
            dir.toFile().deleteOnExit();
            return dir.resolve("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(d.digest(data));
    }

    @Test
    void fullMultipartFlowOverHttp() throws Exception {
        String key = "web/docs/hello.txt";
        byte[] p1 = "Hello, ".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "Object Storage!".getBytes(StandardCharsets.UTF_8);
        byte[] whole = new byte[p1.length + p2.length];
        System.arraycopy(p1, 0, whole, 0, p1.length);
        System.arraycopy(p2, 0, whole, p1.length, p2.length);
        String wholeChecksum = sha256(whole);

        // 1. 初始化
        MvcResult initResult = mockMvc.perform(post("/api/objects/uploads").param("key", key))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode init = json.readTree(initResult.getResponse().getContentAsString());
        String sessionId = init.get("sessionId").asText();
        System.out.println("[web] sessionId=" + sessionId);

        // 2. 上传两个分块（带 X-Content-SHA256，服务端逐块校验）
        mockMvc.perform(put("/api/objects/uploads/" + sessionId + "/parts/1")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Content-SHA256", sha256(p1))
                        .content(p1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").value(sha256(p1)));
        mockMvc.perform(put("/api/objects/uploads/" + sessionId + "/parts/2")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Content-SHA256", sha256(p2))
                        .content(p2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").value(sha256(p2)));

        // 3. 完成（清单 + 整体校验和）
        String completeBody = json.writeValueAsString(new CompleteBody(
                java.util.List.of(new Part(1, sha256(p1)), new Part(2, sha256(p2))),
                wholeChecksum));
        MvcResult done = mockMvc.perform(post("/api/objects/uploads/" + sessionId + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deduplicated").value(false))
                .andExpect(jsonPath("$.checksum").value(wholeChecksum))
                .andReturn();
        System.out.println("[web] complete decision="
                + json.readTree(done.getResponse().getContentAsString()).get("decision").asText());

        // 4. 下载并校验响应头与内容
        MvcResult got = mockMvc.perform(get("/api/objects").param("key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Object-Version", "1"))
                .andExpect(header().string("X-Content-SHA256", wholeChecksum))
                .andReturn();
        byte[] downloaded = got.getResponse().getContentAsByteArray();
        org.junit.jupiter.api.Assertions.assertArrayEquals(whole, downloaded);
        org.junit.jupiter.api.Assertions.assertEquals(wholeChecksum, sha256(downloaded));
        System.out.println("[web] downloaded version=1 checksum=" + wholeChecksum);

        // 5. info
        mockMvc.perform(get("/api/objects/info").param("key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion").value(1))
                .andExpect(jsonPath("$.partCount").value(2));
    }

    @Test
    void duplicateUploadIsDeduplicatedOverHttp() throws Exception {
        String key = "web/dup.txt";
        byte[] content = "deduplicate-me-please".getBytes(StandardCharsets.UTF_8);
        String checksum = sha256(content);

        putOnce(key, content, checksum, 1);
        MvcResult second = putOnce(key, content, checksum, 1);
        JsonNode body = json.readTree(second.getResponse().getContentAsString());
        org.junit.jupiter.api.Assertions.assertTrue(body.get("deduplicated").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals(1, body.get("version").asInt());
        System.out.println("[web] duplicate upload decision=" + body.get("decision").asText());
    }

    @Test
    void badChunkChecksumReturnsDistinctError() throws Exception {
        String key = "web/bad.txt";
        MvcResult init = mockMvc.perform(post("/api/objects/uploads").param("key", key))
                .andExpect(status().isOk()).andReturn();
        String sessionId = json.readTree(init.getResponse().getContentAsString()).get("sessionId").asText();

        mockMvc.perform(put("/api/objects/uploads/" + sessionId + "/parts/1")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Content-SHA256", "00".repeat(32))
                        .content("actual-bytes"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHUNK_CHECKSUM_MISMATCH"));
        System.out.println("[web] bad chunk rejected with CHUNK_CHECKSUM_MISMATCH");
    }

    @Test
    void objectNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/api/objects").param("key", "web/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"));
    }

    private MvcResult putOnce(String key, byte[] content, String checksum, int expectedVersion)
            throws Exception {
        return mockMvc.perform(put("/api/objects").param("key", key)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Content-SHA256", checksum)
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(expectedVersion))
                .andReturn();
    }

    private record Part(int partNumber, String checksum) {
    }

    private record CompleteBody(java.util.List<Part> parts, String checksum) {
    }
}
