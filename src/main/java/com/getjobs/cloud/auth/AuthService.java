package com.getjobs.cloud.auth;

import com.getjobs.cloud.quota.QuotaService;
import com.getjobs.cloud.web.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int LOCK_THRESHOLD = 5;

    private final UserRepository users;
    private final AuthFlowRepository flows;
    private final OneTimeTokenSupport tokens;
    private final AccountEmailSender emailSender;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthRateLimiter rateLimiter;
    private final AuditLogService auditLogs;
    private final AuthProperties properties;
    private final SessionRevocationService sessionRevocation;
    private final UserTransactionExecutor userTransactions;
    private final QuotaService quotaService;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(
            UserRepository users,
            AuthFlowRepository flows,
            OneTimeTokenSupport tokens,
            AccountEmailSender emailSender,
            PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy,
            AuthRateLimiter rateLimiter,
            AuditLogService auditLogs,
            AuthProperties properties,
            SessionRevocationService sessionRevocation,
            UserTransactionExecutor userTransactions,
            QuotaService quotaService,
            Clock clock
    ) {
        this.users = users;
        this.flows = flows;
        this.tokens = tokens;
        this.emailSender = emailSender;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.rateLimiter = rateLimiter;
        this.auditLogs = auditLogs;
        this.properties = properties;
        this.sessionRevocation = sessionRevocation;
        this.userTransactions = userTransactions;
        this.quotaService = quotaService;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-used-only-for-timing");
    }

    @Transactional
    public RegistrationOutcome register(
            String rawEmail,
            String password,
            String inviteCode,
            boolean acceptTerms,
            boolean acceptPrivacy,
            boolean acceptAiDisclosure,
            RequestMetadata request
    ) {
        String email = EmailAddressSupport.normalize(rawEmail);
        rateLimiter.checkRegister(request.remoteAddress(), email);
        if (!acceptTerms) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TERMS_NOT_ACCEPTED", "请先同意服务条款");
        }
        if (properties.isLegalDocumentsFinalized() && (!acceptPrivacy || !acceptAiDisclosure)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CONSENT_NOT_ACCEPTED",
                    "请分别同意隐私政策和第三方 AI 数据处理说明"
            );
        }
        passwordPolicy.validate(password, email);
        if (!properties.isInviteRequired() && users.findByEmail(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已经注册");
        }

        try {
            UUID userId = UUID.randomUUID();
            if (properties.isInviteRequired()) {
                if (inviteCode == null || inviteCode.isBlank()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVITE_REQUIRED", "请输入有效邀请码");
                }
                String inviteOutcome = flows.consumeInvite(
                        tokens.hash(inviteCode.trim()), userId, email, properties.getBetaMaxUsers()
                );
                if ("LIMIT_REACHED".equals(inviteOutcome)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "BETA_LIMIT_REACHED", "测试名额已满");
                }
                if (!"OK".equals(inviteOutcome)) {
                    throw registrationUnavailable();
                }
            }
            UserStatus initialStatus = properties.isEmailVerificationRequired()
                    ? UserStatus.PENDING
                    : UserStatus.ACTIVE;
            users.insertUser(userId, email, passwordEncoder.encode(password), initialStatus);
            // 同一注册事务与同一用户 tenant context：默认资料与 FREE 额度初始化
            // 任一失败都会整体回滚，绝不出现“有账号无额度”的半完成状态。
            userTransactions.execute(userId, () -> {
                users.insertDefaultProfile(userId);
                quotaService.initializeFree(userId);
            });
            flows.recordConsent(userId, "TERMS", properties.getTermsVersion());
            if (acceptPrivacy) {
                flows.recordConsent(userId, "PRIVACY", properties.getPrivacyVersion());
            }
            if (acceptAiDisclosure) {
                flows.recordConsent(userId, "AI_DISCLOSURE", properties.getAiDisclosureVersion());
            }

            PendingEmail pendingEmail = null;
            if (properties.isEmailVerificationRequired()) {
                pendingEmail = createEmailToken(userId, email, "VERIFY_EMAIL", properties.getEmailVerificationTtl());
            }
            UserAccount account = users.findById(userId).orElseThrow();
            auditLogs.append(
                    userId,
                    UserRole.USER,
                    "AUTH_REGISTER",
                    "SUCCESS",
                    request,
                    Map.of(
                            "termsVersion", properties.getTermsVersion(),
                            "privacyVersion", acceptPrivacy ? properties.getPrivacyVersion() : "NOT_ACCEPTED",
                            "aiDisclosureVersion", acceptAiDisclosure ? properties.getAiDisclosureVersion() : "NOT_ACCEPTED"
                    )
            );
            return new RegistrationOutcome(result(account), properties.isEmailVerificationRequired(), pendingEmail);
        } catch (DuplicateKeyException exception) {
            if (properties.isInviteRequired()) {
                throw registrationUnavailable();
            }
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已经注册");
        }
    }

    @Transactional
    public Optional<PendingEmail> requestEmailVerification(String rawEmail, RequestMetadata request) {
        String email = EmailAddressSupport.normalize(rawEmail);
        rateLimiter.checkEmailAction(request.remoteAddress(), email);
        Optional<UserAccount> account = users.findByEmail(email);
        if (account.isEmpty() || account.get().status() != UserStatus.PENDING) {
            return Optional.empty();
        }
        PendingEmail action = createEmailToken(
                account.get().id(), email, "VERIFY_EMAIL", properties.getEmailVerificationTtl()
        );
        auditLogs.append(account.get().id(), account.get().role(), "AUTH_EMAIL_VERIFICATION_SENT", "SUCCESS", request, Map.of());
        return Optional.of(action);
    }

    @Transactional
    public void verifyEmail(String rawToken, RequestMetadata request) {
        rateLimiter.checkEmailToken(request.remoteAddress());
        AuthFlowRepository.EmailToken token = flows.consumeEmailToken(tokens.hash(rawToken), "VERIFY_EMAIL")
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOKEN_INVALID", "验证链接无效或已过期"));
        users.markEmailVerified(token.userId(), clock.instant());
        UserAccount account = users.findById(token.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOKEN_INVALID", "验证链接无效或已过期"));
        auditLogs.append(account.id(), account.role(), "AUTH_EMAIL_VERIFIED", "SUCCESS", request, Map.of());
    }

    @Transactional
    public Optional<PendingEmail> requestPasswordReset(String rawEmail, RequestMetadata request) {
        String email = EmailAddressSupport.normalize(rawEmail);
        rateLimiter.checkEmailAction(request.remoteAddress(), email);
        Optional<UserAccount> account = users.findByEmail(email)
                .filter(candidate -> candidate.status() == UserStatus.ACTIVE || candidate.status() == UserStatus.LOCKED);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        PendingEmail action = createEmailToken(
                account.get().id(), email, "RESET_PASSWORD", properties.getPasswordResetTtl()
        );
        auditLogs.append(account.get().id(), account.get().role(), "AUTH_PASSWORD_RESET_REQUESTED", "SUCCESS", request, Map.of());
        return Optional.of(action);
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, RequestMetadata request) {
        rateLimiter.checkEmailToken(request.remoteAddress());
        AuthFlowRepository.EmailToken token = flows.consumeEmailToken(tokens.hash(rawToken), "RESET_PASSWORD")
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOKEN_INVALID", "重置链接无效或已过期"));
        passwordPolicy.validate(newPassword, token.email());
        UserAccount account = users.findById(token.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TOKEN_INVALID", "重置链接无效或已过期"));
        users.updatePassword(account.id(), passwordEncoder.encode(newPassword));
        sessionRevocation.revokeAll(account.id());
        auditLogs.append(account.id(), account.role(), "AUTH_PASSWORD_RESET_COMPLETED", "SUCCESS", request, Map.of());
    }

    public void sendVerification(PendingEmail email) {
        if (email != null) {
            try {
                emailSender.sendVerification(email.recipient(), email.rawToken());
            } catch (EmailDeliveryException exception) {
                log.warn("邮箱验证邮件发送失败，异常类型={}", exception.getClass().getSimpleName());
            }
        }
    }

    public void sendPasswordReset(PendingEmail email) {
        if (email != null) {
            try {
                emailSender.sendPasswordReset(email.recipient(), email.rawToken());
            } catch (EmailDeliveryException exception) {
                log.warn("密码重置邮件发送失败，异常类型={}", exception.getClass().getSimpleName());
            }
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
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "请先完成邮箱验证");
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

    private ApiException registrationUnavailable() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "REGISTRATION_UNAVAILABLE",
                "无法完成注册，请确认邀请信息或返回登录"
        );
    }

    public record CurrentUserView(UserAccount account, UserProfile profile) {
    }

    public record PendingEmail(String recipient, String rawToken) {
    }

    public record RegistrationOutcome(AuthResult authResult, boolean verificationRequired, PendingEmail pendingEmail) {
    }

    private PendingEmail createEmailToken(UUID userId, String email, String purpose, Duration ttl) {
        String rawToken = tokens.generate();
        flows.createEmailToken(userId, purpose, tokens.hash(rawToken), email, clock.instant().plus(ttl));
        return new PendingEmail(email, rawToken);
    }
}
