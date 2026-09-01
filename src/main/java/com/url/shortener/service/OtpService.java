package com.url.shortener.service;

import com.url.shortener.config.AppProperties;
import com.url.shortener.exception.BadRequestException;
import com.url.shortener.models.OtpPurpose;
import com.url.shortener.models.OtpVerification;
import com.url.shortener.repo.OtpVerificationRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

@Service
public class OtpService {

    private final OtpVerificationRepository otpRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(
        OtpVerificationRepository otpRepository,
        PasswordEncoder passwordEncoder,
        EmailService emailService,
        AppProperties appProperties
    ) {
        this.otpRepository = otpRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.appProperties = appProperties;
    }

    @Transactional
    public void issueOtp(String email, String username, OtpPurpose purpose) {
        String normalizedEmail = email.trim().toLowerCase();
        Instant now = Instant.now();
        otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(normalizedEmail, purpose)
            .filter(existing -> existing.getCreatedAt() != null
                && existing.getConsumedAt() == null
                && existing.getInvalidatedAt() == null
                && existing.getCreatedAt().plus(appProperties.getOtp().getResendCooldown()).isAfter(now))
            .ifPresent(existing -> {
                throw new BadRequestException("Please wait before requesting another verification code");
            });

        otpRepository.findByEmailAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNull(normalizedEmail, purpose)
            .forEach(existing -> existing.setInvalidatedAt(now));

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        OtpVerification verification = new OtpVerification();
        verification.setEmail(normalizedEmail);
        verification.setPurpose(purpose);
        verification.setOtpHash(passwordEncoder.encode(otp));
        verification.setExpiresAt(now.plus(appProperties.getOtp().getExpiration()));
        otpRepository.save(verification);

        emailService.sendOtp(normalizedEmail, username, otp, appProperties.getOtp().getExpiration().toMinutes(), purpose);
    }

    @Transactional
    public String verifyOtp(String email, String otp, OtpPurpose purpose) {
        OtpVerification verification = otpRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email.trim().toLowerCase(), purpose)
            .orElseThrow(() -> new BadRequestException("Verification code is invalid or expired"));
        Instant now = Instant.now();

        if (verification.getConsumedAt() != null || verification.getInvalidatedAt() != null || !verification.getExpiresAt().isAfter(now)) {
            throw new BadRequestException("Verification code is invalid or expired");
        }
        if (verification.getAttemptCount() >= appProperties.getOtp().getMaxAttempts()) {
            verification.setInvalidatedAt(now);
            throw new BadRequestException("Too many invalid attempts. Request a new code.");
        }
        if (!passwordEncoder.matches(otp, verification.getOtpHash())) {
            verification.setAttemptCount(verification.getAttemptCount() + 1);
            if (verification.getAttemptCount() >= appProperties.getOtp().getMaxAttempts()) {
                verification.setInvalidatedAt(now);
            }
            throw new BadRequestException("Verification code is invalid or expired");
        }

        verification.setConsumedAt(now);
        if (purpose == OtpPurpose.PASSWORD_RESET) {
            String resetToken = randomToken();
            verification.setResetTokenHash(passwordEncoder.encode(resetToken));
            verification.setResetTokenExpiresAt(now.plus(appProperties.getOtp().getResetAuthorizationExpiration()));
            return resetToken;
        }
        return null;
    }

    @Transactional
    public void consumeResetAuthorization(String email, String resetToken) {
        OtpVerification verification = otpRepository.findTopByEmailAndPurposeAndResetTokenUsedAtIsNullOrderByCreatedAtDesc(
                email.trim().toLowerCase(), OtpPurpose.PASSWORD_RESET)
            .orElseThrow(() -> new BadRequestException("Password reset authorization is invalid or expired"));
        Instant now = Instant.now();
        if (verification.getResetTokenHash() == null
            || verification.getResetTokenExpiresAt() == null
            || !verification.getResetTokenExpiresAt().isAfter(now)
            || !passwordEncoder.matches(resetToken, verification.getResetTokenHash())) {
            throw new BadRequestException("Password reset authorization is invalid or expired");
        }
        verification.setResetTokenUsedAt(now);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
