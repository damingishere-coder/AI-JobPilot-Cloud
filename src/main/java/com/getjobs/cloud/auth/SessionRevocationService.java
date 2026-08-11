package com.getjobs.cloud.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

@Service
@Profile("api")
public class SessionRevocationService {
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositories;

    public SessionRevocationService(
            ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repositories
    ) {
        this.repositories = repositories;
    }

    public void revokeAll(UUID userId) {
        FindByIndexNameSessionRepository<? extends Session> repository = repositories.getIfAvailable();
        if (repository == null) {
            return;
        }
        repository.findByPrincipalName(userId.toString())
                .keySet()
                .forEach(repository::deleteById);
    }
}
