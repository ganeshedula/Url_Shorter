package com.url.shortener.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.dtos.RegisterRequest;
import com.url.shortener.security.JwtService;
import com.url.shortener.service.AuthService;
import com.url.shortener.service.GoogleOAuthService;
import com.url.shortener.config.AppProperties;
import com.url.shortener.service.RedisSessionService;
import com.url.shortener.service.UserDetailsServiceImpl;
import com.url.shortener.util.ClientInfo;
import com.url.shortener.util.ClientInfoExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    value = AuthController.class,
    excludeAutoConfiguration = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
    }
)
@AutoConfigureMockMvc(addFilters = false)
@Import(com.url.shortener.exception.GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private GoogleOAuthService googleOAuthService;

    @MockBean
    private AppProperties appProperties;

    @MockBean
    private ClientInfoExtractor clientInfoExtractor;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private RedisSessionService redisSessionService;

    @Test
    void registerReturnsBadRequestForInvalidPayload() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("invalid-email");
        request.setPassword("short");

        when(clientInfoExtractor.extract(ArgumentMatchers.any()))
            .thenReturn(ClientInfo.builder().browser("Chrome").operatingSystem("macOS").ipAddress("127.0.0.1").country("Unknown").build());

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void loginAcceptsValidPayload() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("password123");

        when(clientInfoExtractor.extract(ArgumentMatchers.any()))
            .thenReturn(ClientInfo.builder().browser("Chrome").operatingSystem("macOS").ipAddress("127.0.0.1").country("Unknown").build());

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
