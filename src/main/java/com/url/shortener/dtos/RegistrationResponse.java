package com.url.shortener.dtos;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegistrationResponse {
    private final String email;
}
