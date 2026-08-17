package com.diet.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证无扩展名 .env 的 Spring Config Data 加载与覆盖顺序。 */
class DotenvConfigDataTest {

    @TempDir
    Path tempDir;

    @Test
    void 从Dotenv读取DashScope配置() throws IOException {
        Path dotenv = writeDotenv("dotenv-test-key");

        try (ConfigurableApplicationContext context = start(dotenv)) {
            Environment environment = context.getEnvironment();
            assertEquals("dotenv-test-key", environment.getProperty("DASHSCOPE_API_KEY"));
            assertEquals("dotenv-test-key", environment.resolvePlaceholders("${DASHSCOPE_API_KEY:}"));
        }
    }

    @Test
    void 命令行参数覆盖Dotenv() throws IOException {
        Path dotenv = writeDotenv("dotenv-test-key");

        try (ConfigurableApplicationContext context = start(
                dotenv, "--DASHSCOPE_API_KEY=command-line-test-key")) {
            Environment environment = context.getEnvironment();
            assertEquals("command-line-test-key", environment.getProperty("DASHSCOPE_API_KEY"));
        }
    }

    private Path writeDotenv(String apiKey) throws IOException {
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(dotenv, "DASHSCOPE_API_KEY=" + apiKey + System.lineSeparator());
        return dotenv;
    }

    private ConfigurableApplicationContext start(Path dotenv, String... extraArgs) {
        String[] args = new String[extraArgs.length + 3];
        args[0] = "--spring.config.name=dotenv-config-data-test";
        args[1] = "--spring.config.import=file:" + dotenv.toAbsolutePath() + "[.properties]";
        args[2] = "--spring.main.banner-mode=off";
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);

        return new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestApplication {
    }
}
