package com.diet.health.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void 解析原生vectors响应并校验维度() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] body = "{\"vectors\":[[0.1,0.2]],\"base_resp\":{\"status_code\":0}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        MiniMaxEmbeddingClient client = new MiniMaxEmbeddingClient("test-key",
                "http://localhost:" + server.getAddress().getPort(), "embo-01", "", 2, 1000,
                new ObjectMapper());

        Optional<float[]> result = client.embed("清淡晚餐");
        assertTrue(result.isPresent());
        assertArrayEquals(new float[]{0.1f, 0.2f}, result.get(), 0.0001f);
    }

    @Test
    void 业务错误和占位key均降级() throws Exception {
        MiniMaxEmbeddingClient unconfigured = new MiniMaxEmbeddingClient("<rotated-key>",
                "http://localhost:1", "embo-01", "", 2, 100,
                new ObjectMapper());
        assertFalse(unconfigured.configured());
        assertTrue(unconfigured.embed("test").isEmpty());
    }
}
