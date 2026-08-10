package com.diet.config;

import com.diet.constants.DietConstants;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/** 为 API 请求解析匿名身份，并把用户 ID 放入请求属性。 */
@Component
public class AnonymousIdentityInterceptor implements HandlerInterceptor {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long MAX_USER_ID = Long.MAX_VALUE;

    private final SecureRandom random = new SecureRandom();

    @Value("${diet.security.session-cookie-name:HEALTH_SESSION}")
    private String cookieName;

    @Value("${diet.security.session-secret:dev-only-change-me}")
    private String sessionSecret;

    @Value("${diet.security.cookie-secure:false}")
    private boolean secureCookie;

    @Value("${diet.security.allow-legacy-user-header:true}")
    private boolean allowLegacyUserHeader;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Identity identity = readCookie(request);
        if (identity == null && allowLegacyUserHeader) {
            identity = readHeader(request.getHeader(DietConstants.USER_ID));
        }
        if (identity == null) {
            identity = new Identity(newUserId(), true);
        }
        request.setAttribute(DietConstants.USER_ID_ATTRIBUTE, identity.userId());
        if (identity.issueCookie()) {
            writeCookie(response, identity.userId());
        }
        return true;
    }

    private Identity readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                Long userId = verify(cookie.getValue());
                return userId == null ? null : new Identity(userId, false);
            }
        }
        return null;
    }

    private Identity readHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long userId = Long.parseLong(value.trim());
            return userId > 0 ? new Identity(userId, true) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long verify(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) {
            return null;
        }
        try {
            long userId = Long.parseLong(parts[0]);
            if (userId <= 0 || userId > MAX_USER_ID) {
                return null;
            }
            byte[] expected = sign(parts[0]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected, actual) ? userId : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void writeCookie(HttpServletResponse response, long userId) {
        String value = userId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(Long.toString(userId)));
        ResponseCookie cookie = ResponseCookie.from(cookieName, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(30))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(sessionSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("匿名身份签名配置无效", error);
        }
    }

    private long newUserId() {
        long userId;
        do {
            userId = random.nextLong() & Long.MAX_VALUE;
        } while (userId == 0);
        return userId;
    }

    private record Identity(long userId, boolean issueCookie) {
    }
}
