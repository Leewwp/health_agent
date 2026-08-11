package com.diet.skills;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 技能清单的 MCP Resources 暴露（M5 #50）。
 * <p>
 * 以稳定 URI {@code skill://<name>} 通过 resources/list 与 resources/read 暴露原始 YAML；
 * 只读，不引入新的自主执行循环。read 请求的 URI 不在 Registry 中时抛 RESOURCE_NOT_FOUND。
 */
@Component
public class SkillResources {

    private static final Logger log = LoggerFactory.getLogger(SkillResources.class);

    public static final String MIME_TYPE_YAML = "application/yaml";

    private final SkillsRegistry registry;
    private final List<McpServerFeatures.SyncResourceSpecification> specifications;

    public SkillResources(SkillsRegistry registry) {
        this.registry = registry;
        List<McpServerFeatures.SyncResourceSpecification> specs = new ArrayList<>();
        for (SkillManifest manifest : registry.skills()) {
            McpSchema.Resource resource = McpSchema.Resource.builder()
                    .uri(manifest.resourceUri())
                    .name(manifest.name())
                    .description(manifest.description())
                    .mimeType(MIME_TYPE_YAML)
                    .build();
            specs.add(new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
                String content = registry.rawContent(request.uri())
                        .orElseThrow(() -> io.modelcontextprotocol.spec.McpError.builder(
                                        io.modelcontextprotocol.spec.McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
                                .message("技能资源不存在：" + request.uri())
                                .build());
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(request.uri(), MIME_TYPE_YAML, content)));
            }));
        }
        this.specifications = List.copyOf(specs);
        log.info("技能清单已通过 MCP Resources 暴露：{} 个", specs.size());
    }

    /** 注册到 MCP server 的资源规格列表。 */
    public List<McpServerFeatures.SyncResourceSpecification> specifications() {
        return specifications;
    }

    /** 从 classpath 的 skills/ 目录加载全部 YAML manifest（按文件名升序保证确定性）。 */
    public static SkillsRegistry loadClasspathSkills() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/*.yaml");
            List<String> contents = new ArrayList<>();
            for (Resource resource : resources) {
                contents.add(resource.getContentAsString(StandardCharsets.UTF_8));
            }
            return new SkillsRegistry(contents);
        } catch (IOException e) {
            throw new IllegalStateException("加载 classpath skills 清单失败", e);
        }
    }
}
