package com.diet.health.profile;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.mapper.HealthProfileMapper;
import com.diet.model.HealthProfileRow;
import com.diet.model.HealthProfileVersionRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康档案（34 号）：必填校验、能量区间计算、版本递增与快照、估算标记与计算依据。
 */
class HealthProfileServiceTest {

    private final FakeProfileMapper mapper = new FakeProfileMapper();
    private final HealthProfileService service = new HealthProfileService(mapper, new com.fasterxml.jackson.databind.ObjectMapper());

    private static HealthProfileService.HealthProfileInput input(Integer age, ProfileSex sex, Double height,
                                                                 Double weight, ActivityLevel activity, ProfileGoal goal) {
        return new HealthProfileService.HealthProfileInput(age, sex, height, weight, activity, goal, null);
    }

    private static HealthProfileService.HealthProfileInput maleInput() {
        return input(30, ProfileSex.MALE, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN);
    }

    @Test
    void 首次保存档案计算能量区间并生成版本1快照() {
        HealthProfileService.HealthProfileView view = service.saveProfile(1L, maleInput());
        assertEquals(30, view.age());
        assertEquals(2150, view.calorieLow());
        assertEquals(2400, view.calorieHigh());
        assertEquals(1L, view.versionNo());
        assertTrue(view.estimated());
        assertEquals("Asia/Shanghai", view.timezone(), "缺省时区");
        assertEquals(1, mapper.profiles.size());
        assertEquals(1, mapper.versions.size());
        assertEquals(1L, mapper.versions.get(0).getVersionNo());
    }

    @Test
    void 更新档案递增版本并保存快照() {
        service.saveProfile(1L, maleInput());
        HealthProfileService.HealthProfileView updated = service.saveProfile(1L,
                input(31, ProfileSex.MALE, 176.0, 72.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN));
        assertEquals(2L, updated.versionNo());
        assertEquals(2, mapper.versions.size());
        assertEquals(2L, mapper.versions.get(1).getVersionNo());
        assertEquals(1L, mapper.profiles.size(), "当前档案保持一行");
        assertTrue(updated.calorieLow() > 2150, "体重身高变化应重算区间");
    }

    @Test
    void 生理性别缺失取男女并集且计算依据注明() {
        HealthProfileService.HealthProfileView view = service.saveProfile(1L,
                input(30, null, 175.0, 70.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN));
        assertEquals(1950, view.calorieLow());
        assertEquals(2400, view.calorieHigh());
        assertTrue(view.calcBasis().contains("Mifflin-St Jeor"));
        assertTrue(view.calcBasis().contains("男/女公式"));
        assertTrue(view.calcBasis().contains("估算"));
    }

    @Test
    void 必填字段缺失为参数错误() {
        HealthApiException error = assertThrows(HealthApiException.class,
                () -> service.saveProfile(1L, input(null, ProfileSex.MALE, 175.0, 70.0,
                        ActivityLevel.LIGHT, ProfileGoal.MAINTAIN)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("年龄"));
        assertEquals(0, mapper.profiles.size());
    }

    @Test
    void 未满18岁档案被拒绝() {
        HealthApiException error = assertThrows(HealthApiException.class,
                () -> service.saveProfile(1L, input(17, ProfileSex.MALE, 175.0, 70.0,
                        ActivityLevel.LIGHT, ProfileGoal.MAINTAIN)));
        assertEquals(HealthApiException.CODE_BAD_REQUEST, error.code());
        assertTrue(error.getMessage().contains("18"));
    }

    @Test
    void 身高体重越界被拒绝() {
        assertThrows(HealthApiException.class,
                () -> service.saveProfile(1L, input(30, ProfileSex.MALE, 99.0, 70.0,
                        ActivityLevel.LIGHT, ProfileGoal.MAINTAIN)));
        assertThrows(HealthApiException.class,
                () -> service.saveProfile(1L, input(30, ProfileSex.MALE, 175.0, 500.0,
                        ActivityLevel.LIGHT, ProfileGoal.MAINTAIN)));
    }

    @Test
    void 查询不存在档案为NOT_FOUND() {
        HealthApiException error = assertThrows(HealthApiException.class, () -> service.getProfile(99L));
        assertEquals(HealthApiException.CODE_NOT_FOUND, error.code());
    }

    @Test
    void 查询已保存档案返回估算标记() {
        service.saveProfile(1L, maleInput());
        HealthProfileService.HealthProfileView view = service.getProfile(1L);
        assertNotNull(view);
        assertEquals(2150, view.calorieLow());
        assertTrue(view.estimated());
    }

    /** 内存版 HealthProfileMapper：支持档案与快照读写。 */
    private static final class FakeProfileMapper implements HealthProfileMapper {
        final List<HealthProfileRow> profiles = new ArrayList<>();
        final List<HealthProfileVersionRow> versions = new ArrayList<>();

        @Override
        public HealthProfileRow findByUserId(Long userId) {
            return profiles.stream().filter(row -> row.getUserId().equals(userId)).findFirst().orElse(null);
        }

        @Override
        public int insert(HealthProfileRow row) {
            row.setId((long) profiles.size() + 1);
            profiles.add(row);
            return 1;
        }

        @Override
        public int update(HealthProfileRow row) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(row.getId())) {
                    profiles.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public int insertVersion(HealthProfileVersionRow row) {
            row.setId((long) versions.size() + 1);
            versions.add(row);
            return 1;
        }
    }
}
