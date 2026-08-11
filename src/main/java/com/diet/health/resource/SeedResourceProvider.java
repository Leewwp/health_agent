package com.diet.health.resource;

import com.diet.health.module.HealthResource;
import com.diet.health.module.PlanMealCandidate;
import com.diet.health.module.RoutineFact;
import com.diet.health.seed.SeedResources;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存种子 Provider（fixture 模式）：读取 {@link SeedResources}。
 * <p>
 * 只用于无 API key 环境的离线演示与测试，ID 为种子专用（动作 9001-9008 / 事实 R1-R5 /
 * 餐食 M1-M9），有明确 providerMode=FIXTURE_SEED 标识，不得冒充数据库审核子集。
 */
@Component
public class SeedResourceProvider implements HealthResourceProvider {

    @Override
    public List<HealthResource> exercises() {
        return List.copyOf(SeedResources.EXERCISES);
    }

    @Override
    public List<RoutineFact> routineFacts() {
        return List.copyOf(SeedResources.ROUTINE_FACTS);
    }

    @Override
    public Optional<HealthResource> exerciseById(String resourceId) {
        return SeedResources.EXERCISES.stream()
                .filter(item -> item.resourceId().equals(resourceId))
                .findFirst();
    }

    @Override
    public Optional<HealthResource> mealById(String resourceId) {
        return SeedResources.MEAL_CANDIDATES.stream()
                .filter(candidate -> candidate.resourceId().equals(resourceId))
                .findFirst()
                .map(this::toMealResource);
    }

    @Override
    public Optional<RoutineFact> routineFactById(String factId) {
        return SeedResources.ROUTINE_FACTS.stream()
                .filter(fact -> fact.factId().equals(factId))
                .findFirst();
    }

    @Override
    public Optional<RoutineFact> routineFactByTopic(String topicKeyword) {
        if (topicKeyword == null || topicKeyword.isBlank()) {
            return Optional.empty();
        }
        return SeedResources.ROUTINE_FACTS.stream()
                .filter(fact -> fact.category().contains(topicKeyword) || topicKeyword.contains(fact.category()))
                .findFirst();
    }

    @Override
    public List<String> allFactIds() {
        return SeedResources.ROUTINE_FACTS.stream()
                .map(RoutineFact::factId)
                .toList();
    }

    @Override
    public List<HealthResource> planReadyExercises() {
        return SeedResources.EXERCISES.stream()
                .filter(HealthResource::planReady)
                .toList();
    }

    @Override
    public List<String> planReadyExerciseIds() {
        return SeedResources.EXERCISES.stream()
                .filter(HealthResource::planReady)
                .map(HealthResource::resourceId)
                .toList();
    }

    @Override
    public List<PlanMealCandidate> planMealCandidates() {
        return SeedResources.MEAL_CANDIDATES;
    }

    @Override
    public String providerMode() {
        return "FIXTURE_SEED";
    }

    @Override
    public String resourceVersion() {
        return SeedResources.SEED_VERSION;
    }

    /** 餐食候选 → 类型化资源：供快照来源字段与反馈校验解析（resourceId 为种子 ID，标签含餐次）。 */
    private HealthResource toMealResource(PlanMealCandidate candidate) {
        return new HealthResource(
                candidate.resourceType(),
                candidate.resourceId(),
                candidate.name(),
                "PUBLIC",
                "演示餐食种子",
                null,
                false,
                Map.of("mealTime", candidate.mealTimeTags())
        );
    }
}
