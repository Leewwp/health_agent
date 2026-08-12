package com.diet.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 扫描范围：com.diet.health 全包 + 健康控制器 + MCP 包；检查构造器参数、字段与
 * 方法签名的类型引用。旧饮食兼容链（com.diet.service 等）不受本闸门约束。
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

    private List<String> scanClasses(String... basePackages) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        List<String> classes = new ArrayList<>();
        for (String basePackage : basePackages) {
            scanner.findCandidateComponents(basePackage).forEach(bd -> {
                String className = bd instanceof AnnotatedBeanDefinition annotated
                        ? annotated.getMetadata().getClassName()
                        : bd.getBeanClassName();
                if (className != null && !className.contains("$") && !className.endsWith("Test")) {
                    classes.add(className);
                }
            });
        }
        return classes.stream().sorted().distinct().toList();
    }

    /** 类直接引用的资源 Mapper 简单名集合（构造器参数、字段、方法签名）。 */
    private Set<String> directMapperDeps(String className) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(className, false, getClass().getClassLoader());
        Set<String> deps = new TreeSet<>();
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            for (Type type : constructor.getGenericParameterTypes()) {
                collectMapperNames(type, deps);
            }
        }
        for (Field field : clazz.getDeclaredFields()) {
            collectMapperNames(field.getGenericType(), deps);
        }
        for (Method method : clazz.getDeclaredMethods()) {
            collectMapperNames(method.getGenericReturnType(), deps);
            for (Type type : method.getGenericParameterTypes()) {
                collectMapperNames(type, deps);
            }
        }
        return deps;
    }

    private void collectMapperNames(Type type, Set<String> deps) {
        if (type instanceof Class<?> clazz) {
            if (RESOURCE_MAPPERS.contains(clazz.getSimpleName())) {
                deps.add(clazz.getSimpleName());
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            collectMapperNames(parameterized.getRawType(), deps);
            for (Type arg : parameterized.getActualTypeArguments()) {
                collectMapperNames(arg, deps);
            }
            return;
        }
        if (type instanceof GenericArrayType array) {
            collectMapperNames(array.getGenericComponentType(), deps);
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type lower : wildcard.getLowerBounds()) {
                collectMapperNames(lower, deps);
            }
            for (Type upper : wildcard.getUpperBounds()) {
                collectMapperNames(upper, deps);
            }
            return;
        }
        if (type instanceof TypeVariable<?>) {
            return;
        }
    }
}
