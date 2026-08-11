package com.diet.config;

import com.diet.health.vectorstore.InMemoryVectorStore;
import com.diet.health.vectorstore.QdrantVectorStore;
import com.diet.health.vectorstore.VectorStore;
import com.diet.health.vectorstore.VectorStoreIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 餐食向量存储配置（M5 #54）。
 * <p>
 * {@code diet.vectorstore.mode}=qdrant 时使用真实 Qdrant（明文 gRPC）；
 * 其余值（默认 in-memory）使用内存适配器，供测试与离线演示使用。
 * collection 身份由 {@link VectorStoreIdentity} 派生，默认与 diet.embedding.* 对齐，
 * 切换 embedding 模型/维度时须同步调整身份并重建 collection。
 */
@Configuration
public class VectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfiguration.class);

    @Bean
    public VectorStoreIdentity vectorStoreIdentity(
            @Value("${diet.vectorstore.provider:dashscope}") String provider,
            @Value("${diet.vectorstore.model:text-embedding-v3}") String model,
            @Value("${diet.vectorstore.dimension:1024}") int dimension,
            @Value("${diet.vectorstore.version:v3-1024}") String version) {
        return new VectorStoreIdentity(provider, model, dimension, version);
    }

    @Bean
    public VectorStore vectorStore(VectorStoreIdentity identity,
                                   @Value("${diet.vectorstore.mode:in-memory}") String mode,
                                   @Value("${diet.vectorstore.host:localhost}") String host,
                                   @Value("${diet.vectorstore.grpc-port:6334}") int grpcPort,
                                   @Value("${diet.vectorstore.use-tls:false}") boolean useTls,
                                   @Value("${diet.vectorstore.timeout-ms:5000}") long timeoutMs) {
        if ("qdrant".equalsIgnoreCase(mode)) {
            log.info("向量存储模式：Qdrant（{}:{}，collection {}）", host, grpcPort, identity.collectionName());
            return new QdrantVectorStore(identity, host, grpcPort, useTls, timeoutMs);
        }
        log.info("向量存储模式：in-memory（collection {}）", identity.collectionName());
        return new InMemoryVectorStore(identity);
    }
}
