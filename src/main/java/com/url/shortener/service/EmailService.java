package com.url.shortener.service;

import com.url.shortener.exception.ServiceUnavailableException;
import com.url.shortener.models.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
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
            log.error("OTP email delivery is not configured: spring.mail.username resolved to blank");
            throw new ServiceUnavailableException("Verification email service is temporarily unavailable. Please try again shortly.");
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
            log.error("OTP email delivery failed for purpose {} to recipient domain {}: {}",
                purpose, recipient.substring(recipient.indexOf('@') + 1), exception.getMessage(), exception);
            String message = exception instanceof MailAuthenticationException
                ? "Verification email service authentication failed. Please try again shortly."
                : exception instanceof MailSendException
                    ? "Verification email service is temporarily unavailable. Please try again shortly."
                    : "Unable to deliver the verification email. Please try again shortly.";
            throw new ServiceUnavailableException(message, exception);
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
