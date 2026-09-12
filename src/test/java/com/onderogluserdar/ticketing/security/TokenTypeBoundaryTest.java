package com.onderogluserdar.ticketing.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;

@SpringBootTest
class TokenTypeBoundaryTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    @Qualifier("accessTokenDecoder")
    private JwtDecoder accessTokenDecoder;

    private String accessToken;
    private String refreshToken;

    @BeforeEach
    void issueBothKinds() {
        User user = User.create("boundary@example.com", "$2a$12$hash", Set.of(Role.CUSTOMER), Instant.now());
        accessToken = jwtService.issueAccessToken(user);
        refreshToken = jwtService.issueRefreshToken(user);
    }

    @Test
    void aRefreshTokenIsNotAcceptedWhereAnAccessTokenIsRequired() {
        assertThatExceptionOfType(JwtException.class).isThrownBy(() -> accessTokenDecoder.decode(refreshToken));
    }

    @Test
    void anAccessTokenIsNotAcceptedWhereARefreshTokenIsRequired() {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> jwtService.userIdFromRefreshToken(accessToken))
                .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    @Test
    void anAccessTokenCarriesTheRolesUsedForAuthorization() {
        assertThat(accessTokenDecoder.decode(accessToken).getClaimAsStringList(JwtService.ROLES_CLAIM))
                .containsExactly("CUSTOMER");
    }
}
