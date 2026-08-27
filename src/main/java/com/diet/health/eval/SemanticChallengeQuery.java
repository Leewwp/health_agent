package com.diet.health.eval;

import java.util.List;
import java.util.Map;

public record SemanticChallengeQuery(String id, String text, Map<String, List<String>> slots,
                                     List<String> allergens, List<String> excludeSourceIds,
                                     List<String> expectedTop5) {
}
