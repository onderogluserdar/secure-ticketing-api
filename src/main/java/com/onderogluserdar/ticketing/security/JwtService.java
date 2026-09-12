package com.onderogluserdar.ticketing.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;

@Service
public class JwtService {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final JwtDecoder refreshTokenDecoder;
    private final JwtProperties properties;

    JwtService(
            JwtEncoder encoder,
            @Qualifier("refreshTokenDecoder") JwtDecoder refreshTokenDecoder,
            JwtProperties properties) {
        this.encoder = encoder;
        this.refreshTokenDecoder = refreshTokenDecoder;
        this.properties = properties;
    }

    public String issueAccessToken(User user) {
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
        return encode(claims(user.getId(), TokenType.ACCESS, properties.accessTokenTtl())
                .claim(ROLES_CLAIM, roles)
                .build());
    }

    public String issueRefreshToken(User user) {
        return encode(claims(user.getId(), TokenType.REFRESH, properties.refreshTokenTtl())
                .build());
    }

    public UUID userIdFromRefreshToken(String token) {
        try {
            String subject = refreshTokenDecoder.decode(token).getSubject();
            if (subject == null) {
                throw new BadJwtException("token carries no subject");
            }
            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException rejected) {
            // Deliberately identical whatever the reason, so probing cannot distinguish causes.
            throw new BusinessException(ErrorCode.INVALID_TOKEN, "token is not valid");
        }
    }

    public long accessTokenSecondsToLive() {
        return properties.accessTokenTtl().toSeconds();
    }

    private static JwtClaimsSet.Builder claims(UUID userId, TokenType type, java.time.Duration ttl) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(TokenType.CLAIM, type.claimValue());
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
