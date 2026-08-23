package com.getjobs.cloud.auth;

import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.List;

public final class DynamicSessionCookieSerializer implements CookieSerializer {
    public static final String COOKIE_MAX_AGE_ATTRIBUTE = DynamicSessionCookieSerializer.class.getName() + ".MAX_AGE";
    private final DefaultCookieSerializer delegate;

    public DynamicSessionCookieSerializer(AuthProperties properties) {
        this.delegate = new DefaultCookieSerializer();
        delegate.setCookieName(properties.getCookieName());
        delegate.setCookiePath("/");
        delegate.setUseHttpOnlyCookie(true);
        delegate.setUseSecureCookie(properties.isSecureCookie());
        delegate.setSameSite("Lax");
    }

    @Override
    public List<String> readCookieValues(jakarta.servlet.http.HttpServletRequest request) {
        return delegate.readCookieValues(request);
    }

    @Override
    public void writeCookieValue(CookieValue cookieValue) {
        Object configuredMaxAge = cookieValue.getRequest().getAttribute(COOKIE_MAX_AGE_ATTRIBUTE);
        if (cookieValue.getCookieMaxAge() != 0 && configuredMaxAge instanceof Integer maxAge) {
            cookieValue.setCookieMaxAge(maxAge);
        }
        delegate.writeCookieValue(cookieValue);
    }
}
