package com.diet.health.profile;

import com.diet.exception.HealthApiException;
import com.diet.health.enums.ActivityLevel;
import com.diet.health.enums.ProfileGoal;
import com.diet.health.enums.ProfileSex;
import com.diet.mapper.HealthProfileMapper;
import com.diet.model.HealthProfileRow;
import com.diet.model.HealthProfileVersionRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void 连续保存版本号连续递增() {
        service.saveProfile(1L, maleInput());
        service.saveProfile(1L, input(31, ProfileSex.MALE, 176.0, 72.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN));
        HealthProfileService.HealthProfileView third = service.saveProfile(1L,
                input(32, ProfileSex.MALE, 177.0, 73.0, ActivityLevel.LIGHT, ProfileGoal.MAINTAIN));
        assertEquals(3L, third.versionNo());
        assertEquals(3, mapper.versions.size());
        assertEquals(1L, mapper.versions.get(0).getVersionNo());
        assertEquals(2L, mapper.versions.get(1).getVersionNo());
        assertEquals(3L, mapper.versions.get(2).getVersionNo());
    }

    @Test
    void 保存档案使用行锁查询() {
        service.saveProfile(1L, maleInput());
        assertEquals(1, mapper.findByUserIdForUpdateCalls, "保存必须用 FOR UPDATE 查询");
        assertEquals(0, mapper.findByUserIdCalls, "保存路径不得使用非锁定查询");
    }

    @Test
    void 首次保存时版本快照写入失败insert先于insertVersion且保存抛异常() {
        HealthProfileMapper mapperMock = mock(HealthProfileMapper.class);
        HealthProfileService svc = new HealthProfileService(mapperMock, new ObjectMapper());
        when(mapperMock.findByUserIdForUpdate(1L)).thenReturn(null);
        when(mapperMock.insert(any(HealthProfileRow.class))).thenReturn(1);
        when(mapperMock.insertVersion(any(HealthProfileVersionRow.class)))
                .thenThrow(new IllegalStateException("快照写入失败"));
        assertThrows(IllegalStateException.class, () -> svc.saveProfile(1L, maleInput()));
        var order = inOrder(mapperMock);
        order.verify(mapperMock).findByUserIdForUpdate(1L);
        order.verify(mapperMock).insert(any(HealthProfileRow.class));
        order.verify(mapperMock).insertVersion(any(HealthProfileVersionRow.class));
        verify(mapperMock, never()).update(any());
    }

    @Test
    void 更新档案时版本快照写入失败update先于insertVersion且保存抛异常() {
        HealthProfileMapper mapperMock = mock(HealthProfileMapper.class);
        HealthProfileService svc = new HealthProfileService(mapperMock, new ObjectMapper());
        HealthProfileRow existing = new HealthProfileRow();
        existing.setId(1L);
        existing.setUserId(1L);
        existing.setVersionNo(3L);
        when(mapperMock.findByUserIdForUpdate(1L)).thenReturn(existing);
        when(mapperMock.update(any(HealthProfileRow.class))).thenReturn(1);
        when(mapperMock.insertVersion(any(HealthProfileVersionRow.class)))
                .thenThrow(new IllegalStateException("快照写入失败"));
        assertThrows(IllegalStateException.class, () -> svc.saveProfile(1L, maleInput()));
        var order = inOrder(mapperMock);
        order.verify(mapperMock).findByUserIdForUpdate(1L);
        order.verify(mapperMock).update(any(HealthProfileRow.class));
        order.verify(mapperMock).insertVersion(any(HealthProfileVersionRow.class));
        verify(mapperMock, never()).insert(any());
    }

    @Test
    void 首份档案并发保存只有一个成功() throws Exception {
        mapper.simulateFirstSaveRace = true;
        int threads = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    service.saveProfile(1L, maleInput());
                    results.add("OK");
                } catch (HealthApiException e) {
                    results.add(e.code());
                }
                done.countDown();
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发保存应在限时内完成");
        assertEquals(threads, results.size());
        assertEquals(1, results.stream().filter("OK"::equals).count(), "首份档案只能有一个版本 1");
        assertEquals(1, results.stream().filter(HealthApiException.CODE_CONFLICT::equals).count(),
                "输掉竞争的请求应得到冲突错误");
        assertEquals(1, mapper.profiles.size(), "当前档案只落一行");
        assertEquals(1, mapper.versions.size());
        assertEquals(1L, mapper.versions.get(0).getVersionNo());
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

    /** 内存版 HealthProfileMapper：支持档案与快照读写；可模拟首份档案并发唯一键竞争。 */
    private static final class FakeProfileMapper implements HealthProfileMapper {
        final List<HealthProfileRow> profiles = new ArrayList<>();
        final List<HealthProfileVersionRow> versions = new ArrayList<>();
        int findByUserIdCalls;
        int findByUserIdForUpdateCalls;
        /** 测试开关：开启后两个线程都先读到 null，insert 等待双方扫描完成（模拟并发双写窗口）。 */
        volatile boolean simulateFirstSaveRace = false;
        private final CountDownLatch firstScanWindow = new CountDownLatch(2);

        @Override
        public synchronized HealthProfileRow findByUserId(Long userId) {
            findByUserIdCalls++;
            return profiles.stream().filter(row -> row.getUserId().equals(userId)).findFirst().orElse(null);
        }

        @Override
        public synchronized HealthProfileRow findByUserIdForUpdate(Long userId) {
            findByUserIdForUpdateCalls++;
            HealthProfileRow row = profiles.stream().filter(r -> r.getUserId().equals(userId))
                    .findFirst().orElse(null);
            if (simulateFirstSaveRace && row == null) {
                firstScanWindow.countDown();
            }
            return row;
        }

        @Override
        public int insert(HealthProfileRow row) {
            if (simulateFirstSaveRace) {
                // 等待两个线程都完成"无档案"扫描再放行插入；await 不能持有监视器，
                // 否则另一线程的 findByUserIdForUpdate 无法进入，形成死锁
                try {
                    if (!firstScanWindow.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("两个线程应都先完成档案扫描");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("首份档案并发窗口等待被中断", e);
                }
            }
            synchronized (this) {
                if (simulateFirstSaveRace
                        && profiles.stream().anyMatch(existing -> existing.getUserId().equals(row.getUserId()))) {
                    throw new DataIntegrityViolationException("唯一索引 uk_health_profile_user 冲突");
                }
                row.setId((long) profiles.size() + 1);
                profiles.add(row);
                return 1;
            }
        }

        @Override
        public synchronized int update(HealthProfileRow row) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(row.getId())) {
                    profiles.set(i, row);
                    return 1;
                }
            }
            return 0;
        }

        @Override
        public synchronized int insertVersion(HealthProfileVersionRow row) {
            row.setId((long) versions.size() + 1);
            versions.add(row);
            return 1;
        }
    }
}
