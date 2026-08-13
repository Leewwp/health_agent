package com.diet.health.evalv2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * fallback 互斥主分类（契约 §3）：NONE / INTENT_RULE_FALLBACK / RESPONSE_TEMPLATE_FALLBACK /
 * EMBEDDING_UNAVAILABLE / VECTOR_STORE_UNAVAILABLE / NO_VECTOR_HITS / REQUEST_FAILED。
 * <p>
 * 同一 Trace 多原因时主类取最严重项（REQUEST_FAILED 最严重），明细保留全部原因；
 * 风险阻断、正常澄清和无候选不是 fallback。
 */
public final class FallbackClassifier {

    public static final String NONE = "NONE";
    public static final String INTENT_RULE_FALLBACK = "INTENT_RULE_FALLBACK";
    public static final String RESPONSE_TEMPLATE_FALLBACK = "RESPONSE_TEMPLATE_FALLBACK";
    public static final String EMBEDDING_UNAVAILABLE = "EMBEDDING_UNAVAILABLE";
    public static final String VECTOR_STORE_UNAVAILABLE = "VECTOR_STORE_UNAVAILABLE";
    public static final String NO_VECTOR_HITS = "NO_VECTOR_HITS";
    public static final String REQUEST_FAILED = "REQUEST_FAILED";

    private FallbackClassifier() {
    }

    /** 主分类（最严重项） + 全部降级原因明细。 */
    public static Classification classify(TraceFacts facts) {
        List<String> reasons = new ArrayList<>();
        if (facts.failed()) {
            reasons.add(REQUEST_FAILED);
        }
        if (facts.intentFallbackReason() != null) {
            reasons.add(INTENT_RULE_FALLBACK + ":" + facts.intentFallbackReason());
        }
        if (facts.responseFallbackReason() != null) {
            reasons.add(RESPONSE_TEMPLATE_FALLBACK + ":" + facts.responseFallbackReason());
        }
        String degradation = facts.mealRetrievalDegradationReason();
        if (degradation != null && !degradation.isBlank()) {
            reasons.add(degradation);
        }
        String main;
        if (facts.failed()) {
            main = REQUEST_FAILED;
        } else if (facts.intentFallbackReason() != null) {
            main = INTENT_RULE_FALLBACK;
        } else if (facts.responseFallbackReason() != null) {
            main = RESPONSE_TEMPLATE_FALLBACK;
        } else if (degradation != null && degradation.toLowerCase(Locale.ROOT).contains("embedding")) {
            main = EMBEDDING_UNAVAILABLE;
        } else if (degradation != null && degradation.toLowerCase(Locale.ROOT).contains("vector_store")) {
            main = VECTOR_STORE_UNAVAILABLE;
        } else if (degradation != null && degradation.toLowerCase(Locale.ROOT).contains("no_vector_hits")) {
            main = NO_VECTOR_HITS;
        } else {
            main = NONE;
        }
        return new Classification(main, reasons);
    }

    /** 主分类 + 全部原因。 */
    public record Classification(String main, List<String> reasons) {
    }
}
