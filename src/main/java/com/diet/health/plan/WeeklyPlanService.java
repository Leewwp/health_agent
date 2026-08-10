package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.module.HealthResource;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.model.WeeklyPlanItemRow;
import com.diet.model.WeeklyPlanRow;
import com.diet.model.WeeklyPlanVersionRow;
import com.diet.service.trace.AgentTraceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 周计划服务（34 号，规格 6.3/8.2/9）：
 * DRAFT/ACTIVE/ARCHIVED 生命周期；生成时经候选前 Guard（档案风险）与组合时校验，
 * 硬错误不持久化、警告可保存不可激活；激活归档旧 ACTIVE 并快照新版本；
 * ACTIVE 编辑复制为新 DRAFT；PATCH 只改日期/时间/备注。
 * PlanResponseAgent 只解释已校验结果，输出经输出后 Guard 校验。
 */
@Service
public class WeeklyPlanService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_PLAN_ITEMS = 500;

    private final HealthProfileService profileService;
    private final HealthRiskRuleService riskRuleService;
    private final WeeklyPlanComposerService composer;
    private final PlanValidationService validationService;
    private final WeeklyPlanMapper planMapper;
    private final HealthResourceProvider resourceProvider;
    private final HealthPlanResponseAgentService planResponseAgent;
    private final AgentTraceService agentTraceService;
    private final ObjectMapper objectMapper;

    /** 用户级锁，保证同用户激活/归档串行（单实例实现，规格 27 号）。 */
    private final Map<Long, Object> userLocks = new ConcurrentHashMap<>();

    public WeeklyPlanService(
            HealthProfileService profileService,
            HealthRiskRuleService riskRuleService,
            WeeklyPlanComposerService composer,
            PlanValidationService validationService,
            WeeklyPlanMapper planMapper,
            HealthResourceProvider resourceProvider,
            HealthPlanResponseAgentService planResponseAgent,
            AgentTraceService agentTraceService,
            ObjectMapper objectMapper
    ) {
        this.profileService = profileService;
        this.riskRuleService = riskRuleService;
        this.composer = composer;
        this.validationService = validationService;
        this.planMapper = planMapper;
        this.resourceProvider = resourceProvider;
        this.planResponseAgent = planResponseAgent;
        this.agentTraceService = agentTraceService;
        this.objectMapper = objectMapper;
    }

    /** 生成周计划草稿：候选前 Guard → 组合 → 组合时校验 → 持久化（硬错误不落库）。 */
    @Transactional
    public PlanView createDraft(Long userId, DraftPlanRequest request) {
        HealthProfileView profile = profileService.getProfile(userId);
        HealthRiskRuleService.RiskDecision profileRisk = riskRuleService.assessProfile(profile.age(), true);
        if (profileRisk.blocked()) {
            throw blocked(profileRisk.copy());
        }
        String timezone = request == null || request.timezone() == null || request.timezone().isBlank()
                ? DEFAULT_TIMEZONE : request.timezone();
        LocalDate weekStart = request != null && request.weekStart() != null
                ? request.weekStart() : nextMonday(timezone);
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "周起始日期必须是周一");
        }
        List<PlanItemDraft> items = composer.compose(profile.calorieLow(), profile.calorieHigh(), weekStart,
                timezone, request == null ? null : request.trainingFocus());
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (result.blocked()) {
            throw blocked(result.copy());
        }

        WeeklyPlanRow plan = new WeeklyPlanRow();
        plan.setUserId(userId);
        plan.setStatus(PlanStatus.DRAFT.name());
        plan.setWeekStart(weekStart);
        plan.setTimezone(timezone);
        plan.setProfileVersionNo(profile.versionNo());
        plan.setCalorieLow(profile.calorieLow());
        plan.setCalorieHigh(profile.calorieHigh());
        plan.setRulesVersion(PlanValidationService.RULES_VERSION);
        plan.setValidationLevel(result.level().name());
        plan.setValidationJson(toJson(ruleHitViews(result)));
        plan.setSourceSessionId(request == null ? null : request.sessionId());
        plan.setCurrentVersion(1L);
        LocalDateTime now = LocalDateTime.now();
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        planMapper.insertPlan(plan);
        insertVersion(plan, profile, result, now, items);
        insertItems(plan, items, now);

        String explanation = explain(plan, profile, loadItemViews(plan));
        return toView(plan, loadItemViews(plan), false, explanation);
    }

    /** 查询计划（归属校验）。 */
    public PlanView getPlan(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        boolean stale = profileStale(userId, plan);
        return toView(plan, loadItemViews(plan), stale, null);
    }

    /** 计划列表：ACTIVE 优先，其次 DRAFT，最后 ARCHIVED。 */
    public List<PlanSummaryView> listPlans(Long userId) {
        return planMapper.listPlans(userId).stream()
                .map(plan -> new PlanSummaryView(
                        plan.getId(),
                        parseStatus(plan),
                        plan.getWeekStart(),
                        plan.getTimezone(),
                        parseValidationLevel(plan),
                        plan.getCurrentVersion(),
                        planMapper.findItems(plan.getId(), plan.getCurrentVersion()).size(),
                        plan.getUpdatedAt()
                ))
                .toList();
    }

    /**
     * 激活：事务内行锁重读目标计划并重新校验 DRAFT/归属 → 锁定现有 ACTIVE →
     * 归档旧 ACTIVE、快照版本与项目 → activatePlan 原子更新（status='DRAFT' 条件，影响 0 行抛冲突）。
     * 行锁是正确性保障，userLocks synchronized 仅作为单实例应用层优化保留。
     */
    @Transactional
    public PlanView activate(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlanForUpdate(userId, planId);
        if (plan.getStatus() == null || !PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "只有 DRAFT 计划可以激活");
        }
        HealthProfileView profile = profileService.getProfile(userId);
        List<PlanItemDraft> items = loadDrafts(plan);
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (!result.activatable()) {
            throw blocked(result.copy() == null ? "计划存在警告，激活前请先调整" : result.copy());
        }
        Object lock = userLocks.computeIfAbsent(userId, key -> new Object());
        synchronized (lock) {
            WeeklyPlanRow active = planMapper.findActiveByUserForUpdate(userId);
            if (active != null && !active.getId().equals(planId)) {
                active.setStatus(PlanStatus.ARCHIVED.name());
                active.setUpdatedAt(LocalDateTime.now());
                planMapper.updatePlan(active);
            }
            long newVersion = plan.getCurrentVersion() + 1;
            LocalDateTime now = LocalDateTime.now();
            planMapper.insertVersion(buildVersion(plan, profile, result, now, items, newVersion));
            insertItems(plan, items, now, newVersion);
            plan.setStatus(PlanStatus.ACTIVE.name());
            plan.setCurrentVersion(newVersion);
            plan.setProfileVersionNo(profile.versionNo());
            plan.setCalorieLow(profile.calorieLow());
            plan.setCalorieHigh(profile.calorieHigh());
            plan.setValidationLevel(result.level().name());
            plan.setValidationJson(toJson(ruleHitViews(result)));
            plan.setUpdatedAt(now);
            if (planMapper.activatePlan(plan) == 0) {
                throw new HealthApiException(HealthApiException.CODE_CONFLICT, "计划状态已变化，请刷新后重试");
            }
        }
        String explanation = explain(plan, profile, loadItemViews(plan));
        return toView(plan, loadItemViews(plan), false, explanation);
    }

    /** 编辑：DRAFT 直接返回；ACTIVE 复制为新 DRAFT（规格 6.3）。 */
    @Transactional
    public PlanView edit(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        if (PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            boolean stale = profileStale(userId, plan);
            return toView(plan, loadItemViews(plan), stale, null);
        }
        if (PlanStatus.ARCHIVED.name().equals(plan.getStatus())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "归档计划不可编辑");
        }
        HealthProfileView profile = profileService.getProfile(userId);
        List<PlanItemDraft> items = loadDrafts(plan);
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (result.blocked()) {
            throw blocked(result.copy());
        }
        WeeklyPlanRow copy = new WeeklyPlanRow();
        copy.setUserId(userId);
        copy.setStatus(PlanStatus.DRAFT.name());
        copy.setWeekStart(plan.getWeekStart());
        copy.setTimezone(plan.getTimezone());
        copy.setProfileVersionNo(profile.versionNo());
        copy.setCalorieLow(profile.calorieLow());
        copy.setCalorieHigh(profile.calorieHigh());
        copy.setRulesVersion(PlanValidationService.RULES_VERSION);
        copy.setValidationLevel(result.level().name());
        copy.setValidationJson(toJson(ruleHitViews(result)));
        copy.setSourceSessionId(plan.getSourceSessionId());
        copy.setCurrentVersion(1L);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        planMapper.insertPlan(copy);
        insertVersion(copy, profile, result, now, items);
        insertItems(copy, items, now);
        return toView(copy, loadItemViews(copy), false, null);
    }

    /** PATCH 项目：只允许日期/时间/备注；硬错误拒绝变更不落库。 */
    @Transactional
    public PlanView patchItem(Long userId, Long planId, Long itemId, PatchItemRequest patch) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                    "ACTIVE 计划不可原地修改，请先编辑生成新的草稿");
        }
        WeeklyPlanItemRow item = planMapper.findItemById(itemId);
        if (item == null || !plan.getId().equals(item.getPlanId())
                || !plan.getCurrentVersion().equals(item.getVersionNo())) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划项目不存在");
        }
        WeeklyPlanItemRow updated = new WeeklyPlanItemRow();
        updated.setId(item.getId());
        updated.setPlanId(item.getPlanId());
        updated.setVersionNo(item.getVersionNo());
        updated.setResourceType(item.getResourceType());
        updated.setResourceId(item.getResourceId());
        updated.setName(item.getName());
        updated.setLocalDate(patch.localDate() == null ? item.getLocalDate() : patch.localDate());
        updated.setStartTime(patch.startTime() == null ? item.getStartTime() : patch.startTime());
        updated.setEndTime(patch.endTime() == null ? item.getEndTime() : patch.endTime());
        // note 为 null 表示不修改；空字符串表示清空备注
        updated.setNote(patch.note() == null ? item.getNote() : (patch.note().isBlank() ? null : patch.note()));
        updated.setPlanParamsJson(item.getPlanParamsJson());
        updated.setCreatedAt(item.getCreatedAt());
        updated.setUpdatedAt(LocalDateTime.now());

        HealthProfileView profile = profileService.getProfile(userId);
        List<PlanItemDraft> allItems = new ArrayList<>();
        for (WeeklyPlanItemRow row : planMapper.findItems(plan.getId(), plan.getCurrentVersion())) {
            allItems.add(row.getId().equals(itemId) ? toDraft(updated) : toDraft(row));
        }
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(plan, profile.age()), allItems, resourceCatalog());
        if (result.blocked()) {
            throw blocked(result.copy());
        }
        planMapper.updateItemSchedule(updated);
        plan.setValidationLevel(result.level().name());
        plan.setValidationJson(toJson(ruleHitViews(result)));
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updatePlan(plan);
        boolean stale = profileStale(userId, plan);
        return toView(plan, loadItemViews(plan), stale, null);
    }

    /** 生成解释：PlanResponseAgent 只解释已校验结果，输出后 Guard 由服务内部执行。 */
    private String explain(WeeklyPlanRow plan, HealthProfileView profile, List<PlanItemView> items) {
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        // 计划接口不接收客户端 requestId，用 plan- 前缀合成幂等列所需的请求标识（DB NOT NULL）
        String requestId = "plan-" + traceId;
        try (AgentTraceService.TraceScope scope = agentTraceService.openTrace(traceId, plan.getSourceSessionId(),
                plan.getUserId(), requestId)) {
            HealthPlanResponseAgentService.PlanExplanation explanation = planResponseAgent.explain(profile, items);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("fallbackReason", explanation.fallbackReason());
            agentTraceService.recordEvent("PLAN_EXPLAINED", "RESPOND",
                    Map.of("planId", plan.getId(), "itemCount", items.size()), output);
            return explanation.speechText();
        }
    }

    // ---------- 内部辅助 ----------

    private WeeklyPlanRow requirePlan(Long userId, Long planId) {
        WeeklyPlanRow plan = planMapper.findPlanById(planId, userId);
        if (plan == null) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划不存在或无权访问");
        }
        return plan;
    }

    /** 激活专用查询：FOR UPDATE 行锁 + 归属校验（并发激活的串行化保障）。 */
    private WeeklyPlanRow requirePlanForUpdate(Long userId, Long planId) {
        WeeklyPlanRow plan = planMapper.findPlanByIdForUpdate(planId, userId);
        if (plan == null) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划不存在或无权访问");
        }
        return plan;
    }

    private PlanValidationService.ProfileContext validationContext(HealthProfileView profile) {
        return new PlanValidationService.ProfileContext(profile.age(), profile.calorieLow(), profile.calorieHigh());
    }

    private PlanValidationService.ProfileContext validationContext(WeeklyPlanRow plan, int age) {
        return new PlanValidationService.ProfileContext(age, plan.getCalorieLow(), plan.getCalorieHigh());
    }

    /** 资源目录：从统一审核资源 Provider 构建校验快照（动作资格 + 事实引用）。 */
    private PlanValidationService.ResourceCatalog resourceCatalog() {
        Set<String> knownExercises = new HashSet<>();
        Set<String> planReady = new HashSet<>();
        for (HealthResource resource : resourceProvider.exercises()) {
            knownExercises.add(resource.resourceId());
            if (resource.planReady()) {
                planReady.add(resource.resourceId());
            }
        }
        return new PlanValidationService.ResourceCatalog(
                planReady, knownExercises, Set.copyOf(resourceProvider.allFactIds()));
    }

    private void insertVersion(WeeklyPlanRow plan, HealthProfileView profile,
                               PlanValidationService.ValidationResult result, LocalDateTime now,
                               List<PlanItemDraft> items) {
        planMapper.insertVersion(buildVersion(plan, profile, result, now, items, plan.getCurrentVersion()));
    }

    /**
     * 构建不可变版本（42 号票）：档案快照 + 规则版本 + 来源会话 + 作息事实来源 + 资源快照一次落库。
     * fact_sources_json / resource_snapshot_json 来自生成时的项目与 Provider，历史版本读取不依赖计划根。
     */
    private WeeklyPlanVersionRow buildVersion(WeeklyPlanRow plan, HealthProfileView profile,
                                              PlanValidationService.ValidationResult result,
                                              LocalDateTime now, List<PlanItemDraft> items, long versionNo) {
        WeeklyPlanVersionRow version = new WeeklyPlanVersionRow();
        version.setPlanId(plan.getId());
        version.setVersionNo(versionNo);
        version.setProfileVersionNo(profile.versionNo());
        version.setProfileSnapshotJson(HealthProfileService.profileSnapshot(profile, objectMapper));
        version.setRulesVersion(PlanValidationService.RULES_VERSION);
        version.setSourceSessionId(plan.getSourceSessionId());
        version.setFactSourcesJson(toJson(factSources(items)));
        version.setResourceSnapshotJson(toJson(resourceSnapshot(items)));
        version.setValidationJson(toJson(ruleHitViews(result)));
        version.setCreatedAt(now);
        return version;
    }

    /** 作息事实来源快照：ROUTINE 项目按 factId 解析结构化事实与来源（其他类型不参与）。 */
    private List<Map<String, Object>> factSources(List<PlanItemDraft> items) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (PlanItemDraft item : items) {
            if (!"ROUTINE".equals(item.resourceType())) {
                continue;
            }
            resourceProvider.routineFactById(item.resourceId()).ifPresent(fact -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("factId", fact.factId());
                entry.put("category", fact.category());
                entry.put("sourceName", fact.sourceName());
                entry.put("sourceDetail", fact.sourceDetail());
                facts.add(entry);
            });
        }
        return facts;
    }

    /**
     * 资源快照：Provider 模式/资源版本（审核子集批次版本）+ 每项类型化资源的
     * 来源、来源版本、审核状态、plan_ready 与生成时计划参数。
     * reviewStatus 由 Provider 过滤保证：REVIEWED_DB 模式只暴露 APPROVED 资源，
     * FIXTURE_SEED 模式为离线演示种子（SEED）。
     */
    private List<Map<String, Object>> resourceSnapshot(List<PlanItemDraft> items) {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (PlanItemDraft item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("resourceType", item.resourceType());
            entry.put("resourceId", item.resourceId());
            entry.put("name", item.name());
            entry.put("sourceVersion", resourceProvider.resourceVersion());
            entry.put("reviewStatus", "REVIEWED_DB".equals(resourceProvider.providerMode()) ? "APPROVED" : "SEED");
            entry.put("planParams", item.planParams());
            fillSource(entry, item);
            snapshots.add(entry);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("providerMode", resourceProvider.providerMode());
        envelope.put("resourceVersion", resourceProvider.resourceVersion());
        envelope.put("items", snapshots);
        return List.of(envelope);
    }

    /** 资源来源字段：动作/餐食按类型化 ID 解析；作息事实按 factId 解析；解析失败时保留项目快照字段。 */
    private void fillSource(Map<String, Object> entry, PlanItemDraft item) {
        if ("EXERCISE".equals(item.resourceType())) {
            resourceProvider.exerciseById(item.resourceId()).ifPresent(resource -> {
                entry.put("sourceType", resource.sourceType());
                entry.put("sourceName", resource.sourceName());
                entry.put("planReady", resource.planReady());
            });
        } else if ("MEAL".equals(item.resourceType())) {
            resourceProvider.mealById(item.resourceId()).ifPresent(resource -> {
                entry.put("sourceType", resource.sourceType());
                entry.put("sourceName", resource.sourceName());
                entry.put("planReady", resource.planReady());
            });
        } else if ("ROUTINE".equals(item.resourceType())) {
            resourceProvider.routineFactById(item.resourceId()).ifPresent(fact -> {
                entry.put("sourceType", "STRUCTURED_FACT");
                entry.put("sourceName", fact.sourceName());
            });
        }
    }

    private void insertItems(WeeklyPlanRow plan, List<PlanItemDraft> items, LocalDateTime now) {
        insertItems(plan, items, now, plan.getCurrentVersion());
    }

    private void insertItems(WeeklyPlanRow plan, List<PlanItemDraft> items, LocalDateTime now, long versionNo) {
        if (items.size() > MAX_PLAN_ITEMS) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目数量超出上限");
        }
        for (PlanItemDraft item : items) {
            WeeklyPlanItemRow row = new WeeklyPlanItemRow();
            row.setPlanId(plan.getId());
            row.setVersionNo(versionNo);
            row.setResourceType(item.resourceType());
            row.setResourceId(item.resourceId());
            row.setName(item.name());
            row.setLocalDate(item.localDate());
            row.setStartTime(item.startTime());
            row.setEndTime(item.endTime());
            row.setNote(item.note());
            row.setPlanParamsJson(toJson(item.planParams()));
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            planMapper.insertItem(row);
        }
    }

    private List<PlanItemDraft> loadDrafts(WeeklyPlanRow plan) {
        return planMapper.findItems(plan.getId(), plan.getCurrentVersion()).stream()
                .map(this::toDraft)
                .toList();
    }

    private PlanItemDraft toDraft(WeeklyPlanItemRow row) {
        return new PlanItemDraft(
                row.getResourceType(),
                row.getResourceId(),
                row.getName(),
                row.getLocalDate(),
                row.getStartTime(),
                row.getEndTime(),
                row.getNote(),
                parseParams(row.getPlanParamsJson())
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private PlanView toView(WeeklyPlanRow plan, List<PlanItemView> items, boolean profileStale, String explanation) {
        return new PlanView(
                plan.getId(),
                parseStatus(plan),
                plan.getWeekStart(),
                plan.getTimezone(),
                plan.getProfileVersionNo(),
                plan.getCalorieLow(),
                plan.getCalorieHigh(),
                plan.getRulesVersion(),
                parseValidationLevel(plan),
                parseHits(plan.getValidationJson()),
                plan.getNote(),
                plan.getCurrentVersion(),
                items,
                profileStale,
                explanation,
                plan.getUpdatedAt()
        );
    }

    /** 当前档案版本是否新于计划生成依据（规格 8.2：不静默重算，只标记较旧）。 */
    private boolean profileStale(Long userId, WeeklyPlanRow plan) {
        try {
            return profileService.getProfile(userId).versionNo() > plan.getProfileVersionNo();
        } catch (HealthApiException ignored) {
            return false;
        }
    }

    /** 加载当前版本项目视图（带数据库 id，供 PATCH 引用）。 */
    private List<PlanItemView> loadItemViews(WeeklyPlanRow plan) {
        return planMapper.findItems(plan.getId(), plan.getCurrentVersion()).stream()
                .map(row -> new PlanItemView(
                        row.getId(), row.getResourceType(), row.getResourceId(), row.getName(),
                        row.getLocalDate(), row.getStartTime(), row.getEndTime(), row.getNote(),
                        parseParams(row.getPlanParamsJson())))
                .toList();
    }

    private List<RuleHitView> ruleHitViews(PlanValidationService.ValidationResult result) {
        return result.hits().stream()
                .map(hit -> new RuleHitView(hit.ruleCode(), hit.ruleVersion(), hit.stage(),
                        hit.severity().name(), hit.decision().name(), hit.copy(), hit.detail()))
                .toList();
    }

    private List<RuleHitView> parseHits(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RuleHitView>>() {
            });
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private PlanStatus parseStatus(WeeklyPlanRow plan) {
        try {
            return PlanStatus.valueOf(plan.getStatus());
        } catch (Exception ignored) {
            return PlanStatus.DRAFT;
        }
    }

    private PlanValidationLevel parseValidationLevel(WeeklyPlanRow plan) {
        try {
            return PlanValidationLevel.valueOf(plan.getValidationLevel());
        } catch (Exception ignored) {
            return PlanValidationLevel.DEGRADED;
        }
    }

    private HealthApiException blocked(String copy) {
        return new HealthApiException(HealthApiException.CODE_RISK_BLOCKED,
                copy == null ? "当前情况不适合生成具体计划" : copy);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "计划数据序列化失败");
        }
    }

    /** 本地下周一（缺省周起始；当天是周一时取下周）。 */
    private LocalDate nextMonday(String timezone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (Exception ignored) {
            zone = ZoneId.of(DEFAULT_TIMEZONE);
        }
        return LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    }
}
