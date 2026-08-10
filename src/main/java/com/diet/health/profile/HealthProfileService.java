package com.diet.health.profile;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.mapper.HealthProfileMapper;
import com.diet.model.HealthProfileRow;
import com.diet.model.HealthProfileVersionRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康档案服务（34 号，规格 8.1）：
 * 必填年龄/身高/体重/活动水平/主要目标，生理性别与时区选填；
 * 能量区间由 EnergyCalculator 确定性计算并标记估算；每次保存递增版本并落快照，
 * 已激活计划保留生成时档案版本，不被静默重算。
 */
@Service
public class HealthProfileService {

    /** 档案边界（越界直接参数错误，不进入计划流程）。 */
    public static final int MIN_AGE = 18;
    public static final int MAX_AGE = 100;
    public static final double MIN_HEIGHT_CM = 100;
    public static final double MAX_HEIGHT_CM = 250;
    public static final double MIN_WEIGHT_KG = 30;
    public static final double MAX_WEIGHT_KG = 300;
    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final HealthProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    public HealthProfileService(HealthProfileMapper profileMapper, ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
    }

    /** 档案写入入参（timezone 缺省 Asia/Shanghai）。 */
    public record HealthProfileInput(Integer age, ProfileSex sex, Double heightCm, Double weightKg,
                                     ActivityLevel activityLevel, ProfileGoal goal, String timezone) {
    }

    /** 档案视图：能量区间、估算标记与计算依据固定文案。 */
    public record HealthProfileView(Long userId, Integer age, ProfileSex sex, Double heightCm, Double weightKg,
                                    ActivityLevel activityLevel, ProfileGoal goal, String timezone,
                                    Integer calorieLow, Integer calorieHigh, boolean estimated,
                                    Long versionNo, String calcBasis) {
    }

    /** 查询当前档案，不存在抛 NOT_FOUND。 */
    public HealthProfileView getProfile(Long userId) {
        HealthProfileRow row = profileMapper.findByUserId(userId);
        if (row == null) {
            throw new HealthApiException(HealthApiException.CODE_NOT_FOUND, "健康档案不存在，请先完善健康档案");
        }
        return toView(row);
    }

    /** 档案快照字段（计划版本/档案版本共用，保持生成依据一致）。 */
    public static String profileSnapshot(HealthProfileView profile, ObjectMapper objectMapper) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("age", profile.age());
        snapshot.put("sex", profile.sex() == null ? null : profile.sex().name());
        snapshot.put("heightCm", profile.heightCm());
        snapshot.put("weightKg", profile.weightKg());
        snapshot.put("activityLevel", profile.activityLevel().name());
        snapshot.put("goal", profile.goal().name());
        snapshot.put("timezone", profile.timezone());
        snapshot.put("calorieLow", profile.calorieLow());
        snapshot.put("calorieHigh", profile.calorieHigh());
        snapshot.put("estimated", profile.estimated());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "档案快照序列化失败");
        }
    }

    /** 保存档案：校验 → 计算区间 → 递增版本 + 快照。 */
    public HealthProfileView saveProfile(Long userId, HealthProfileInput input) {
        validate(input);
        String timezone = input.timezone() == null || input.timezone().isBlank()
                ? DEFAULT_TIMEZONE : input.timezone();
        EnergyCalculator.EnergyRange range = EnergyCalculator.dailyRange(
                input.age(), input.sex(), input.heightCm(), input.weightKg(),
                input.activityLevel(), input.goal());
        String calcBasis = buildCalcBasis(input);

        HealthProfileRow row = profileMapper.findByUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        long versionNo = row == null ? 1 : row.getVersionNo() + 1;
        if (row == null) {
            row = new HealthProfileRow();
            row.setUserId(userId);
            row.setCreatedAt(now);
        }
        apply(row, input, timezone, range, versionNo, now);
        if (row.getId() == null) {
            profileMapper.insert(row);
        } else {
            profileMapper.update(row);
        }
        profileMapper.insertVersion(versionRow(row, range, calcBasis, now));
        return toView(row);
    }

    private void apply(HealthProfileRow row, HealthProfileInput input, String timezone,
                       EnergyCalculator.EnergyRange range, long versionNo, LocalDateTime now) {
        row.setAge(input.age());
        row.setSex(input.sex() == null ? null : input.sex().name());
        row.setHeightCm(BigDecimal.valueOf(input.heightCm()));
        row.setWeightKg(BigDecimal.valueOf(input.weightKg()));
        row.setActivityLevel(input.activityLevel().name());
        row.setGoal(input.goal().name());
        row.setTimezone(timezone);
        row.setCalorieLow(range.lowKcal());
        row.setCalorieHigh(range.highKcal());
        row.setEstimated(true);
        row.setVersionNo(versionNo);
        row.setUpdatedAt(now);
    }

    private HealthProfileVersionRow versionRow(HealthProfileRow row, EnergyCalculator.EnergyRange range,
                                               String calcBasis, LocalDateTime now) {
        HealthProfileVersionRow version = new HealthProfileVersionRow();
        version.setUserId(row.getUserId());
        version.setProfileId(row.getId());
        version.setVersionNo(row.getVersionNo());
        version.setCalorieLow(range.lowKcal());
        version.setCalorieHigh(range.highKcal());
        version.setCreatedAt(now);
        version.setSnapshotJson(toJson(buildSnapshot(row, calcBasis)));
        return version;
    }

    private Map<String, Object> buildSnapshot(HealthProfileRow row, String calcBasis) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("age", row.getAge());
        snapshot.put("sex", row.getSex());
        snapshot.put("heightCm", row.getHeightCm());
        snapshot.put("weightKg", row.getWeightKg());
        snapshot.put("activityLevel", row.getActivityLevel());
        snapshot.put("goal", row.getGoal());
        snapshot.put("timezone", row.getTimezone());
        snapshot.put("calorieLow", row.getCalorieLow());
        snapshot.put("calorieHigh", row.getCalorieHigh());
        snapshot.put("estimated", row.getEstimated());
        snapshot.put("calcBasis", calcBasis);
        return snapshot;
    }

    private void validate(HealthProfileInput input) {
        if (input == null) {
            throw badRequest("健康档案不能为空");
        }
        if (input.age() == null) {
            throw badRequest("年龄不能为空");
        }
        if (input.age() < MIN_AGE) {
            throw badRequest("健康档案仅面向 " + MIN_AGE + " 岁及以上成年人，未满 18 岁不适合使用具体计划服务");
        }
        if (input.age() > MAX_AGE) {
            throw badRequest("年龄不能大于 " + MAX_AGE);
        }
        if (input.heightCm() == null || input.heightCm() < MIN_HEIGHT_CM || input.heightCm() > MAX_HEIGHT_CM) {
            throw badRequest("身高需在 " + (int) MIN_HEIGHT_CM + "-" + (int) MAX_HEIGHT_CM + " 厘米之间");
        }
        if (input.weightKg() == null || input.weightKg() < MIN_WEIGHT_KG || input.weightKg() > MAX_WEIGHT_KG) {
            throw badRequest("体重需在 " + (int) MIN_WEIGHT_KG + "-" + (int) MAX_WEIGHT_KG + " 千克之间");
        }
        if (input.activityLevel() == null) {
            throw badRequest("活动水平不能为空");
        }
        if (input.goal() == null) {
            throw badRequest("主要目标不能为空");
        }
    }

    private HealthApiException badRequest(String message) {
        return new HealthApiException(HealthApiException.CODE_BAD_REQUEST, message);
    }

    /** 计算依据固定文案：公式、活动系数、目标调整、舍入与估算标记。 */
    private String buildCalcBasis(HealthProfileInput input) {
        String goalDesc = switch (input.goal()) {
            case MAINTAIN -> "维持目标调整 ±5%";
            case LOSE -> "减脂目标调整 -5%~-15%";
            case GAIN -> "增重目标调整 +5%~+10%";
        };
        StringBuilder basis = new StringBuilder("Mifflin-St Jeor 估算 × 活动系数 ")
                .append(EnergyCalculator.activityFactor(input.activityLevel()))
                .append("，").append(goalDesc).append("，四舍五入到 50 kcal；以上为估算值，不是医疗处方。");
        if (input.sex() == null) {
            basis.insert(0, "未填写性别，按男/女公式取较宽区间；");
        }
        return basis.toString();
    }

    private HealthProfileView toView(HealthProfileRow row) {
        return new HealthProfileView(
                row.getUserId(),
                row.getAge(),
                row.getSex() == null ? null : ProfileSex.valueOf(row.getSex()),
                row.getHeightCm() == null ? null : row.getHeightCm().doubleValue(),
                row.getWeightKg() == null ? null : row.getWeightKg().doubleValue(),
                ActivityLevel.valueOf(row.getActivityLevel()),
                ProfileGoal.valueOf(row.getGoal()),
                row.getTimezone(),
                row.getCalorieLow(),
                row.getCalorieHigh(),
                Boolean.TRUE.equals(row.getEstimated()),
                row.getVersionNo(),
                buildCalcBasis(new HealthProfileInput(row.getAge(),
                        row.getSex() == null ? null : ProfileSex.valueOf(row.getSex()),
                        row.getHeightCm() == null ? null : row.getHeightCm().doubleValue(),
                        row.getWeightKg() == null ? null : row.getWeightKg().doubleValue(),
                        ActivityLevel.valueOf(row.getActivityLevel()),
                        ProfileGoal.valueOf(row.getGoal()),
                        row.getTimezone()))
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new HealthApiException(HealthApiException.CODE_SERVICE_ERROR, "档案快照序列化失败");
        }
    }
}
