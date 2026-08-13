package com.getjobs.cloud.process;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

@Component
public class CloudRoleInfoContributor implements InfoContributor {
    private final String role;

    public CloudRoleInfoContributor(@Value("${app.role:unknown}") String role) {
        this.role = role;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("role", role);
    }
}
