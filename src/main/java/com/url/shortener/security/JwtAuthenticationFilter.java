package com.url.shortener.security;

import com.url.shortener.exception.InvalidTokenException;
import com.url.shortener.service.RedisSessionService;
import com.url.shortener.service.UserDetailsImpl;
import com.url.shortener.service.UserDetailsServiceImpl;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final RedisSessionService redisSessionService;

    public JwtAuthenticationFilter(
        JwtService jwtService,
        UserDetailsServiceImpl userDetailsService,
        RedisSessionService redisSessionService
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.redisSessionService = redisSessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (!StringUtils.hasText(header) || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            if (!jwtService.isTokenValid(token, JwtService.TOKEN_TYPE_ACCESS)) {
                throw new InvalidTokenException("Invalid access token");
            }
            if (redisSessionService.isAccessTokenBlacklisted(jwtService.extractId(token))) {
                throw new InvalidTokenException("Token has been invalidated");
            }
            String email = jwtService.extractEmail(token);
            UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(email);
            if (jwtService.extractTokenVersion(token) != userDetails.getTokenVersion()) {
                throw new InvalidTokenException("Token has been globally invalidated");
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                userDetails.getAuthorities()
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | InvalidTokenException exception) {
            log.debug("JWT authentication failed: {}", exception.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
