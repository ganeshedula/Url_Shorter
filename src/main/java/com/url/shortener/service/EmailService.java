package com.url.shortener.service;

import com.url.shortener.exception.BadRequestException;
import com.url.shortener.models.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;
    private final String sender;

    public EmailService(JavaMailSender mailSender, @Value("${spring.mail.username:}") String sender) {
        this.mailSender = mailSender;
        this.sender = sender;
    }

    public void sendOtp(String recipient, String username, String otp, long expirationMinutes, OtpPurpose purpose) {
        if (sender.isBlank()) {
            throw new BadRequestException("Email delivery is not configured");
        }
        String template = purpose == OtpPurpose.ACCOUNT_VERIFICATION
            ? "templates/verification-email.html"
            : "templates/password-reset-email.html";
        String subject = purpose == OtpPurpose.ACCOUNT_VERIFICATION
            ? "Verify your URL Shortener account"
            : "Reset your URL Shortener password";
        try {
            String body = applyTemplate(template, Map.of(
                "username", escapeHtml(username == null || username.isBlank() ? "there" : username),
                "otp", otp,
                "expirationMinutes", Long.toString(expirationMinutes)
            ));
            var message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(sender);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, true);
            mailSender.send(message);
        } catch (Exception exception) {
            log.warn("OTP email delivery failed for purpose {}: {}", purpose, exception.getClass().getSimpleName());
            throw new BadRequestException("Unable to deliver the verification email. Please try again.");
        }
    }

    private String applyTemplate(String path, Map<String, String> values) {
        try {
            String template = new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return template;
        } catch (IOException exception) {
            throw new IllegalStateException("Email template could not be loaded", exception);
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
