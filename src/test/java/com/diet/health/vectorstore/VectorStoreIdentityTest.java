package com.diet.health.vectorstore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * VectorStoreIdentity collection 名派生测试（M5 #54）。
 * 验证 provider+model+dimension+version 拼装与非法字符清洗。
 */
class VectorStoreIdentityTest {

    @Test
    void 身份拼出完整collection名() {
        VectorStoreIdentity identity = new VectorStoreIdentity("dashscope", "text-embedding-v3", 1024, "v3-1024");
        assertEquals("meal_dashscope_text-embedding-v3_1024_v3-1024", identity.collectionName());
    }

    @Test
    void 非法字符被清洗为下划线() {
        VectorStoreIdentity identity = new VectorStoreIdentity("a b/c", "m$o#d", 8, "v.1");
        assertEquals("meal_a_b_c_m_o_d_8_v.1", identity.collectionName());
    }
}
