package com.diet.health.plan;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.PlanStatus;
import com.diet.health.enums.PlanScope;
import com.diet.health.enums.PlanValidationLevel;
import com.diet.health.module.HealthResource;
import com.diet.health.profile.HealthProfileService;
import com.diet.health.profile.HealthProfileService.HealthProfileView;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.risk.HealthRiskRuleService;
import com.diet.health.session.HealthSessionService;
import com.diet.health.session.HealthSessionState;
import com.diet.mapper.WeeklyPlanMapper;
import com.diet.mapper.PlanWriteRequestMapper;
import com.diet.model.PlanWriteRequestRow;
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
 * DRAFT/UNENABLED/ENABLED/HISTORY 生命周期；生成时经候选前 Guard（档案风险）与组合时校验，
 * 硬错误不持久化、警告可保存不可启用；启用新计划只让旧 ENABLED 回到 UNENABLED。
 * PlanResponseAgent 只解释已校验结果，输出经输出后 Guard 校验。
 */
@Service
public class WeeklyPlanService {

    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final int MAX_PLAN_ITEMS = 500;

    /** 项目日期越界文案（60 号票：合法日期仅为本地周一至周日闭区间）。 */
    private static final String ITEM_DATE_OUT_OF_WEEK_COPY = "计划项目日期超出本周范围（本地周一至周日）";

    private final HealthProfileService profileService;
    private final HealthRiskRuleService riskRuleService;
    private final WeeklyPlanComposerService composer;
    private final PlanValidationService validationService;
    private final WeeklyPlanMapper planMapper;
    private final HealthResourceProvider resourceProvider;
    private final HealthPlanResponseAgentService planResponseAgent;
    private final AgentTraceService agentTraceService;
    private final HealthSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final PlanScopeGuard scopeGuard;
    private final PlanWriteRequestMapper writeRequestMapper;

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
            HealthSessionService sessionService,
            ObjectMapper objectMapper
    ) {
        this(profileService, riskRuleService, composer, validationService, planMapper, resourceProvider,
                planResponseAgent, agentTraceService, sessionService, objectMapper, new PlanScopeGuard(), null);
    }

    /** Spring 入口：计划写请求幂等记录与状态转换使用同一事务。 */
    @org.springframework.beans.factory.annotation.Autowired
    public WeeklyPlanService(
            HealthProfileService profileService,
            HealthRiskRuleService riskRuleService,
            WeeklyPlanComposerService composer,
            PlanValidationService validationService,
            WeeklyPlanMapper planMapper,
            HealthResourceProvider resourceProvider,
            HealthPlanResponseAgentService planResponseAgent,
            AgentTraceService agentTraceService,
            HealthSessionService sessionService,
            ObjectMapper objectMapper,
            PlanWriteRequestMapper writeRequestMapper
    ) {
        this(profileService, riskRuleService, composer, validationService, planMapper, resourceProvider,
                planResponseAgent, agentTraceService, sessionService, objectMapper, new PlanScopeGuard(),
                writeRequestMapper);
    }

    public WeeklyPlanService(
            HealthProfileService profileService,
            HealthRiskRuleService riskRuleService,
            WeeklyPlanComposerService composer,
            PlanValidationService validationService,
            WeeklyPlanMapper planMapper,
            HealthResourceProvider resourceProvider,
            HealthPlanResponseAgentService planResponseAgent,
            AgentTraceService agentTraceService,
            HealthSessionService sessionService,
            ObjectMapper objectMapper,
            PlanScopeGuard scopeGuard
    ) {
        this(profileService, riskRuleService, composer, validationService, planMapper, resourceProvider,
                planResponseAgent, agentTraceService, sessionService, objectMapper, scopeGuard, null);
    }

    WeeklyPlanService(
            HealthProfileService profileService,
            HealthRiskRuleService riskRuleService,
            WeeklyPlanComposerService composer,
            PlanValidationService validationService,
            WeeklyPlanMapper planMapper,
            HealthResourceProvider resourceProvider,
            HealthPlanResponseAgentService planResponseAgent,
            AgentTraceService agentTraceService,
            HealthSessionService sessionService,
            ObjectMapper objectMapper,
            PlanScopeGuard scopeGuard,
            PlanWriteRequestMapper writeRequestMapper
    ) {
        this.profileService = profileService;
        this.riskRuleService = riskRuleService;
        this.composer = composer;
        this.validationService = validationService;
        this.planMapper = planMapper;
        this.resourceProvider = resourceProvider;
        this.planResponseAgent = planResponseAgent;
        this.agentTraceService = agentTraceService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.scopeGuard = scopeGuard;
        this.writeRequestMapper = writeRequestMapper;
    }

    /** 生成周计划草稿：候选前 Guard → 组合 → 组合时校验 → 持久化（硬错误不落库）。 */
    @Transactional
    public PlanView createDraft(Long userId, DraftPlanRequest request) {
        throw new HealthApiException(HealthApiException.CODE_CONFLICT,
                "旧的通用草稿入口已移除，请先在聊天中完成对应范围简报并使用范围生成入口");
    }

    /** 训练 Agent 生成后的短事务写入：事务内只重读档案/会话并执行最终 Guard 与版本快照。 */
    @Transactional
    public PlanView persistGeneratedDraft(Long userId, DraftPlanRequest request, List<PlanItemDraft> items,
                                          String generationSource, Map<String, Object> generationMetadata,
                                          String explanation) {
        return persistScopedGeneratedDraft(userId, request, PlanScope.EXERCISE, items,
                generationSource, generationMetadata, explanation);
    }

    /** 范围生成的唯一事务写入口；调用者在事务外完成对应子计划生成。 */
    @Transactional
    public PlanView persistScopedGeneratedDraft(Long userId, DraftPlanRequest request, PlanScope scope,
                                                List<PlanItemDraft> items, String generationSource,
                                                Map<String, Object> generationMetadata, String explanation) {
        if (request == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划生成请求不能为空");
        }
        HealthProfileView profile = requireProfileRiskPassed(userId);
        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? DEFAULT_TIMEZONE : request.timezone();
        LocalDate weekStart = request.weekStart();
        if (weekStart == null || weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "已确认训练简报的目标周必须从周一开始");
        }
        if (request != null && request.planScope() != null && request.planScope() != scope) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "请求范围与写入范围不一致");
        }
        scopeGuard.requireCompatible(scope, items);
        HealthSessionState session = sessionService.loadOrCreate(request.sessionId(), userId);
        // 生成写入前服务端重读会话简报：只要求当前完整且目标周一致，不依赖历史确认字段或确认版本。
        boolean briefReady = switch (scope) {
            case EXERCISE -> session.planBrief() != null && session.planBrief().isComplete()
                    && weekStart.equals(session.planBrief().weekStart());
            case MEAL -> session.mealPlanBrief() != null && session.mealPlanBrief().isComplete()
                    && weekStart.equals(session.mealPlanBrief().weekStart());
            case COMPOSITE -> session.planBrief() != null && session.planBrief().isComplete()
                    && session.mealPlanBrief() != null && session.mealPlanBrief().isComplete()
                    && weekStart.equals(session.planBrief().weekStart())
                    && weekStart.equals(session.mealPlanBrief().weekStart());
        };
        if (!briefReady) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "计划简报不完整或已变化，请回到聊天整理简报后重新生成");
        }
        requireItemsInWeek(items, weekStart);
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (result.blocked()) {
            throw blocked(result.copy());
        }

        WeeklyPlanRow plan = new WeeklyPlanRow();
        plan.setUserId(userId);
        // 生成入口可以是餐食-only/训练-only，但用户侧统一看作综合周计划。
        plan.setPlanScope(PlanScope.COMPOSITE.name());
        plan.setStatus(PlanStatus.DRAFT.name());
        plan.setName(defaultPlanName(weekStart));
        plan.setWeekStart(weekStart);
        plan.setTimezone(timezone);
        plan.setProfileVersionNo(profile.versionNo());
        plan.setCalorieLow(profile.calorieLow());
        plan.setCalorieHigh(profile.calorieHigh());
        plan.setRulesVersion(PlanValidationService.RULES_VERSION);
        plan.setValidationLevel(result.level().name());
        plan.setValidationJson(toJson(ruleHitViews(result)));
        plan.setSourceSessionId(session.sessionId());
        plan.setGenerationSource(generationSource == null || generationSource.isBlank()
                ? "FALLBACK" : generationSource);
        plan.setGenerationMetadataJson(toJson(generationMetadata == null ? Map.of() : generationMetadata));
        plan.setCurrentVersion(1L);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(plan.getCreatedAt());
        planMapper.insertPlan(plan);
        insertVersion(plan, profile, result, plan.getCreatedAt(), items, generationMetadata);
        insertItems(plan, items, plan.getCreatedAt());
        return toView(plan, loadItemViews(plan), false, explanation, generationSource);
    }

    /** 查询计划（归属校验）。 */
    public PlanView getPlan(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        boolean stale = profileStale(userId, plan);
        return toView(plan, loadItemViews(plan), stale, null, null);
    }

    /** 计划列表：ENABLED 优先，其次草稿、未启用和历史。 */
    public List<PlanSummaryView> listPlans(Long userId) {
        return planMapper.listPlans(userId).stream()
                .map(plan -> new PlanSummaryView(
                        plan.getId(),
                        plan.getName(),
                        parseStatus(plan),
                        plan.getWeekStart(),
                        plan.getTimezone(),
                        parseValidationLevel(plan),
                        plan.getCurrentVersion(),
                        planMapper.findItems(plan.getId(), plan.getCurrentVersion()).size(),
                        plan.getGenerationSource(),
                        plan.getUpdatedAt(),
                        scopeGuard.parse(plan.getPlanScope())
                ))
                .toList();
    }

    /** 兼容旧调用名；新的 HTTP 契约使用 enable。 */
    @Transactional
    public PlanView activate(Long userId, Long planId) {
        return enableInternal(userId, planId, new PlanWriteRequest(null, null), false);
    }

    @Transactional
    public PlanView confirm(Long userId, Long planId, PlanWriteRequest request) {
        return mutateState(userId, planId, request, "CONFIRM", true, PlanStatus.DRAFT.name(), PlanStatus.UNENABLED.name());
    }

    @Transactional
    public PlanView enable(Long userId, Long planId, PlanWriteRequest request) {
        return enableInternal(userId, planId, request, true);
    }

    private PlanView enableInternal(Long userId, Long planId, PlanWriteRequest request, boolean strictRequest) {
        PlanWriteRequest normalized = normalizeRequest(request, strictRequest);
        PlanView replay = replay(userId, planId, normalized, "ENABLE");
        if (replay != null) return replay;
        WeeklyPlanRow plan = requirePlanForUpdate(userId, planId);
        checkExpectedVersion(plan, normalized, strictRequest);
        if (!PlanStatus.UNENABLED.name().equals(plan.getStatus())) {
            throw stateConflict("只有 UNENABLED 计划可以启用");
        }
        HealthProfileView profile = requireProfileRiskPassed(userId);
        List<PlanItemDraft> items = loadDrafts(plan);
        requireItemsInWeek(items, plan.getWeekStart());
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (!result.activatable()) throw validationFailure(result, "启用前请先调整计划");
        Object lock = userLocks.computeIfAbsent(userId, key -> new Object());
        synchronized (lock) {
            WeeklyPlanRow enabled = planMapper.findActiveByUserForUpdate(userId);
            if (enabled != null && !enabled.getId().equals(planId)) {
                enabled.setStatus(PlanStatus.UNENABLED.name());
                enabled.setUpdatedAt(LocalDateTime.now());
                planMapper.updatePlan(enabled);
            }
            LocalDateTime now = LocalDateTime.now();
            long nextVersion = plan.getCurrentVersion() + 1;
            plan.setProfileVersionNo(profile.versionNo());
            plan.setCalorieLow(profile.calorieLow());
            plan.setCalorieHigh(profile.calorieHigh());
            plan.setRulesVersion(PlanValidationService.RULES_VERSION);
            planMapper.insertVersion(buildVersion(plan, profile, result, now, items, nextVersion,
                    generationMetadata(plan)));
            insertItems(plan, items, now, nextVersion);
            plan.setStatus(PlanStatus.ENABLED.name());
            plan.setCurrentVersion(nextVersion);
            plan.setUpdatedAt(now);
            plan.setValidationLevel(result.level().name());
            plan.setValidationJson(toJson(ruleHitViews(result)));
            if (planMapper.activatePlan(plan) == 0) {
                throw stateConflict("计划状态已变化，请刷新后重试");
            }
        }
        PlanView resultView = toView(plan, loadItemViews(plan), false, explain(plan, profile, loadItemViews(plan)), null);
        return remember(userId, planId, normalized, "ENABLE", resultView);
    }

    @Transactional
    public PlanView disable(Long userId, Long planId, PlanWriteRequest request) {
        return mutateState(userId, planId, request, "DISABLE", true, PlanStatus.ENABLED.name(), PlanStatus.UNENABLED.name());
    }

    @Transactional
    public PlanView archive(Long userId, Long planId, PlanWriteRequest request) {
        return mutateState(userId, planId, request, "ARCHIVE", true,
                PlanStatus.DRAFT.name(), PlanStatus.HISTORY.name(), PlanStatus.UNENABLED.name());
    }

    private PlanView mutateState(Long userId, Long planId, PlanWriteRequest request, String operation,
                                 boolean strictRequest, String expectedState, String newState, String... moreExpected) {
        PlanWriteRequest normalized = normalizeRequest(request, strictRequest);
        PlanView replay = replay(userId, planId, normalized, operation);
        if (replay != null) return replay;
        WeeklyPlanRow plan = requirePlanForUpdate(userId, planId);
        checkExpectedVersion(plan, normalized, strictRequest);
        Set<String> expected = new HashSet<>();
        expected.add(expectedState);
        java.util.Collections.addAll(expected, moreExpected);
        if (!expected.contains(plan.getStatus())) throw stateConflict("当前状态不允许执行该操作");
        plan.setStatus(newState);
        plan.setUpdatedAt(LocalDateTime.now());
        if (planMapper.updatePlan(plan) == 0) throw stateConflict("计划状态已变化，请刷新后重试");
        PlanView view = toView(plan, loadItemViews(plan), false, null, null);
        return remember(userId, planId, normalized, operation, view);
    }

    /** 编辑：草稿/未启用可直接编辑，已启用创建草稿副本，历史只能复制。 */
    @Transactional
    public PlanView edit(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        PlanScope scope = scopeGuard.parse(plan.getPlanScope());
        if (PlanStatus.DRAFT.name().equals(plan.getStatus()) || PlanStatus.UNENABLED.name().equals(plan.getStatus())) {
            scopeGuard.requireCompatible(scope, loadDrafts(plan));
            boolean stale = profileStale(userId, plan);
            return toView(plan, loadItemViews(plan), stale, null, null);
        }
        if (PlanStatus.HISTORY.name().equals(plan.getStatus())) {
            throw stateConflict("历史计划只读，请先复制为新的草稿");
        }
        HealthProfileView profile = requireProfileRiskPassed(userId);
        List<PlanItemDraft> items = loadDrafts(plan);
        requireItemsInWeek(items, plan.getWeekStart());
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (result.blocked()) {
            throw blocked(result.copy());
        }
        WeeklyPlanRow copy = new WeeklyPlanRow();
        copy.setUserId(userId);
        copy.setPlanScope(plan.getPlanScope());
        copy.setStatus(PlanStatus.DRAFT.name());
        copy.setName(plan.getName() + "（副本）");
        copy.setWeekStart(plan.getWeekStart());
        copy.setTimezone(plan.getTimezone());
        copy.setProfileVersionNo(profile.versionNo());
        copy.setCalorieLow(profile.calorieLow());
        copy.setCalorieHigh(profile.calorieHigh());
        copy.setRulesVersion(PlanValidationService.RULES_VERSION);
        copy.setValidationLevel(result.level().name());
        copy.setValidationJson(toJson(ruleHitViews(result)));
        copy.setSourceSessionId(plan.getSourceSessionId());
        copy.setGenerationSource(plan.getGenerationSource());
        copy.setGenerationMetadataJson(plan.getGenerationMetadataJson());
        copy.setCurrentVersion(1L);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        planMapper.insertPlan(copy);
        insertVersion(copy, profile, result, now, items, generationMetadata(copy));
        insertItems(copy, items, now);
        return toView(copy, loadItemViews(copy), false, null, null);
    }

    @Transactional
    public PlanView copy(Long userId, Long planId, PlanWriteRequest request) {
        PlanWriteRequest normalized = normalizeRequest(request, true);
        PlanView replay = replay(userId, planId, normalized, "COPY");
        if (replay != null) return replay;
        WeeklyPlanRow source = requirePlanForUpdate(userId, planId);
        checkExpectedVersion(source, normalized, true);
        if (!PlanStatus.HISTORY.name().equals(source.getStatus())) {
            throw stateConflict("只有 HISTORY 计划可以复制");
        }
        HealthProfileView profile = requireProfileRiskPassed(userId);
        List<PlanItemDraft> items = loadDrafts(source);
        requireItemsInWeek(items, source.getWeekStart());
        PlanValidationService.ValidationResult validation = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (validation.blocked()) throw validationFailure(validation, "历史计划内容已不满足当前规则");
        WeeklyPlanRow copy = copyPlanRow(source, profile, validation);
        PlanView view = toView(copy, loadItemViews(copy), false, null, null);
        return remember(userId, source.getId(), normalized, "COPY", view);
    }

    /** 兼容旧删除入口；统一生命周期下删除语义由 archive 负责。 */
    @Transactional
    public void deleteDraft(Long userId, Long planId) {
        WeeklyPlanRow plan = requirePlanForUpdate(userId, planId);
        if (!PlanStatus.DRAFT.name().equals(plan.getStatus())) {
            throw stateConflict("只有 DRAFT 计划可以物理删除，请使用归档操作");
        }
        planMapper.deleteItemsByPlanId(planId);
        planMapper.deleteVersionsByPlanId(planId);
        if (planMapper.deletePlan(planId, userId) == 0) {
            throw new HealthApiException(HealthApiException.CODE_CONFLICT, "计划状态已变化，请刷新后重试");
        }
    }

    /** 一次性替换保存项目集合；失败时事务整体回滚。 */
    @Transactional
    public PlanView updateItems(Long userId, Long planId, PlanItemsWriteRequest request) {
        if (request == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划保存请求不能为空");
        }
        PlanWriteRequest normalized = normalizeRequest(request == null ? null
                : new PlanWriteRequest(request.requestId(), request.expectedVersion()), true);
        PlanView replay = replay(userId, planId, normalized, "ITEMS");
        if (replay != null) return replay;
        WeeklyPlanRow plan = requirePlanForUpdate(userId, planId);
        checkExpectedVersion(plan, normalized, true);
        if (!(PlanStatus.DRAFT.name().equals(plan.getStatus()) || PlanStatus.UNENABLED.name().equals(plan.getStatus()))) {
            throw stateConflict("只有 DRAFT 或 UNENABLED 计划可以编辑项目");
        }
        Set<Long> currentItemIds = planMapper.findItems(plan.getId(), plan.getCurrentVersion()).stream()
                .map(WeeklyPlanItemRow::getId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, WeeklyPlanItemRow> currentItems = planMapper.findItems(plan.getId(), plan.getCurrentVersion()).stream()
                .filter(item -> item.getId() != null)
                .collect(java.util.stream.Collectors.toMap(WeeklyPlanItemRow::getId, item -> item));
        List<PlanItemDraft> items = request.items().stream().map(item -> {
            if (item == null) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目不能为空");
            }
            if (item.id() != null && !currentItemIds.contains(item.id())) {
                throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "计划项目不存在或不属于当前计划");
            }
            requireItemParams(item.planParams());
            return canonicalizeItem(item, currentItems.get(item.id()));
        }).toList();
        if (items.isEmpty()) throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划至少需要一个项目");
        requireItemsInWeek(items, plan.getWeekStart());
        scopeGuard.requireCompatible(scopeGuard.parse(plan.getPlanScope()), items);
        HealthProfileView profile = requireProfileRiskPassed(userId);
        PlanValidationService.ValidationResult validation = validationService.validate(
                validationContext(profile), items, resourceCatalog());
        if (validation.blocked()) throw validationFailure(validation, "计划项目不满足保存条件");
        long nextVersion = plan.getCurrentVersion() + 1;
        LocalDateTime now = LocalDateTime.now();
        planMapper.insertVersion(buildVersion(plan, profile, validation, now, items, nextVersion, generationMetadata(plan)));
        insertItems(plan, items, now, nextVersion);
        plan.setCurrentVersion(nextVersion);
        if (request.name() != null) {
            plan.setName(normalizePlanName(request.name()));
        }
        plan.setValidationLevel(validation.level().name());
        plan.setValidationJson(toJson(ruleHitViews(validation)));
        plan.setUpdatedAt(now);
        planMapper.updatePlan(plan);
        PlanView view = toView(plan, loadItemViews(plan), profileStale(userId, plan), null, null);
        return remember(userId, planId, normalized, "ITEMS", view);
    }

    /** PATCH 项目：保留旧接口，但新的编辑器使用 PUT /items。 */
    @Transactional
    public PlanView patchItem(Long userId, Long planId, Long itemId, PatchItemRequest patch) {
        WeeklyPlanRow plan = requirePlan(userId, planId);
        if (!(PlanStatus.DRAFT.name().equals(plan.getStatus()) || PlanStatus.UNENABLED.name().equals(plan.getStatus()))) {
            throw stateConflict("ENABLED 计划不可原地修改，请先创建未启用副本");
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

        HealthProfileView profile = requireProfileRiskPassed(userId);
        List<PlanItemDraft> allItems = new ArrayList<>();
        for (WeeklyPlanItemRow row : planMapper.findItems(plan.getId(), plan.getCurrentVersion())) {
            allItems.add(row.getId().equals(itemId) ? toDraft(updated) : toDraft(row));
        }
        requireItemsInWeek(allItems, plan.getWeekStart());
        scopeGuard.requireCompatible(scopeGuard.parse(plan.getPlanScope()), allItems);
        PlanValidationService.ValidationResult result = validationService.validate(
                validationContext(plan, profile.age()), allItems, resourceCatalog());
        if (result.blocked()) throw validationFailure(result, "项目调整不满足保存条件");
        planMapper.updateItemSchedule(updated);
        plan.setValidationLevel(result.level().name());
        plan.setValidationJson(toJson(ruleHitViews(result)));
        plan.setUpdatedAt(LocalDateTime.now());
        planMapper.updatePlan(plan);
        boolean stale = profileStale(userId, plan);
        return toView(plan, loadItemViews(plan), stale, null, null);
    }

    /** 生成解释：PlanResponseAgent 只解释已校验结果，输出后 Guard 由服务内部执行。 */
    private String explain(WeeklyPlanRow plan, HealthProfileView profile, List<PlanItemView> items) {
        String traceId = "trace_" + UUID.randomUUID().toString().replace("-", "");
        // 计划接口不接收客户端 requestId，用 plan- 前缀合成幂等列所需的请求标识（DB NOT NULL）
        String requestId = "plan-" + traceId;
        try (AgentTraceService.TraceScope scope = agentTraceService.openTrace(traceId, plan.getSourceSessionId(),
                plan.getUserId(), requestId)) {
            HealthPlanResponseAgentService.PlanExplanation explanation = planResponseAgent.explain(profile, items);
            if (explanation == null) {
                return "计划已校验并启用。";
            }
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("fallbackReason", explanation.fallbackReason());
            agentTraceService.recordEvent("PLAN_EXPLAINED", "RESPOND",
                    Map.of("planId", plan.getId(), "itemCount", items.size()), output);
            return explanation.speechText();
        }
    }

    // ---------- 内部辅助 ----------

    /**
     * 统一档案风险 Guard（62 号票）：所有计划写入口（createDraft/ACTIVE 复制/PATCH/activate）
     * 在 Java 领域边界重新评估当前档案风险，不能依赖调用方先走聊天；
     * BLOCK_PLAN 直接抛 RISK_BLOCKED，不持久化计划/版本/项目。
     */
    private HealthProfileView requireProfileRiskPassed(Long userId) {
        HealthProfileView profile = profileService.getProfile(userId);
        HealthRiskRuleService.RiskDecision risk = riskRuleService.assessProfile(
                profile.age(), true, profile.riskConditions());
        if (risk.blocked()) {
            throw blocked(risk.copy());
        }
        return profile;
    }

    /**
     * 统一时间/日期不变量 Guard（60 号票）：周计划合法项目日期仅为
     * [weekStart, weekStart+6]（本地周一至周日）闭区间，越界为稳定参数错误（BAD_REQUEST）。
     * 所有计划写入口在持久化前调用，任何越界项目都不得落库或激活。
     */
    private void requireItemsInWeek(List<PlanItemDraft> items, LocalDate weekStart) {
        LocalDate weekEnd = weekStart.plusDays(6);
        for (PlanItemDraft item : items) {
            if (item.localDate() == null) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划项目日期不能为空");
            }
            if (item.localDate().isBefore(weekStart) || item.localDate().isAfter(weekEnd)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                        ITEM_DATE_OUT_OF_WEEK_COPY + "：" + item.localDate());
            }
        }
    }

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

    /** 资源目录：从统一审核资源 Provider 构建校验快照。 */
    private PlanValidationService.ResourceCatalog resourceCatalog() {
        Set<String> knownExercises = new HashSet<>();
        Set<String> planReady = new HashSet<>();
        for (HealthResource resource : resourceProvider.exercises()) {
            knownExercises.add(resource.resourceId());
            if (resource.planReady()) {
                planReady.add(resource.resourceId());
            }
        }
        Set<String> knownMeals = resourceProvider.planMealCandidates().stream()
                .map(com.diet.health.module.PlanMealCandidate::resourceId).collect(java.util.stream.Collectors.toSet());
        return new PlanValidationService.ResourceCatalog(
                planReady, knownExercises, Set.copyOf(resourceProvider.allFactIds()), knownMeals);
    }

    private void insertVersion(WeeklyPlanRow plan, HealthProfileView profile,
                               PlanValidationService.ValidationResult result, LocalDateTime now,
                               List<PlanItemDraft> items) {
        planMapper.insertVersion(buildVersion(plan, profile, result, now, items, plan.getCurrentVersion(), null));
    }

    private void insertVersion(WeeklyPlanRow plan, HealthProfileView profile,
                               PlanValidationService.ValidationResult result, LocalDateTime now,
                               List<PlanItemDraft> items, Map<String, Object> generationMetadata) {
        planMapper.insertVersion(buildVersion(plan, profile, result, now, items, plan.getCurrentVersion(), generationMetadata));
    }

    /**
     * 构建不可变版本（42 号票）：档案快照 + 规则版本 + 来源会话 + 作息事实来源 + 资源快照一次落库。
     * fact_sources_json / resource_snapshot_json 来自生成时的项目与 Provider，历史版本读取不依赖计划根。
     */
    private WeeklyPlanVersionRow buildVersion(WeeklyPlanRow plan, HealthProfileView profile,
                                              PlanValidationService.ValidationResult result,
                                              LocalDateTime now, List<PlanItemDraft> items, long versionNo,
                                              Map<String, Object> generationMetadata) {
        WeeklyPlanVersionRow version = new WeeklyPlanVersionRow();
        version.setPlanId(plan.getId());
        version.setPlanScope(plan.getPlanScope());
        version.setVersionNo(versionNo);
        version.setProfileVersionNo(profile.versionNo());
        version.setProfileSnapshotJson(HealthProfileService.profileSnapshot(profile, objectMapper));
        version.setRulesVersion(PlanValidationService.RULES_VERSION);
        version.setSourceSessionId(plan.getSourceSessionId());
        version.setFactSourcesJson(toJson(factSources(items)));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("resources", resourceSnapshot(items));
        if (generationMetadata != null) {
            snapshot.put("generation", generationMetadata);
        }
        version.setResourceSnapshotJson(toJson(snapshot));
        version.setValidationJson(toJson(ruleHitViews(result)));
        version.setCreatedAt(now);
        return version;
    }

    private Map<String, Object> generationMetadata(WeeklyPlanRow plan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (plan.getGenerationMetadataJson() != null && !plan.getGenerationMetadataJson().isBlank()) {
            try {
                metadata.putAll(objectMapper.readValue(plan.getGenerationMetadataJson(),
                        new TypeReference<Map<String, Object>>() { }));
            } catch (JsonProcessingException ignored) {
                // 旧计划元数据无法解析时仍保留根来源，避免后续版本继续丢失来源。
            }
        }
        if (plan.getGenerationSource() != null && !plan.getGenerationSource().isBlank()) {
            metadata.put("generationSource", plan.getGenerationSource());
        }
        return Map.copyOf(metadata);
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
            entry.put("reviewStatus", resourceProvider.providerMode().isReviewed() ? "APPROVED" : "SEED");
            entry.put("planParams", item.planParams());
            fillSource(entry, item);
            snapshots.add(entry);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("providerMode", resourceProvider.providerMode().name());
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

    private PlanView toView(WeeklyPlanRow plan, List<PlanItemView> items, boolean profileStale, String explanation,
                            String generationSource) {
        return new PlanView(
                plan.getId(),
                plan.getName(),
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
                generationSource == null ? plan.getGenerationSource() : generationSource,
                plan.getUpdatedAt(),
                scopeGuard.parse(plan.getPlanScope())
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

    private WeeklyPlanRow copyPlanRow(WeeklyPlanRow source, HealthProfileView profile,
                                      PlanValidationService.ValidationResult validation) {
        List<PlanItemDraft> items = loadDrafts(source);
        WeeklyPlanRow copy = new WeeklyPlanRow();
        copy.setUserId(source.getUserId());
        copy.setPlanScope(PlanScope.COMPOSITE.name());
        copy.setName((source.getName() == null ? defaultPlanName(source.getWeekStart()) : source.getName()) + "（副本）");
        copy.setStatus(PlanStatus.DRAFT.name());
        copy.setWeekStart(source.getWeekStart());
        copy.setTimezone(source.getTimezone());
        copy.setProfileVersionNo(profile.versionNo());
        copy.setCalorieLow(profile.calorieLow());
        copy.setCalorieHigh(profile.calorieHigh());
        copy.setRulesVersion(PlanValidationService.RULES_VERSION);
        copy.setValidationLevel(validation.level().name());
        copy.setValidationJson(toJson(ruleHitViews(validation)));
        copy.setSourceSessionId(source.getSourceSessionId());
        copy.setGenerationSource(source.getGenerationSource());
        copy.setGenerationMetadataJson(source.getGenerationMetadataJson());
        copy.setCurrentVersion(1L);
        LocalDateTime now = LocalDateTime.now();
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        planMapper.insertPlan(copy);
        insertVersion(copy, profile, validation, now, items, generationMetadata(copy));
        insertItems(copy, items, now);
        return copy;
    }

    private String defaultPlanName(LocalDate weekStart) {
        return "每周综合计划 " + weekStart;
    }

    private PlanWriteRequest normalizeRequest(PlanWriteRequest request, boolean strict) {
        if (!strict && (request == null || request.requestId() == null || request.requestId().isBlank())) {
            return new PlanWriteRequest(null, null);
        }
        if (request == null || request.requestId() == null || request.requestId().isBlank()
                || request.requestId().trim().length() > 128) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "requestId 不能为空且长度不能超过 128 个字符");
        }
        if (strict && request.expectedVersion() == null) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "expectedVersion 不能为空");
        }
        return new PlanWriteRequest(request.requestId().trim(), request.expectedVersion());
    }

    private void checkExpectedVersion(WeeklyPlanRow plan, PlanWriteRequest request, boolean strict) {
        if (strict && !plan.getCurrentVersion().equals(request.expectedVersion())) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_VERSION_CONFLICT,
                    "计划版本已变化，请刷新后重试");
        }
    }

    private PlanView replay(Long userId, Long planId, PlanWriteRequest request, String operation) {
        if (writeRequestMapper == null || request == null || request.requestId() == null) return null;
        PlanWriteRequestRow previous = writeRequestMapper.find(userId, request.requestId());
        if (previous == null) return null;
        if (!operation.equals(previous.getOperation())) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT,
                    "requestId 已用于其他计划操作");
        }
        if (!planId.equals(previous.getPlanId())) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_IDEMPOTENCY_CONFLICT,
                    "requestId 已用于其他计划");
        }
        try {
            return objectMapper.readValue(previous.getResponseJson(), PlanView.class);
        } catch (JsonProcessingException error) {
            throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "幂等响应快照损坏");
        }
    }

    private PlanView remember(Long userId, Long planId, PlanWriteRequest request, String operation, PlanView view) {
        if (writeRequestMapper == null || request == null || request.requestId() == null) return view;
        PlanWriteRequestRow row = new PlanWriteRequestRow();
        row.setUserId(userId);
        row.setRequestId(request.requestId());
        row.setPlanId(planId);
        row.setOperation(operation);
        row.setResponseJson(toJson(view));
        row.setCreatedAt(LocalDateTime.now());
        try {
            writeRequestMapper.insert(row);
        } catch (RuntimeException error) {
            PlanWriteRequestRow previous = writeRequestMapper.find(userId, request.requestId());
            if (previous != null && operation.equals(previous.getOperation())) {
                return replay(userId, planId, request, operation);
            }
            throw error;
        }
        return view;
    }

    /**
     * 计划写入的资源事实边界：名称、餐食热量、餐次和动作部位均从审核 Provider 重读；
     * 客户端只能提交日期、时间、备注以及动作处方。替换动作时丢弃旧处方，使用确定性默认值。
     */
    private PlanItemDraft canonicalizeItem(PlanItemWrite item, WeeklyPlanItemRow current) {
        if (item.resourceType() == null || item.resourceId() == null || item.resourceId().isBlank()) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_RESOURCE_INVALID, "计划资源不能为空");
        }
        String resourceType = item.resourceType().trim().toUpperCase();
        String resourceId = item.resourceId().trim();
        if ("MEAL".equals(resourceType)) {
            HealthResource resource = resourceProvider.mealById(resourceId)
                    .orElseThrow(() -> new HealthApiException(HealthApiException.CODE_PLAN_RESOURCE_INVALID,
                            "餐食资源不存在或未通过审核"));
            Map<String, Object> params = new LinkedHashMap<>();
            firstTag(resource, "mealTime").ifPresent(value -> params.put("mealTime", value));
            if (resource.nutrition() != null && resource.nutrition().caloriesKcal() != null) {
                params.put("caloriesKcal", resource.nutrition().caloriesKcal().intValue());
            }
            return new PlanItemDraft("MEAL", resourceId, resource.name(), item.localDate(), item.startTime(),
                    item.endTime(), normalizeNote(item.note()), params);
        }
        if ("EXERCISE".equals(resourceType)) {
            HealthResource resource = resourceProvider.exerciseById(resourceId)
                    .orElseThrow(() -> new HealthApiException(HealthApiException.CODE_PLAN_RESOURCE_INVALID,
                            "动作资源不存在或未通过审核"));
            if (!resource.planReady()) {
                throw new HealthApiException(HealthApiException.CODE_PLAN_RESOURCE_INVALID,
                        "动作资源尚未达到周计划资格");
            }
            String bodyPart = firstTag(resource, "primaryBodyPart")
                    .or(() -> firstTag(resource, "bodyParts"))
                    .orElse("全身");
            boolean replacement = current == null || !resourceId.equals(current.getResourceId());
            // 资源替换仍由服务端重读事实；同一次批量请求中若带有处方，则保留用户明确提交的正整数。
            // 替换请求缺少处方时，才回退到新资源的确定性默认值，不沿用旧动作处方。
            Map<String, Object> params = replacement
                    ? editableExerciseParams(item.planParams(), defaultExerciseParams(resource))
                    : editableExerciseParams(item.planParams(), current);
            params.put("bodyPart", bodyPart);
            return new PlanItemDraft("EXERCISE", resourceId, resource.name(), item.localDate(), item.startTime(),
                    item.endTime(), normalizeNote(item.note()), params);
        }
        throw new HealthApiException(HealthApiException.CODE_PLAN_RESOURCE_INVALID,
                "计划只支持审核餐食和可入计划动作");
    }

    private java.util.Optional<String> firstTag(HealthResource resource, String key) {
        List<String> values = resource.tags().getOrDefault(key, List.of());
        return values.stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    private Map<String, Object> defaultExerciseParams(HealthResource resource) {
        String difficulty = firstTag(resource, "difficulty").orElse("进阶");
        int duration = switch (difficulty) {
            case "入门" -> 20;
            case "挑战" -> 40;
            default -> 30;
        };
        int sets = "挑战".equals(difficulty) ? 4 : "入门".equals(difficulty) ? 2 : 3;
        int reps = "挑战".equals(difficulty) ? 8 : 10;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("durationMinutes", duration);
        params.put("sets", sets);
        params.put("reps", reps);
        return params;
    }

    private Map<String, Object> editableExerciseParams(Map<String, Object> submitted, WeeklyPlanItemRow current) {
        Map<String, Object> previous = parseParams(current.getPlanParamsJson());
        return editableExerciseParams(submitted, previous, 30, 3, 10);
    }

    private Map<String, Object> editableExerciseParams(Map<String, Object> submitted,
                                                       Map<String, Object> defaults) {
        return editableExerciseParams(submitted, defaults,
                positiveDefault(defaults, "durationMinutes", 30),
                positiveDefault(defaults, "sets", 3),
                positiveDefault(defaults, "reps", 10));
    }

    private Map<String, Object> editableExerciseParams(Map<String, Object> submitted,
                                                       Map<String, Object> previous,
                                                       int durationFallback, int setsFallback,
                                                       int repsFallback) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("durationMinutes", positiveParam(submitted, "durationMinutes", previous, durationFallback));
        params.put("sets", positiveParam(submitted, "sets", previous, setsFallback));
        params.put("reps", positiveParam(submitted, "reps", previous, repsFallback));
        return params;
    }

    private int positiveDefault(Map<String, Object> defaults, String key, int fallback) {
        Object value = defaults == null ? null : defaults.get(key);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }

    private int positiveParam(Map<String, Object> submitted, String key, Map<String, Object> previous, int fallback) {
        Object value = submitted == null ? null : submitted.get(key);
        if (value == null) value = previous.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() <= 0
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划参数必须为正整数：" + key);
        }
        return number.intValue();
    }

    private String normalizeNote(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    private void requireItemParams(Map<String, Object> params) {
        if (params == null) {
            return;
        }
        Set<String> allowed = Set.of("mealTime", "caloriesKcal", "bodyPart", "durationMinutes", "sets", "reps");
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "不允许的计划参数：" + key);
            }
            Object value = params.get(key);
            if ("mealTime".equals(key)) {
                if (!(value instanceof String) || ((String) value).isBlank()) {
                    throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "餐次参数格式不正确");
                }
            } else if ("bodyPart".equals(key)) {
                if (!(value instanceof String) || ((String) value).isBlank()) {
                    throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "训练部位参数格式不正确");
                }
            } else if (!(value instanceof Number number) || number.doubleValue() <= 0
                    || number.doubleValue() != Math.rint(number.doubleValue())) {
                throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST, "计划参数必须为正整数：" + key);
            }
        }
    }

    private String normalizePlanName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > 128) {
            throw new HealthApiException(HealthApiException.CODE_BAD_REQUEST,
                    "计划名称不能为空且长度不能超过 128 个字符");
        }
        return name;
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
        } catch (Exception error) {
            throw new HealthApiException(HealthApiException.CODE_PLAN_STATE_CONFLICT, "计划状态无效或已损坏");
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

    private HealthApiException validationFailure(PlanValidationService.ValidationResult result, String fallback) {
        String copy = result.copy() == null ? fallback : result.copy();
        boolean time = result.hits().stream().anyMatch(hit -> Set.of("SCHEDULE_OVERLAP", "INVALID_TIME_RANGE",
                "INVALID_TIME_GRANULARITY", "CROSS_MIDNIGHT").contains(hit.ruleCode()));
        boolean resource = result.hits().stream().anyMatch(hit -> Set.of("RESOURCE_NOT_FOUND",
                "RESOURCE_NOT_PLAN_READY").contains(hit.ruleCode()));
        String code = time ? HealthApiException.CODE_PLAN_TIME_CONFLICT
                : resource ? HealthApiException.CODE_PLAN_RESOURCE_INVALID : HealthApiException.CODE_RISK_BLOCKED;
        return new HealthApiException(code, copy);
    }

    private HealthApiException stateConflict(String message) {
        return new HealthApiException(HealthApiException.CODE_PLAN_STATE_CONFLICT, message);
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
