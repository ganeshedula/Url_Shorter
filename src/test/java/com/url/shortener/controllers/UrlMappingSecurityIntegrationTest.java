package com.url.shortener.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.url.shortener.dtos.LoginRequest;
import com.url.shortener.models.Role;
import com.url.shortener.models.User;
import com.url.shortener.repo.UserRepository;
import com.url.shortener.service.GeoLocationClient;
import com.url.shortener.service.RedisSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlMappingSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private RedisSessionService redisSessionService;

    @MockBean
    private GeoLocationClient geoLocationClient;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        when(geoLocationClient.lookup(anyString())).thenReturn(Optional.empty());
        createUser("owner@example.com");
        createUser("other@example.com");
    }

    @Test
    void protectedUrlEndpointsEnforceOwnershipAndRejectUnauthenticatedRequests() throws Exception {
        String ownerToken = login("owner@example.com");
        String otherToken = login("other@example.com");
        JsonNode created = createUrl(ownerToken, "https://example.com/owned");
        String id = created.path("data").path("id").asText();

        mockMvc.perform(get("/api/url/" + id))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/url/" + id).header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/url/" + id)
                .header("Authorization", "Bearer " + otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/url/" + id).header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/url/" + id).header("Authorization", "Bearer " + ownerToken))
            .andExpect(status().isOk());
    }

    @Test
    void urlCreationRejectsDangerousSchemesAndRedirectsOnlyStoredHttpUrls() throws Exception {
        String token = login("owner@example.com");
        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"javascript:alert(1)\"}"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"data:text/html,hello\"}"))
            .andExpect(status().isBadRequest());

        JsonNode created = createUrl(token, "https://example.com/redirect-target");
        String shortCode = created.path("data").path("shortCode").asText();
        mockMvc.perform(get("/" + shortCode))
            .andExpect(status().isFound())
            .andExpect(header().string("Location", "https://example.com/redirect-target"));
    }

    private JsonNode createUrl(String token, String url) throws Exception {
        String body = mockMvc.perform(post("/api/url")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body);
    }

    private String login(String email) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword("password123");
        String body = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    private void createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.ROLE_USER);
        userRepository.save(user);
    }
}
