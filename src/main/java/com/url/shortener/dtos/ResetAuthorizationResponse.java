package com.url.shortener.dtos;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResetAuthorizationResponse {
    private final String resetToken;
}
