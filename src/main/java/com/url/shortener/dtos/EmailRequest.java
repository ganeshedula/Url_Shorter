package com.url.shortener.dtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailRequest {
    @Email(message = "Email must be valid")
    @NotBlank(message = "Email is required")
    private String email;
}
