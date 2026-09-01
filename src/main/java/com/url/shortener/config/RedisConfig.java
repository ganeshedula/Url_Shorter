package com.url.shortener.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis configuration with proper Upstash TLS/SSL support for Lettuce.
 *
 * <p>Upstash Redis requires SSL (TLS). Standard Spring Boot autoconfiguration
 * does not always wire Lettuce SSL correctly. This config explicitly builds
 * the Lettuce connection with {@code useSsl()} and disables peer verification
 * to avoid certificate chain issues on cloud JREs.</p>
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.username:}")
    private String username;

    @Value("${spring.data.redis.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${spring.data.redis.timeout:5s}")
    private Duration timeout;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration();
        serverConfig.setHostName(host);
        serverConfig.setPort(port);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }
        if (username != null && !username.isBlank()) {
            serverConfig.setUsername(username);
        }

        LettuceClientConfiguration clientConfig;
        if (sslEnabled) {
            clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .useSsl()
                .disablePeerVerification()
                .build();
        } else {
            clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .build();
        }

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
