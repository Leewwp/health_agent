package com.diet.health.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CorpusManifestTest {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    @Test
    void currentCorpusManifestMatchesReviewedBaseline() throws Exception {
        Path path = Path.of("data/manifests/current-corpus-v2.json");
        assertTrue(Files.exists(path), "manifest 必须版本化提交");
        JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        assertEquals("current-corpus-v2", root.path("corpusVersion").asText());
        assertEquals("reviewed-2026-08-29-v1", root.path("resourceVersion").asText());
        assertEquals("current-corpus-v1", root.path("supersedes").asText(), "facet 语料版本必须声明替代关系");
        assertEquals(295, root.path("eligibility").path("count").asInt());
        assertEquals("PUBLIC", root.path("eligibility").path("sourceType").asText());
        assertEquals("APPROVED", root.path("eligibility").path("reviewStatus").asText());
        assertEquals(295, root.path("meals").size());
        assertEquals("canonical-json-v1", root.path("contentHash").path("normalization").asText());
        // facet 溯源：规范词表、人工策展输入与 facetSource 必须进入 manifest（加固规格）
        assertTrue(SHA256.matcher(root.path("facets").path("sha256").asText()).matches(),
                "manifest 必须记录规范 facet 词表哈希");
        assertTrue(SHA256.matcher(root.path("manualInput").path("sha256").asText()).matches(),
                "manifest 必须记录人工策展输入哈希");
        assertEquals(13, root.path("facets").path("cuisineCount").asInt());
        assertEquals(11, root.path("facets").path("foodTypeCount").asInt());
        assertEquals(16, root.path("facetProvenance").path("cuisine").path("DATA").asInt());
        assertEquals(279, root.path("facetProvenance").path("cuisine").path("STABLE_KEY_DEMO").asInt());
        assertEquals(46, root.path("facetProvenance").path("foodType").path("DATA").asInt());
        assertEquals(249, root.path("facetProvenance").path("foodType").path("STABLE_KEY_DEMO").asInt());
        String seed = Files.readString(Path.of("src/main/resources/db/seed/reviewed_resources.sql"));
        assertEquals(295, seed.lines().filter(line -> line.contains("'PUBLIC'") && line.contains("'APPROVED'")
                && line.contains("'foodcom-recipes-and-reviews-v2'")).count());
        Set<String> ids = new HashSet<>();
        for (JsonNode meal : root.path("meals")) {
            assertTrue(ids.add(meal.path("sourceId").asText()));
            assertTrue(SHA256.matcher(meal.path("contentHash").asText()).matches());
        }
        assertEquals(295, ids.size());
        assertEquals("afbda6b7ef705634595ece80830dd707e86fa5d43bb3e7c3cc43714a50413646",
                root.path("source").path("inputSha256").asText());
        assertEquals(sha256(seed), root.path("source").path("seedSha256").asText());
        JsonNode etl = new ObjectMapper().readTree(Files.readString(Path.of("data/reports/resource_etl_report.json")));
        assertEquals(295, etl.path("meals").path("included").asInt());
        assertEquals(root.path("facets").path("sha256").asText(), etl.path("facets").path("sha256").asText(),
                "ETL 报告与 manifest 必须绑定同一份规范词表");
    }

    /** 冻结语料版本不可覆盖：current-corpus-v1 仍指向旧 seed 哈希，作为历史证据保留。 */
    @Test
    void frozenCorpusV1ManifestIsImmutable() throws Exception {
        Path path = Path.of("data/manifests/current-corpus-v1.json");
        assertTrue(Files.exists(path), "冻结 manifest current-corpus-v1 必须保留");
        JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        assertEquals("current-corpus-v1", root.path("corpusVersion").asText());
        assertEquals("reviewed-2026-08-10-v1", root.path("resourceVersion").asText());
        assertEquals(295, root.path("meals").size());
        assertEquals("1b9c34a843412c6a8b9b10a3176487964c60e440f354cfdebb6b65c017d7069d",
                root.path("source").path("seedSha256").asText(),
                "冻结 manifest 的 seed 哈希不得被改写");
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
        return result.toString();
    }
}
