package com.diet.health.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class SemanticChallengeSetTest {
    @Test
    void semanticChallengeV1HasTwelveIndependentQueries() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/diet/eval/semantic_challenge_v1.json")) {
            assertNotNull(in);
            JsonNode root = mapper.readTree(in);
            assertEquals("semantic-challenge-v1", root.path("challengeSetVersion").asText());
            assertEquals("COMPLETE", root.path("annotationStatus").asText());
            assertEquals(12, root.path("queries").size());
            var ids = new HashSet<String>();
            for (JsonNode query : root.path("queries")) {
                assertTrue(ids.add(query.path("id").asText()));
                assertFalse(query.path("text").asText().isBlank());
                assertFalse(query.path("rationale").asText().isBlank());
                assertEquals(5, query.path("expectedTop5").size());
                var expectedIds = new HashSet<String>();
                for (JsonNode expected : query.path("expectedTop5")) {
                    assertTrue(expected.asText().matches("\\d+"),
                            query.path("id").asText() + " expectedTop5 必须使用数字 sourceId");
                    assertTrue(expectedIds.add(expected.asText()),
                            query.path("id").asText() + " expectedTop5 不得重复 sourceId");
                }
            }
            assertEquals(12, ids.size());
        }
    }
}
