package com.diet.health.evalv2;

import com.diet.health.evalv2.HealthEvalReport.CaseDetail;
import com.diet.health.evalv2.HealthEvalReport.ClassMetrics;
import com.diet.health.evalv2.HealthEvalReport.Metric;

import java.util.Locale;
import java.util.Map;

/**
 * health-eval-v2 Markdown 摘要（契约 §6）：只做摘要和解释，不维护另一套数字；
 * 版本化 JSON（health-eval-v2-report.json）是唯一机器事实来源。
 */
public final class HealthEvalMarkdownWriter {

    private HealthEvalMarkdownWriter() {
    }

    public static String write(HealthEvalReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# 健康评估报告 health-eval-v2\n\n");
        out.append("- schemaVersion: `").append(report.schemaVersion()).append("`\n");
        out.append("- 运行模式: `").append(report.runMode()).append("`\n");
        out.append("- 运行时间: ").append(report.runAt()).append("\n");
        out.append("- Git commit: `").append(report.gitCommit()).append("`\n");
        out.append("- 数据集: `").append(report.dataset().id()).append("@").append(report.dataset().version())
                .append("`（样本 ").append(report.dataset().sampleCount()).append(" 条）\n");
        out.append("- 资源: ").append(report.environment().resourceProviderMode())
                .append(" / 版本 ").append(report.environment().resourceVersion()).append("\n");
        out.append("- 规则版本: 风险 ").append(report.environment().rulesVersion())
                .append(" / 档案 ").append(report.environment().profileRulesVersion())
                .append(" / 计划 ").append(report.environment().planRulesVersion()).append("\n");
        out.append("- 模型: 主 ").append(report.environment().mainModel())
                .append(" / 轻 ").append(report.environment().lightModel()).append("\n");
        if (report.environment().embeddingModel() != null) {
            out.append("- Embedding: ").append(report.environment().embeddingModel())
                    .append(" @ ").append(report.environment().embeddingModelVersion()).append("\n");
            out.append("- 向量索引: ").append(report.environment().vectorStoreProvider())
                    .append(" / collection `").append(report.environment().vectorCollection()).append("`\n");
        } else {
            out.append("- Embedding/向量索引: 未使用（fixture 运行无 RAG 依赖）\n");
        }
        out.append("\n## 状态\n\n");
        out.append("- 总样本: ").append(report.status().total()).append("\n");
        out.append("- REVIEWED 进入评估: ").append(report.status().reviewed()).append("\n");
        out.append("- 排除（AMBIGUOUS_INPUT）: ").append(report.status().excluded()).append("\n");
        out.append("- 无 gold: ").append(report.status().missingGold()).append("\n");
        out.append("- 缺 Trace 结构化事实: ").append(report.status().missingTraceFact()).append("\n");

        out.append("\n## 核心指标\n\n");
        if (report.metrics() != null) {
            out.append("| 指标 | 值 | 有效分母 |\n|---|---|---|\n");
            line(out, "domainAccuracy", report.metrics().domainAccuracy());
            line(out, "taskAccuracy", report.metrics().taskAccuracy());
            line(out, "domainTaskExactMatch", report.metrics().domainTaskExactMatch());
            line(out, "slotExactMatch", report.metrics().slotExactMatch());
            if (report.metrics().slotMicro() != null) {
                line(out, "slotMicro.Precision", report.metrics().slotMicro().precision());
                line(out, "slotMicro.Recall", report.metrics().slotMicro().recall());
                line(out, "slotMicro.F1", report.metrics().slotMicro().f1());
            }
            if (report.metrics().risk() != null) {
                line(out, "riskAccuracy", report.metrics().risk().accuracy());
                line(out, "BLOCK_PLAN Recall", report.metrics().risk().blockPlanRecall());
                for (Map.Entry<String, ClassMetrics> entry : report.metrics().risk().byLevel().entrySet()) {
                    line(out, "risk." + entry.getKey() + ".F1", entry.getValue().f1());
                }
            }
            if (report.metrics().clarify() != null) {
                line(out, "clarifyDecisionAccuracy", report.metrics().clarify().clarifyDecisionAccuracy());
                line(out, "missingSlotF1", report.metrics().clarify().missingSlotF1());
            }
            line(out, "candidateCitationCompliance", report.metrics().candidateCitationCompliance());
            if (report.metrics().planValidation() != null) {
                line(out, "planValidationPassRate", report.metrics().planValidation().passRate());
            }
        }

        out.append("\n## 分组（分品类）\n\n");
        if (report.byDomain() != null) {
            report.byDomain().forEach((domain, metrics) -> {
                out.append("- ").append(domain).append(": domain ")
                        .append(fmt(metrics.domainAccuracy()))
                        .append(" / task ").append(fmt(metrics.taskAccuracy()))
                        .append(" / slotExact ").append(fmt(metrics.slotExactMatch()))
                        .append(" / slotF1 ").append(fmt(metrics.slotF1()))
                        .append("\n");
            });
        }

        out.append("\n## fallback 与延迟\n\n");
        if (report.metrics() != null && report.metrics().fallback() != null) {
            out.append("fallback 分布（互斥主分类，共 ").append(report.metrics().fallback().effectiveTotal())
                    .append(" 条聊天样本）:\n\n");
            report.metrics().fallback().distribution()
                    .forEach((category, count) -> out.append("- ").append(category).append(": ").append(count).append("\n"));
        }
        if (report.metrics() != null && report.metrics().latency() != null) {
            out.append("\n延迟（ms）: 正常 P50 ").append(fmt(report.metrics().latency().normal().p50()))
                    .append(" / P95 ").append(fmt(report.metrics().latency().normal().p95()))
                    .append(" / max ").append(fmt(report.metrics().latency().normal().max()))
                    .append("（n=").append(report.metrics().latency().normal().count()).append("）");
            if (report.metrics().latency().requestFailed().count() > 0) {
                out.append("；REQUEST_FAILED P50 ").append(fmt(report.metrics().latency().requestFailed().p50()))
                        .append(" / max ").append(fmt(report.metrics().latency().requestFailed().max()))
                        .append("（n=").append(report.metrics().latency().requestFailed().count()).append("）");
            }
            out.append("\n");
        }

        out.append("\n## 用户反馈（#74 精确归因）\n\n");
        if (report.metrics() != null && report.metrics().feedback() != null) {
            out.append("- adoptionRate: ").append(fmt(report.metrics().feedback().adoptionRate())).append("\n");
            out.append("- positiveRate: ").append(fmt(report.metrics().feedback().positiveRate())).append("\n");
            out.append("- exactAttributionCount（EXACT_TRACE）: ")
                    .append(report.metrics().feedback().exactAttributionCount()).append("\n");
            out.append("- legacyFallbackCount（LEGACY_SESSION_FALLBACK）: ")
                    .append(report.metrics().feedback().legacyFallbackCount()).append("\n");
            out.append("- FAVORITE/UNFAVORITE 不进满意度；旧 session 回退不进比例分母\n");
        }

        out.append("\n## 说明\n\n");
        if (report.notes() != null) {
            report.notes().forEach(note -> out.append("- ").append(note).append("\n"));
        }

        out.append("\n## 明细（部分）\n\n");
        if (report.cases() != null) {
            out.append("| caseId | 状态 | 命中 |\n|---|---|---|\n");
            for (CaseDetail detail : report.cases()) {
                StringBuilder hits = new StringBuilder();
                if (detail.domainMatch() != null) {
                    hits.append("domain=").append(bool(detail.domainMatch()));
                }
                if (detail.taskMatch() != null) {
                    hits.append(" task=").append(bool(detail.taskMatch()));
                }
                if (detail.slotExactMatch() != null) {
                    hits.append(" slot=").append(bool(detail.slotExactMatch()));
                }
                if (detail.riskMatch() != null) {
                    hits.append(" risk=").append(bool(detail.riskMatch()));
                }
                if (detail.planLevelMatch() != null) {
                    hits.append(" plan=").append(bool(detail.planLevelMatch()));
                }
                out.append("| ").append(detail.caseId()).append("#").append(detail.turnIndex())
                        .append(" | ").append(detail.status())
                        .append(" | ").append(hits).append(" |\n");
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String name, Metric metric) {
        out.append("| ").append(name).append(" | ").append(fmt(metric)).append(" | ").append(denom(metric))
                .append(" |\n");
    }

    private static String fmt(Metric metric) {
        return metric == null || metric.value() == null ? "null" : String.format(Locale.ROOT, "%.4f", metric.value());
    }

    private static String fmt(Double value) {
        return value == null ? "null" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String denom(Metric metric) {
        return metric == null ? "-" : String.valueOf(metric.denominator());
    }

    private static String bool(Boolean value) {
        return Boolean.TRUE.equals(value) ? "✓" : "✗";
    }
}
