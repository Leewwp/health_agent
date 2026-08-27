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
        Path path = Path.of("data/manifests/current-corpus-v1.json");
        assertTrue(Files.exists(path), "manifest 必须版本化提交");
        JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        assertEquals("current-corpus-v1", root.path("corpusVersion").asText());
        assertEquals("reviewed-2026-08-10-v1", root.path("resourceVersion").asText());
        assertEquals(295, root.path("eligibility").path("count").asInt());
        assertEquals("PUBLIC", root.path("eligibility").path("sourceType").asText());
        assertEquals("APPROVED", root.path("eligibility").path("reviewStatus").asText());
        assertEquals(295, root.path("meals").size());
        assertEquals("canonical-json-v1", root.path("contentHash").path("normalization").asText());
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
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte valueByte : digest) result.append(String.format("%02x", valueByte));
        return result.toString();
    }
}
