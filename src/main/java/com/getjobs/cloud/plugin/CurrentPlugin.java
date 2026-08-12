package com.getjobs.cloud.plugin;

import com.getjobs.cloud.web.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Accessor for the authenticated plugin principal; plugin endpoints only.
 */
@Component
@Profile("api")
public class CurrentPlugin {
    public PluginPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PluginPrincipal principal) {
            return principal;
        }
        throw new ApiException(
                HttpStatus.UNAUTHORIZED, "PLUGIN_TOKEN_INVALID", "缺少有效的插件凭证"
        );
    }
}
