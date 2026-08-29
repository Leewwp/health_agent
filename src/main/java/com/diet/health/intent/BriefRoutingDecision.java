package com.diet.health.intent;

import com.diet.health.enums.HealthDomain;

/**
 * 共享结构化续轮判定结果（简报补充回路规格 v3.2）：
 * briefActive（计划简报会话是否活跃）、activeSide（活跃侧归属，仅 COMPOSITE 有意义）、
 * escape（逃生口类型）、reason（命中的裁决优先级）。escapeDomain 为 RECOMMEND/DOMAIN_OR_ROUTINE
 * 逃生口的导航辅助字段，不属于对外合同的四元组。
 * <p>
 * 裁决优先级为固定合同：风险阻断（由编排器在判定前执行）&gt; 明确领域切换/作息提问 &gt;
 * 明确替代/换一批 &gt; 明确普通推荐逃生口 &gt; 简报生命周期（GENERATED/PAUSED 不捕获）&gt;
 * activeSide 归属 &gt; 字段解析。三个调用点（模型前续轮、模型后修正、编排器简报门槛）
 * 必须复用同一实现，不得各自覆写。
 */
public record BriefRoutingDecision(
        boolean briefActive,
        BriefSide activeSide,
        BriefEscape escape,
        String reason,
        HealthDomain escapeDomain
) {

    public static BriefRoutingDecision inactive(String reason) {
        return new BriefRoutingDecision(false, BriefSide.NONE, BriefEscape.NONE, reason, null);
    }
}
