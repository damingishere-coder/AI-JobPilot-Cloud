package com.getjobs.cloud.auth;

import com.getjobs.cloud.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("api")
public class AuthService {
    private static final int LOCK_THRESHOLD = 5;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthRateLimiter rateLimiter;
    private final AuditLogService auditLogs;
    private final AuthProperties properties;
    private final SessionRevocationService sessionRevocation;
    private final UserTransactionExecutor userTransactions;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            AuthRateLimiter rateLimiter,
            AuditLogService auditLogs,
            AuthProperties properties,
            SessionRevocationService sessionRevocation,
            UserTransactionExecutor userTransactions,
            Clock clock
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimiter = rateLimiter;
        this.auditLogs = auditLogs;
        this.properties = properties;
        this.sessionRevocation = sessionRevocation;
        this.userTransactions = userTransactions;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-used-only-for-timing");
    }

    @Transactional
    public AuthResult register(
            String rawEmail,
            String password,
            boolean acceptTerms,
            RequestMetadata request
    ) {
        String email = EmailAddressSupport.normalize(rawEmail);
        rateLimiter.checkRegister(request.remoteAddress(), email);
        if (!acceptTerms) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERMS_NOT_ACCEPTED", "请先同意服务条款和隐私说明");
        }
        passwordPolicy.validate(password, email);
        if (users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已经注册");
        }

        try {
            UUID userId = users.insertUser(email, passwordEncoder.encode(password));
            userTransactions.execute(userId, () -> users.insertDefaultProfile(userId));
            UserAccount account = users.findById(userId).orElseThrow();
            auditLogs.append(
                    userId,
                    UserRole.USER,
                    "AUTH_REGISTER",
                    "SUCCESS",
                    request,
                    Map.of("termsVersion", properties.getTermsVersion())
            );
            return result(account);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已经注册");
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult login(String rawEmail, String password, RequestMetadata request) {
        String email = EmailAddressSupport.normalize(rawEmail);
        rateLimiter.checkLogin(request.remoteAddress(), email);
        Optional<UserAccount> maybeAccount = users.findByEmail(email);
        UserAccount account = maybeAccount.orElse(null);
        Instant now = clock.instant();

        if (account != null && account.status() == UserStatus.LOCKED) {
            if (account.lockedUntil() != null && !now.isBefore(account.lockedUntil())) {
                users.unlockExpired(account.id());
                account = new UserAccount(
                        account.id(), account.email(), account.passwordHash(), account.role(), UserStatus.ACTIVE,
                        account.failedLoginCount(), null, account.createdAt()
                );
            } else {
                auditLogs.append(null, null, "AUTH_LOGIN_LOCKED", "DENIED", request, Map.of("reason", "ACCOUNT_LOCKED"));
                long retryAfter = account.lockedUntil() == null ? 900 : Math.max(1, Duration.between(now, account.lockedUntil()).toSeconds());
                throw locked(retryAfter);
            }
        }
        if (account != null && account.status() == UserStatus.DISABLED) {
            auditLogs.append(null, null, "AUTH_LOGIN_DISABLED", "DENIED", request, Map.of("reason", "ACCOUNT_DISABLED"));
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已被禁用");
        }
        if (account != null && account.status() == UserStatus.PENDING) {
            auditLogs.append(null, null, "AUTH_LOGIN_PENDING", "DENIED", request, Map.of("reason", "ACCOUNT_PENDING"));
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号尚未启用");
        }

        String storedHash = account == null ? dummyPasswordHash : account.passwordHash();
        if (!passwordEncoder.matches(password, storedHash)) {
            if (account != null) {
                int failedCount = account.failedLoginCount() + 1;
                Instant lockedUntil = failedCount >= LOCK_THRESHOLD ? now.plus(lockDuration(failedCount)) : null;
                users.markLoginFailure(account.id(), failedCount, lockedUntil);
                auditLogs.append(
                        null,
                        null,
                        lockedUntil == null ? "AUTH_LOGIN_FAILED" : "AUTH_ACCOUNT_LOCKED",
                        "DENIED",
                        request,
                        Map.of("reason", lockedUntil == null ? "INVALID_CREDENTIALS" : "ACCOUNT_LOCKED")
                );
                if (lockedUntil != null) {
                    sessionRevocation.revokeAll(account.id());
                    throw locked(Duration.between(now, lockedUntil).toSeconds());
                }
            } else {
                auditLogs.append(null, null, "AUTH_LOGIN_FAILED", "DENIED", request, Map.of("reason", "INVALID_CREDENTIALS"));
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "账号或密码错误");
        }

        users.markLoginSuccess(account.id(), now);
        UserAccount activeAccount = new UserAccount(
                account.id(), account.email(), account.passwordHash(), account.role(), UserStatus.ACTIVE,
                0, null, account.createdAt()
        );
        auditLogs.append(account.id(), account.role(), "AUTH_LOGIN", "SUCCESS", request, Map.of());
        return result(activeAccount);
    }

    @Transactional(readOnly = true)
    public CurrentUserView currentUser(UUID userId) {
        UserAccount account = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录"));
        if (account.status() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号当前不可用");
        }
        UserProfile profile = userTransactions.execute(userId, () -> users.findCurrentProfile(userId)
                .orElse(new UserProfile(userId, null, null, "Asia/Shanghai", "zh-CN")));
        return new CurrentUserView(account, profile);
    }

    private AuthResult result(UserAccount account) {
        return new AuthResult(
                account,
                new SessionPrincipal(account.id(), EmailAddressSupport.mask(account.email()), account.role())
        );
    }

    private Duration lockDuration(int failedCount) {
        int exponent = Math.min(7, Math.max(0, failedCount - LOCK_THRESHOLD));
        return Duration.ofMinutes(Math.min(24 * 60L, 15L << exponent));
    }

    private ApiException locked(long retryAfterSeconds) {
        return new ApiException(
                HttpStatus.LOCKED,
                "ACCOUNT_LOCKED",
                "登录失败次数过多，账号已被临时锁定",
                true,
                retryAfterSeconds,
                java.util.List.of()
        );
    }

    public record CurrentUserView(UserAccount account, UserProfile profile) {
    }
}
