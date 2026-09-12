package com.onderogluserdar.ticketing.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(signingKey(properties)));
    }

    @Bean
    JwtDecoder accessTokenDecoder(JwtProperties properties) {
        return decoderRequiring(properties, TokenType.ACCESS);
    }

    @Bean
    JwtDecoder refreshTokenDecoder(JwtProperties properties) {
        return decoderRequiring(properties, TokenType.REFRESH);
    }

    private static JwtDecoder decoderRequiring(JwtProperties properties, TokenType required) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withSecretKey(signingKey(properties)).build();
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), typeValidator(required)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> typeValidator(TokenType required) {
        return token -> required.claimValue().equals(token.getClaimAsString(TokenType.CLAIM))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "unexpected token type", null));
    }

    private static SecretKey signingKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
