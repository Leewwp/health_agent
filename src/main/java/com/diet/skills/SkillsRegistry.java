package com.diet.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skills Registry（M5 #50）：启动时加载并校验版本化技能清单。
 * <p>
 * 校验规则（任一不满足即拒绝启动）：
 * <ul>
 *   <li>name/version/description 非空，name 只能是小写字母数字与连字符；</li>
 *   <li>input_schema/output_schema 必须可解析且为带 type 的对象；</li>
 *   <li>allowed_tools 非空且全部属于四工具 allowlist；</li>
 *   <li>risk_level 只能是 low/medium/high；</li>
 *   <li>name 全局唯一（稳定 URI 的前提），重复即拒绝。</li>
 * </ul>
 * manifest 只描述能力与调用边界；Registry 本身不执行任何工具编排。
 */
public class SkillsRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillsRegistry.class);

    /** 四工具 allowlist（与 MCP 工具白名单一致，M5 #47）。 */
    public static final List<String> TOOL_ALLOWLIST = List.of(
            "search_meals", "get_meal_detail", "get_routine_facts", "calculate_targets");

    private static final List<String> RISK_LEVELS = List.of("low", "medium", "high");

    private final List<SkillManifest> skills;
    private final Map<String, String> rawContentByUri;

    /** 解析并校验 manifest；任何非法清单抛 {@link IllegalArgumentException}，阻止启动。 */
    public SkillsRegistry(List<String> rawManifests) {
        if (rawManifests == null || rawManifests.isEmpty()) {
            throw new IllegalArgumentException("skills 清单不能为空：至少需要一个版本化 manifest");
        }
        List<SkillManifest> parsed = new ArrayList<>(rawManifests.size());
        Map<String, String> rawByUri = new LinkedHashMap<>();
        for (String raw : rawManifests) {
            SkillManifest manifest = parseAndValidate(raw);
            if (rawByUri.containsKey(manifest.resourceUri())) {
                throw new IllegalArgumentException("技能名称重复：" + manifest.name() + "（稳定 URI 必须唯一）");
            }
            parsed.add(manifest);
            rawByUri.put(manifest.resourceUri(), raw);
        }
        parsed.sort((a, b) -> a.name().compareTo(b.name()));
        this.skills = List.copyOf(parsed);
        this.rawContentByUri = Map.copyOf(rawByUri);
        log.info("Skills Registry 已加载 {} 个技能清单：{}", skills.size(),
                skills.stream().map(SkillManifest::name).toList());
    }

    /** 全部技能（不可变，按 name 升序）。 */
    public List<SkillManifest> skills() {
        return skills;
    }

    /** 按稳定 URI（skill://name）查技能。 */
    public Optional<SkillManifest> byUri(String uri) {
        return skills.stream().filter(skill -> skill.resourceUri().equals(uri)).findFirst();
    }

    /** 按稳定 URI 返回原始 YAML 内容（resources/read 使用）。 */
    public Optional<String> rawContent(String uri) {
        return Optional.ofNullable(rawContentByUri.get(uri));
    }

    /** 解析单个 manifest；非法字段抛 IllegalArgumentException。 */
    static SkillManifest parseAndValidate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("manifest 不能为空");
        }
        Map<String, Object> doc = parseYaml(raw);
        String name = requireString(doc, "name");
        if (!name.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("技能 name 非法：" + name);
        }
        String version = requireString(doc, "version");
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new IllegalArgumentException("技能版本非法：" + version);
        }
        String description = requireString(doc, "description");
        Map<String, Object> inputSchema = requireSchema(doc, "input_schema");
        Map<String, Object> outputSchema = requireSchema(doc, "output_schema");
        List<String> allowedTools = requireTools(doc);
        String riskLevel = requireString(doc, "risk_level");
        if (!RISK_LEVELS.contains(riskLevel)) {
            throw new IllegalArgumentException("risk_level 必须是 " + RISK_LEVELS + "：" + riskLevel);
        }
        return new SkillManifest(name, version, description, inputSchema, outputSchema, allowedTools, riskLevel);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseYaml(String raw) {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object parsed = yaml.load(raw);
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("manifest 必须是 YAML 对象");
            }
            return (Map<String, Object>) parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("manifest YAML 解析失败：" + e.getMessage(), e);
        }
    }

    private static String requireString(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("manifest 缺少非空字段：" + key);
        }
        return s;
    }

    /** Schema 必须可解析且为带 type 的对象。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireSchema(Map<String, Object> doc, String key) {
        Object value = doc.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("manifest 字段 " + key + " 必须是对象");
        }
        Map<String, Object> schema = (Map<String, Object>) value;
        if (!(schema.get("type") instanceof String type) || type.isBlank()) {
            throw new IllegalArgumentException("manifest 字段 " + key + " 缺少 type");
        }
        return Map.copyOf(schema);
    }

    @SuppressWarnings("unchecked")
    private static List<String> requireTools(Map<String, Object> doc) {
        Object value = doc.get("allowed_tools");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("manifest 字段 allowed_tools 必须是非空数组");
        }
        List<String> tools = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String tool)) {
                throw new IllegalArgumentException("allowed_tools 元素必须是字符串");
            }
            if (!TOOL_ALLOWLIST.contains(tool)) {
                throw new IllegalArgumentException("allowed_tools 越界：" + tool + "，允许范围 " + TOOL_ALLOWLIST);
            }
            tools.add(tool);
        }
        return List.copyOf(tools);
    }
}
