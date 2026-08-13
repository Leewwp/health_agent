package com.diet.health.evalv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BENCHMARK JSONL 解析（#73）：每行一条样本，按 caseId+turnIndex 关联多轮会话。
 * <p>
 * 校验：36 条固定样本、caseType 分层数量、必填字段齐全、caseId+turnIndex 唯一、
 * 两遍复核标记（labeledAt/reviewedAt/reviewStatus=REVIEWED）与排除语义。
 */
public class BenchmarkDatasetLoader {

    /** 数据集标识（报告 dataset.id）。 */
    public static final String DATASET_ID = "health-eval-v2-benchmark";

    /** 首版样本总量（契约 §5）。 */
    public static final int EXPECTED_SAMPLE_COUNT = 36;

    /** caseType 分层期望数量（契约 §5）：饮食 12、健身 8、作息 6、计划/综合 6、风险阻断 4。 */
    public static final Map<String, Integer> EXPECTED_CASE_TYPE_COUNTS = Map.of(
            "MEAL", 12,
            "EXERCISE", 8,
            "ROUTINE", 6,
            "PLAN_VALIDATION", 4,
            "COMPOSITE", 2,
            "RISK_BLOCK", 4
    );

    private final ObjectMapper objectMapper;

    public BenchmarkDatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 从文件读取样本并做完整性校验（样本数、分层、字段、唯一键、复核标记）。 */
    public List<BenchmarkCase> load(Path path) throws IOException {
        String jsonl = Files.readString(path, StandardCharsets.UTF_8);
        return parse(jsonl);
    }

    /** 从 classpath/输入流读取样本（测试用）。 */
    public List<BenchmarkCase> load(InputStream in) throws IOException {
        return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    private List<BenchmarkCase> parse(String jsonl) throws IOException {
        List<BenchmarkCase> cases = new ArrayList<>();
        int lineNo = 0;
        for (String line : jsonl.split("\n")) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(trimmed);
            BenchmarkCase parsed = BenchmarkCase.parse(node);
            validate(parsed, lineNo);
            cases.add(parsed);
        }
        validateSet(cases);
        return cases;
    }

    /** 单行必填字段与复核标记校验。 */
    private void validate(BenchmarkCase sample, int lineNo) {
        if (sample.caseId() == null || sample.caseId().isBlank()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 caseId");
        }
        if (sample.turnIndex() < 1) {
            throw new IllegalArgumentException("第 " + lineNo + " 行 turnIndex 必须 >= 1");
        }
        if (sample.input() == null || sample.input().isBlank()) {
            throw new IllegalArgumentException("第 " + lineNo + " 行缺少 input");
        }
        if (sample.excluded()) {
            return;
        }
        if (sample.gold() == null && sample.planInput() == null) {
            throw new IllegalArgumentException("第 " + lineNo + " 行未排除样本必须提供 gold.expectedHealth 或 planInput");
        }
        if (!BenchmarkCase.REVIEW_STATUS_REVIEWED.equals(sample.reviewStatus())
                || sample.labeledAt() == null || sample.reviewedAt() == null) {
            throw new IllegalArgumentException(
                    "第 " + lineNo + " 行必须记录两遍复核（reviewStatus=REVIEWED + labeledAt + reviewedAt）");
        }
    }

    /** 整体样本集校验：总量、caseType 分层、caseId+turnIndex 唯一。 */
    private void validateSet(List<BenchmarkCase> cases) {
        if (cases.size() != EXPECTED_SAMPLE_COUNT) {
            throw new IllegalArgumentException(
                    "样本总数必须为 " + EXPECTED_SAMPLE_COUNT + "，实际 " + cases.size());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BenchmarkCase sample : cases) {
            counts.merge(sample.caseType(), 1, Integer::sum);
        }
        EXPECTED_CASE_TYPE_COUNTS.forEach((type, expected) -> {
            Integer actual = counts.get(type);
            if (actual == null || actual != expected) {
                throw new IllegalArgumentException("caseType=" + type + " 样本数必须为 " + expected + "，实际 " + actual);
            }
        });
        Set<String> keys = new LinkedHashSet<>();
        for (BenchmarkCase sample : cases) {
            String key = sample.caseId() + "#" + sample.turnIndex();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("caseId+turnIndex 必须唯一: " + key);
            }
        }
    }
}
