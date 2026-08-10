package com.diet.health.resource;

import com.diet.health.module.HealthResource;
import com.diet.health.module.RoutineFact;

import java.util.List;
import java.util.Optional;

/**
 * 统一审核资源提供者（40 号票）：封装已审核且可用的餐食、动作、作息事实查询。
 * <p>
 * 两种实现互斥，由 {@code diet.resource.mode} 选择：
 * REVIEWED_DB 读取数据库审核子集（餐食/动作用数据库稳定主键，作息事实用冻结业务 ref_id）；
 * FIXTURE_SEED 读取内存种子（9001-9008 / R1-R5），只用于离线演示与测试，禁止与审核资源混用。
 * 返回集合一律为不可变拷贝，空库不得抛异常。
 */
public interface HealthResourceProvider {

    /** 全部已审核动作（不可变拷贝）。 */
    List<HealthResource> exercises();

    /** 全部已审核作息事实（不可变拷贝）。 */
    List<RoutineFact> routineFacts();

    /** 按资源 ID 查动作（正式模式为数据库主键，fixture 模式为种子 ID），无命中返回空。 */
    Optional<HealthResource> exerciseById(String resourceId);

    /** 按资源 ID 查餐食（正式模式为数据库主键，fixture 模式无餐食返回空），无命中返回空。 */
    Optional<HealthResource> mealById(String resourceId);

    /** 按事实标识查作息事实（正式模式为冻结 ref_id，fixture 模式为 R 前缀 ID），无命中返回空。 */
    Optional<RoutineFact> routineFactById(String factId);

    /**
     * 按主题关键词查作息事实：正式模式匹配 topic（DB），fixture 模式匹配类别（种子），
     * 双向包含匹配保证两套数据都能命中"睡眠时长"这类关键词，返回按事实 ID 升序的第一条，无命中返回空。
     */
    Optional<RoutineFact> routineFactByTopic(String topicKeyword);

    /** 全部作息事实标识（计划资源目录用）。 */
    List<String> allFactIds();

    /** 全部 plan_ready 动作（周计划组合用）。 */
    List<HealthResource> planReadyExercises();

    /** 全部 plan_ready 动作资源 ID。 */
    List<String> planReadyExerciseIds();

    /** Provider 模式标识：REVIEWED_DB=数据库审核子集；FIXTURE_SEED=内存种子。 */
    String providerMode();

    /** 资源版本（数据库审核子集批次版本或种子版本）。 */
    String resourceVersion();
}
