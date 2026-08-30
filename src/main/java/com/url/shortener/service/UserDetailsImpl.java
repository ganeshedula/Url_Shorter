package com.url.shortener.service;

import com.url.shortener.models.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class UserDetailsImpl implements UserDetails {

    private final UUID id;
    private final String email;
    private final String password;
    private final long tokenVersion;
    private final Collection<? extends GrantedAuthority> authorities;

    public UserDetailsImpl(UUID id, String email, String password, long tokenVersion, Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.tokenVersion = tokenVersion;
        this.authorities = authorities;
    }

    public static UserDetailsImpl fromUser(User user) {
        return new UserDetailsImpl(
            user.getId(),
            user.getEmail(),
            user.getPassword(),
            user.getTokenVersion(),
            List.of(new SimpleGrantedAuthority(user.getRole().name()))
        );
    }

    @Override
    public String getUsername() {
        return email;
    }
}
