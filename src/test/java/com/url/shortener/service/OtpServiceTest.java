package com.url.shortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.config.AppProperties;
import com.url.shortener.models.OtpPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        AppProperties appProperties = new AppProperties();
        appProperties.getOtp().setExpiration(Duration.ofMinutes(10));
        appProperties.getOtp().setResendCooldown(Duration.ofMinutes(1));
        appProperties.getOtp().setMaxAttempts(5);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        otpService = new OtpService(redisTemplate, passwordEncoder, emailService, appProperties, new ObjectMapper());
    }

    @Test
    void issueOtpStoresOnlyAHashInRedisWithExpiryThenEmailsTheCode() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("otp-hash");

        otpService.issueOtp("USER@example.com", "User", OtpPurpose.ACCOUNT_VERIFICATION);

        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(otpCaptor.capture());
        assertThat(otpCaptor.getValue()).matches("\\d{6}");
        verify(valueOperations).set(anyString(), eq("otp-hash"), eq(Duration.ofMinutes(10)));
        verify(emailService).sendOtp(eq("user@example.com"), eq("User"), eq(otpCaptor.getValue()), eq(10L), eq(OtpPurpose.ACCOUNT_VERIFICATION));
    }

    @Test
    void validOtpIsDeletedAfterVerificationSoItCannotBeReused() {
        when(valueOperations.get(anyString())).thenReturn("otp-hash");
        when(passwordEncoder.matches("123456", "otp-hash")).thenReturn(true);

        otpService.verifyOtp("user@example.com", "123456", OtpPurpose.ACCOUNT_VERIFICATION);

        verify(redisTemplate).delete(any(java.util.Collection.class));
    }
}
