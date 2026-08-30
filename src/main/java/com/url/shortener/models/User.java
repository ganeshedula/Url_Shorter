package com.url.shortener.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(length = 100)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role = Role.ROLE_USER;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion = 0L;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UrlMapping> urlMappings = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (username == null || username.isBlank()) {
            username = email;
        }
    }
}
