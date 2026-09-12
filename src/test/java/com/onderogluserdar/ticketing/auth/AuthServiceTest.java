package com.onderogluserdar.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import com.onderogluserdar.ticketing.common.error.BusinessException;
import com.onderogluserdar.ticketing.common.error.ErrorCode;
import com.onderogluserdar.ticketing.user.Role;
import com.onderogluserdar.ticketing.user.User;
import com.onderogluserdar.ticketing.user.UserRepository;

@SpringBootTest
@Transactional
class AuthServiceTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private AuthService authService;

    @MockitoSpyBean
    private PasswordEncoder passwordEncoder;

    @MockitoSpyBean
    private UserRepository users;

    @Test
    void registersACustomerWithAHashedPassword() {
        User registered = authService.register("New.User@Example.COM", PASSWORD);

        assertThat(registered.getEmail()).isEqualTo("new.user@example.com");
        assertThat(registered.getRoles()).containsExactly(Role.CUSTOMER);
        assertThat(registered.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, registered.getPasswordHash()))
                .isTrue();
    }

    @Test
    void refusesAnEmailThatIsAlreadyRegisteredWhateverItsCase() {
        authService.register("taken@example.com", PASSWORD);

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.register("TAKEN@Example.com", PASSWORD))
                .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void authenticatesWithTheRightPasswordAndRecordsTheLogin() {
        User registered = authService.register("Login@Example.COM", PASSWORD);
        assertThat(registered.getLastLoginAt()).isNull();

        // Different casing again: one canonicalization rule has to serve both sides.
        User authenticated = authService.authenticate("  LOGIN@example.com ", PASSWORD);

        assertThat(authenticated.getId()).isEqualTo(registered.getId());
        assertThat(authenticated.getLastLoginAt()).isNotNull();
    }

    @Test
    void answersAnUnknownEmailAndAWrongPasswordIdentically() {
        authService.register("known@example.com", PASSWORD);

        BusinessException wrongPassword = catchBusinessException("known@example.com", "not the right password");
        BusinessException unknownEmail = catchBusinessException("nobody@example.com", PASSWORD);

        assertThat(wrongPassword.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(unknownEmail.getErrorCode()).isEqualTo(wrongPassword.getErrorCode());
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    void reportsAConcurrentRegistrationOfTheSameAddressAsAlreadyRegistered() {
        users.saveAndFlush(User.create("winner@example.com", "$2a$12$hash", Set.of(Role.CUSTOMER), Instant.now()));
        // Blinding the pre-check is what a concurrent registration does to it, leaving only the
        // database unique constraint. Different casing must still collide once canonicalized.
        doReturn(false).when(users).existsByEmail(anyString());

        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> authService.register("Winner@Example.COM", PASSWORD))
                .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED));
    }

    @Test
    void verifiesAPasswordEvenWhenNoSuchUserExists() {
        catchBusinessException("nobody-at-all@example.com", PASSWORD);

        verify(passwordEncoder).matches(eq(PASSWORD), anyString());
    }

    private BusinessException catchBusinessException(String email, String password) {
        try {
            authService.authenticate(email, password);
            throw new AssertionError("expected authentication to fail for " + email);
        } catch (BusinessException expected) {
            return expected;
        }
    }
}
