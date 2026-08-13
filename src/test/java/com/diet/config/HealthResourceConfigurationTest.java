package com.diet.config;

import com.diet.health.resource.DbReviewedResourceProvider;
import com.diet.health.resource.HealthResourceProvider;
import com.diet.health.resource.SeedResourceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 资源模式配置契约：fixture 选择种子 Provider，生产环境禁止 fixture。 */
class HealthResourceConfigurationTest {

    private final HealthResourceConfiguration configuration = new HealthResourceConfiguration();
    private final DbReviewedResourceProvider reviewedProvider = mock(DbReviewedResourceProvider.class);
    private final SeedResourceProvider seedProvider = new SeedResourceProvider();

    @Test
    void fixture模式选择种子Provider() {
        HealthResourceProvider selected = configuration.healthResourceProvider(
                reviewedProvider, seedProvider, new MockEnvironment(), "fixture");

        assertSame(seedProvider, selected);
    }

    @Test
    void reviewed模式选择正式库Provider() {
        HealthResourceProvider selected = configuration.healthResourceProvider(
                reviewedProvider, seedProvider, new MockEnvironment(), "reviewed");

        assertSame(reviewedProvider, selected);
    }

    @Test
    void 生产环境拒绝fixture模式() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> configuration.healthResourceProvider(
                reviewedProvider, seedProvider, environment, "fixture"));
    }

    @Test
    void 未知模式拒绝启动而不静默回退正式库() {
        assertThrows(IllegalStateException.class, () -> configuration.healthResourceProvider(
                reviewedProvider, seedProvider, new MockEnvironment(), "fixtures"));
    }
}
