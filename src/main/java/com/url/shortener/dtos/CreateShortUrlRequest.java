package com.url.shortener.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
public class CreateShortUrlRequest {

    @NotBlank(message = "URL is required")
    @jakarta.validation.constraints.Size(max = 2048, message = "URL must not exceed 2048 characters")
    @Pattern(
        regexp = "^(?i)https?://.+",
        message = "URL must start with http:// or https://"
    )
    private String url;

    @jakarta.validation.constraints.Future(message = "Expiration date must be in the future")
    private OffsetDateTime expirationDate;
}
