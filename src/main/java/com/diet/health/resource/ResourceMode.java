package com.diet.health.resource;

/** 健康资源运行模式；枚举名称同时作为 Trace 与计划快照中的稳定外部标识。 */
public enum ResourceMode {
    REVIEWED_DB,
    FIXTURE_SEED;

    public boolean isReviewed() {
        return this == REVIEWED_DB;
    }

    public boolean isFixture() {
        return this == FIXTURE_SEED;
    }

    /** 正式审核库批处理能力在 fixture 模式下统一 fail-fast。 */
    public void requireReviewedCapability(String switchName, String capability) {
        if (isFixture()) {
            throw new IllegalStateException(
                    this + " 模式下禁止启用 " + switchName
                            + "（" + capability + "不是 fixture 能力，请改用 diet.resource.mode=reviewed）");
        }
    }
}
