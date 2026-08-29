package com.diet.agent.llm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容聊天模型适配器单测：请求体组装、响应解析与模型名。
 */
class OpenAiCompatibleChatModelTest {

    private final OpenAiCompatibleChatModel model =
            new OpenAiCompatibleChatModel("sk-test", "https://example.com/compatible-mode/v1", "qwen-test", 5000);

    @Test
    void buildRequestBody_角色与内容映射() throws Exception {
        Msg user = Msg.builder().role(MsgRole.USER).textContent("你好").build();
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("你是助手").build();
        String body = model.buildRequestBody(List.of(system, user), null);

        assertTrue(body.contains("\"model\":\"qwen-test\""));
        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"content\":\"你好\""));
    }

    @Test
    void buildRequestBody_透传温度与最大token() throws Exception {
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.7).maxTokens(256).build();
        String body = model.buildRequestBody(List.of(user), options);

        assertTrue(body.contains("\"temperature\":0.7"));
        assertTrue(body.contains("\"max_tokens\":256"));
    }

    @Test
    void parseResponse_解析文本内容与用量() throws Exception {
        String json = "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"推荐结果\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5}}";
        ChatResponse response = model.parseResponse(json);

        assertEquals("chatcmpl-1", response.getId());
        assertNotNull(response.getContent());
        assertEquals("推荐结果", response.getContent().get(0).toString());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void getModelName_返回配置模型名() {
        assertEquals("qwen-test", model.getModelName());
    }

    @Test
    void 外层截止时间可以取消正在等待的HTTP请求() throws Exception {
        // 加固规格：取消测试必须可证伪——服务端延迟（5s）远超断言阈值（3s），
        // “完全不取消、等满响应”的路径必然失败；除耗时外还断言两个取消信号：
        // ① 模型暴露的 cancelledRequests 计数；② 原始 socket 服务端读到 EOF（连接被真实中止）。
        java.net.ServerSocket serverSocket = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        java.util.concurrent.atomic.AtomicBoolean clientDisconnected = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                InputStream in = socket.getInputStream();
                // 读完请求头与请求体后阻塞在 read()：客户端取消 → HttpClient 关闭连接 → read 返回 -1
                StringBuilder request = new StringBuilder();
                int ch;
                while ((ch = in.read()) != -1) {
                    request.append((char) ch);
                    if (request.toString().endsWith("\r\n\r\n")
                            && !request.toString().contains("Content-Length: 0")) {
                        String upper = request.toString().toUpperCase();
                        String marker = "CONTENT-LENGTH:";
                        int idx = upper.indexOf(marker);
                        int contentLength = idx >= 0 ? Integer.parseInt(
                                upper.substring(idx + marker.length()).trim().split("\\r")[0].trim()) : 0;
                        for (int i = 0; i < contentLength; i++) {
                            in.read();
                        }
                        break;
                    }
                }
                int next = in.read();
                if (next == -1) {
                    clientDisconnected.set(true);
                    return;
                }
                // 未被取消的路径：保持 5s 延迟后正常回包（远超 3s 断言阈值，保证可证伪）
                Thread.sleep(5000);
                byte[] body = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                java.io.OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(body);
                out.flush();
            } catch (Exception ignored) {
                // 服务端异常（含客户端断开引发的读写失败）不影响断言路径。
            }
        }, "mock-llm-server");
        serverThread.setDaemon(true);
        serverThread.start();
        OpenAiCompatibleChatModel slow = new OpenAiCompatibleChatModel("sk-test",
                "http://127.0.0.1:" + serverSocket.getLocalPort(), "qwen-test", 5000);
        try {
            Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
            long started = System.nanoTime();
            assertThrows(RuntimeException.class, () -> slow.stream(List.of(user), List.of(), null)
                    .timeout(Duration.ofMillis(100)).blockLast());
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(elapsedMs < 3000,
                    "外层截止时间应远早于服务端 5s 延迟返回，实际耗时=" + elapsedMs + "ms");
            assertEquals(1, slow.cancelledRequests(), "模型必须暴露可观测的取消信号（cancelledRequests）");
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!clientDisconnected.get() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(clientDisconnected.get(),
                    "HTTP future 必须被真实取消（服务端应读到客户端断开的 EOF）");
        } finally {
            serverSocket.close();
        }
    }
}
