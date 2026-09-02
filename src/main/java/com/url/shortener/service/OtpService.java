package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.exception.BadRequestException;
import com.url.shortener.models.OtpPurpose;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class OtpService {

    private static final String OTP_PREFIX = "auth:otp:";
    private static final String ATTEMPTS_PREFIX = "auth:otp-attempts:";
    private static final String COOLDOWN_PREFIX = "auth:otp-cooldown:";
    private static final String RESET_AUTHORIZATION_PREFIX = "auth:password-reset:";
    private static final String PENDING_REGISTRATION_PREFIX = "auth:pending-registration:";

    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(
        StringRedisTemplate redisTemplate,
        PasswordEncoder passwordEncoder,
        EmailService emailService,
        AppProperties appProperties,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
    }

    public void issueOtp(String email, String username, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        String cooldownKey = cooldownKey(normalizedEmail, purpose);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            throw new BadRequestException("Please wait before requesting another verification code");
        }

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        String otpKey = otpKey(normalizedEmail, purpose);
        redisTemplate.opsForValue().set(otpKey, passwordEncoder.encode(otp), appProperties.getOtp().getExpiration());
        redisTemplate.delete(attemptsKey(normalizedEmail, purpose));
        redisTemplate.opsForValue().set(cooldownKey, "1", appProperties.getOtp().getResendCooldown());
        try {
            emailService.sendOtp(normalizedEmail, username, otp, appProperties.getOtp().getExpiration().toMinutes(), purpose);
        } catch (RuntimeException exception) {
            // A code that was not sent must never remain usable or block the next request.
            redisTemplate.delete(java.util.List.of(otpKey, attemptsKey(normalizedEmail, purpose), cooldownKey));
            throw exception;
        }
    }

    /**
     * Keeps a new registration out of the users table until the emailed code is
     * verified. The password is already hashed; plaintext registration data is
     * never put in Redis.
     */
    public void startPendingRegistration(String email, String username, String passwordHash) {
        String normalizedEmail = email.trim().toLowerCase();
        String key = pendingRegistrationKey(normalizedEmail);
        try {
            String payload = objectMapper.writeValueAsString(new PendingRegistration(username, passwordHash));
            redisTemplate.opsForValue().set(key, payload, appProperties.getOtp().getExpiration());
            issueOtp(normalizedEmail, username, OtpPurpose.ACCOUNT_VERIFICATION);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to prepare registration verification", exception);
        } catch (RuntimeException exception) {
            // If delivery fails, no pending credential should remain.
            redisTemplate.delete(key);
            throw exception;
        }
    }

    public PendingRegistration getPendingRegistration(String email) {
        String payload = redisTemplate.opsForValue().get(pendingRegistrationKey(email.trim().toLowerCase()));
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, PendingRegistration.class);
        } catch (JsonProcessingException exception) {
            redisTemplate.delete(pendingRegistrationKey(email.trim().toLowerCase()));
            throw new BadRequestException("Registration verification has expired. Please register again.");
        }
    }

    public void clearPendingRegistration(String email) {
        redisTemplate.delete(pendingRegistrationKey(email.trim().toLowerCase()));
    }

    public String verifyOtp(String email, String otp, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        String otpKey = otpKey(normalizedEmail, purpose);
        String storedHash = redisTemplate.opsForValue().get(otpKey);
        if (storedHash == null) {
            throw new BadRequestException("Verification code is invalid or expired");
        }
        if (!passwordEncoder.matches(otp, storedHash)) {
            String attemptsKey = attemptsKey(normalizedEmail, purpose);
            Long attemptCount = redisTemplate.opsForValue().increment(attemptsKey);
            if (attemptCount != null && attemptCount == 1) {
                redisTemplate.expire(attemptsKey, appProperties.getOtp().getExpiration());
            }
            if (attemptCount != null && attemptCount >= appProperties.getOtp().getMaxAttempts()) {
                redisTemplate.delete(java.util.List.of(otpKey, attemptsKey));
                throw new BadRequestException("Too many invalid attempts. Request a new code.");
            }
            throw new BadRequestException("Verification code is invalid or expired");
        }

        redisTemplate.delete(java.util.List.of(otpKey, attemptsKey(normalizedEmail, purpose)));
        if (purpose == OtpPurpose.PASSWORD_RESET) {
            String resetToken = randomToken();
            redisTemplate.opsForValue().set(
                resetAuthorizationKey(normalizedEmail),
                passwordEncoder.encode(resetToken),
                appProperties.getOtp().getResetAuthorizationExpiration()
            );
            return resetToken;
        }
        return null;
    }

    public void consumeResetAuthorization(String email, String resetToken) {
        String key = resetAuthorizationKey(email.trim().toLowerCase());
        String storedHash = redisTemplate.opsForValue().get(key);
        if (storedHash == null || !passwordEncoder.matches(resetToken, storedHash)) {
            throw new BadRequestException("Password reset authorization is invalid or expired");
        }
        redisTemplate.delete(key);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String otpKey(String email, OtpPurpose purpose) {
        return OTP_PREFIX + purpose.name().toLowerCase() + ":" + emailFingerprint(email);
    }

    private String attemptsKey(String email, OtpPurpose purpose) {
        return ATTEMPTS_PREFIX + purpose.name().toLowerCase() + ":" + emailFingerprint(email);
    }

    private String cooldownKey(String email, OtpPurpose purpose) {
        return COOLDOWN_PREFIX + purpose.name().toLowerCase() + ":" + emailFingerprint(email);
    }

    private String resetAuthorizationKey(String email) {
        return RESET_AUTHORIZATION_PREFIX + emailFingerprint(email);
    }

    private String pendingRegistrationKey(String email) {
        return PENDING_REGISTRATION_PREFIX + emailFingerprint(email);
    }

    public record PendingRegistration(String username, String passwordHash) {
    }

    private String emailFingerprint(String email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
