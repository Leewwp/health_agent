package com.diet.health.browse;

import com.diet.exception.HealthApiException;
import com.diet.health.resource.HealthResourceProvider;

/** 审核浏览能力的统一资源模式 Guard。 */
final class ReviewedBrowseMode {

    private ReviewedBrowseMode() {
    }

    static void require(HealthResourceProvider provider, String capability) {
        if (!provider.providerMode().isReviewed()) {
            throw new HealthApiException(HealthApiException.CODE_RESOURCE_MODE_UNAVAILABLE,
                    "当前资源模式不提供正式审核库" + capability);
        }
    }
}
