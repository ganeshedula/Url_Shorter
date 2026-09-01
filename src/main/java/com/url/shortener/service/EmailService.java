package com.url.shortener.service;

import com.url.shortener.exception.ServiceUnavailableException;
import com.url.shortener.models.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional OTP emails via the Brevo (formerly Sendinblue) HTTP API.
 *
 * <p>Using the HTTP API (port 443) instead of SMTP avoids the outbound SMTP port
 * blocks that Render and many other PaaS providers enforce on their free tiers.</p>
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code BREVO_API_KEY}   – your Brevo v3 API key (starts with "xkeysib-")</li>
 *   <li>{@code MAIL_FROM_ADDRESS} – verified sender address in your Brevo account</li>
 *   <li>{@code MAIL_SENDER_NAME}  – display name shown in the From field (default: Nexly)</li>
 * </ul>
 * </p>
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String BREVO_SEND_URL = "https://api.brevo.com/v3/smtp/email";

    private final RestClient restClient;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;

    public EmailService(
        RestClient.Builder restClientBuilder,
        @Value("${brevo.api-key:}") String apiKey,
        @Value("${app.mail.from:}") String senderEmail,
        @Value("${app.mail.sender-name:Nexly}") String senderName
    ) {
        this.restClient = restClientBuilder.build();
        this.apiKey = apiKey;
        this.senderEmail = senderEmail;
        this.senderName = senderName;
    }

    public void sendOtp(String recipient, String username, String otp, long expirationMinutes, OtpPurpose purpose) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Brevo API key is not configured (BREVO_API_KEY env var is missing or blank)");
            throw new ServiceUnavailableException("Verification email service is temporarily unavailable. Please try again shortly.");
        }
        if (senderEmail == null || senderEmail.isBlank()) {
            log.error("Sender email is not configured (MAIL_FROM_ADDRESS env var is missing or blank)");
            throw new ServiceUnavailableException("Verification email service is temporarily unavailable. Please try again shortly.");
        }

        String template = purpose == OtpPurpose.ACCOUNT_VERIFICATION
            ? "templates/verification-email.html"
            : "templates/password-reset-email.html";
        String subject = purpose == OtpPurpose.ACCOUNT_VERIFICATION
            ? "Verify your Nexly account"
            : "Reset your Nexly password";

        try {
            String htmlBody = applyTemplate(template, Map.of(
                "username", escapeHtml(username == null || username.isBlank() ? "there" : username),
                "otp", otp,
                "expirationMinutes", Long.toString(expirationMinutes)
            ));

            Map<String, Object> payload = Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", recipient)),
                "subject", subject,
                "htmlContent", htmlBody
            );

            var response = restClient.post()
                .uri(BREVO_SEND_URL)
                .header("api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("OTP email sent via Brevo API to domain {} for purpose {}",
                    recipient.substring(recipient.indexOf('@') + 1), purpose);
            } else {
                log.error("Brevo API returned non-2xx status {} for purpose {} to domain {}",
                    response.getStatusCode(), purpose, recipient.substring(recipient.indexOf('@') + 1));
                throw new ServiceUnavailableException("Verification email service is temporarily unavailable. Please try again shortly.");
            }
        } catch (ServiceUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            log.error("OTP email delivery failed via Brevo API for purpose {} to recipient domain {}: {}",
                purpose, recipient.substring(recipient.indexOf('@') + 1), exception.getMessage(), exception);
            throw new ServiceUnavailableException("Verification email service is temporarily unavailable. Please try again shortly.", exception);
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
