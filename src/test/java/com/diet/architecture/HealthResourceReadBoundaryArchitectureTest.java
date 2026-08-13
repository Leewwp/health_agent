package com.diet.architecture;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 方案 B 健康资源读取边界架构闸门（#67）。
 * <p>
 * 精确允许依赖图，不使用宽泛包白名单：
 * <ul>
 *   <li>{@code DbReviewedResourceProvider} 可依赖 MealMapper/ExerciseMapper/RoutineFactMapper；</li>
 *   <li>审核餐食 DB adapter（DbReviewedMealReader）只可依赖 MealMapper；</li>
 *   <li>审核动作 DB adapter（DbReviewedExerciseReader）只可依赖 ExerciseMapper；</li>
 *   <li>其余健康调用方（领域编排/计划/反馈/module、browse/rag/vectorstore/eval、健康控制器与 MCP 工具）
 *       不得直接依赖三个资源 Mapper；违规时失败信息列出实际违规类与违规 Mapper 类型。</li>
 * </ul>
 * 扫描范围：com.diet.health 全包 + 健康控制器 + MCP 包；读取全部 class 字节码，
 * 覆盖非 Spring bean、嵌套类、继承链和方法体局部引用。旧饮食兼容链
 * （com.diet.service 等）不受本闸门约束。
 */
class HealthResourceReadBoundaryArchitectureTest {

    /** 允许直接依赖资源 Mapper 的类 → 允许的 Mapper 简单名集合（精确到类）。 */
    private static final Map<String, Set<String>> ALLOWED_MAPPER_DEPS = Map.of(
            "com.diet.health.resource.DbReviewedResourceProvider",
            Set.of("MealMapper", "ExerciseMapper", "RoutineFactMapper"),
            "com.diet.health.reader.meal.DbReviewedMealReader",
            Set.of("MealMapper"),
            "com.diet.health.reader.exercise.DbReviewedExerciseReader",
            Set.of("ExerciseMapper")
    );

    private static final Set<String> RESOURCE_MAPPERS = Set.of("MealMapper", "ExerciseMapper", "RoutineFactMapper");

    @Test
    void 只有允许的三个类可直接依赖资源Mapper() throws Exception {
        Map<String, Set<String>> violations = new TreeMap<>();
        for (String className : scanClasses("com.diet.health", "com.diet.controller.health", "com.diet.mcp")) {
            if (ALLOWED_MAPPER_DEPS.containsKey(className)) {
                continue;
            }
            Set<String> deps = directMapperDeps(className);
            if (!deps.isEmpty()) {
                violations.put(className, deps);
            }
        }
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("健康调用方直接依赖资源 Mapper（违反方案 B 读取边界）：\n");
            violations.forEach((cls, mappers) -> sb.append("  ").append(cls).append(" → ").append(mappers).append('\n'));
            sb.append("允许依赖：DbReviewedResourceProvider(三个 Mapper) / DbReviewedMealReader(MealMapper) / DbReviewedExerciseReader(ExerciseMapper)。\n");
            sb.append("其余调用方必须通过 HealthResourceProvider 或审核读取模块接口消费资源。");
            fail(sb.toString());
        }
    }

    @Test
    void 允许名单中的adapter权限不交叉() throws Exception {
        for (String className : ALLOWED_MAPPER_DEPS.keySet()) {
            Set<String> deps = directMapperDeps(className);
            Set<String> allowed = ALLOWED_MAPPER_DEPS.get(className);
            assertEquals(allowed, deps,
                    className + " 的 Mapper 依赖与允许集合不一致（不允许跨 adapter 串读）");
        }
    }

    @Test
    void 允许名单中的类确实存在() {
        for (String className : ALLOWED_MAPPER_DEPS.keySet()) {
            try {
                Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                fail("架构闸门允许名单引用了不存在的类：" + className);
            }
        }
    }

    // ---- 扫描与依赖提取 ----

    private List<String> scanClasses(String... basePackages) throws IOException, URISyntaxException {
        List<String> classes = new ArrayList<>();
        for (String basePackage : basePackages) {
            String packagePath = basePackage.replace('.', '/');
            Enumeration<URL> roots = getClass().getClassLoader().getResources(packagePath);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if (!"file".equals(root.getProtocol())) {
                    continue;
                }
                Path directory = Path.of(root.toURI());
                try (var files = Files.walk(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".class"))
                            .map(directory::relativize)
                            .map(Path::toString)
                            .map(relative -> basePackage + "." + relative
                                    .substring(0, relative.length() - ".class".length())
                                    .replace('/', '.').replace('\\', '.'))
                            .filter(className -> !className.endsWith("package-info"))
                            .filter(className -> !className.matches(".*Test(\\$.*)?$"))
                            .forEach(classes::add);
                }
            }
        }
        return classes.stream().sorted().distinct().toList();
    }

    /** 类字节码及其继承链引用的资源 Mapper 集合，包括方法体局部引用。 */
    private Set<String> directMapperDeps(String className) throws ClassNotFoundException, IOException {
        Class<?> clazz = Class.forName(className, false, getClass().getClassLoader());
        Set<String> deps = new TreeSet<>();
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            collectMapperNames(current, deps);
        }
        return deps;
    }

    private void collectMapperNames(Class<?> clazz, Set<String> deps) throws IOException {
        String resource = "/" + clazz.getName().replace('.', '/') + ".class";
        try (InputStream input = clazz.getResourceAsStream(resource)) {
            if (input == null) {
                return;
            }
            String bytecode = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            for (String mapper : RESOURCE_MAPPERS) {
                if (bytecode.contains("com/diet/mapper/" + mapper)) {
                    deps.add(mapper);
                }
            }
        }
    }
}
