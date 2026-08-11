package com.diet.health.vectorstore;

/**
 * 向量索引身份：provider + model + dimension + version。
 * <p>
 * collection 名由该身份派生（如 meal_dashscope_text-embedding-v3_1024_v3-1024），
 * 保证同一身份的重建落在同一 collection；切换 embedding 模型/维度时必须换身份，
 * 禁止混写旧向量。
 */
public record VectorStoreIdentity(String provider, String model, int dimension, String version) {

    /** collection 名：只保留字母数字与 _-.，避免非法字符破坏 Qdrant 名称约束。 */
    public String collectionName() {
        return "meal_" + sanitize(provider) + "_" + sanitize(model) + "_" + dimension + "_" + sanitize(version);
    }

    private String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
