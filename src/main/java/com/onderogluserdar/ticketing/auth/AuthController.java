package com.onderogluserdar.ticketing.auth;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.onderogluserdar.ticketing.auth.dto.LoginRequest;
import com.onderogluserdar.ticketing.auth.dto.RefreshRequest;
import com.onderogluserdar.ticketing.auth.dto.RegisterRequest;
import com.onderogluserdar.ticketing.auth.dto.TokenResponse;
import com.onderogluserdar.ticketing.auth.dto.UserResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Authentication", description = "Registration and token issuing. All operations are public.")
@SecurityRequirements
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Register a customer", description = "Self-registration always creates a CUSTOMER.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "409", description = "Email already registered", content = @Content)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return UserResponse.from(authService.register(request.email(), request.password()));
    }

    @Operation(summary = "Log in", description = "Rate limited per client address.")
    @ApiResponse(responseCode = "200", description = "Access and refresh tokens")
    @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @Content)
    @ApiResponse(
            responseCode = "429",
            description = "Too many attempts from this client address",
            headers =
                    @Header(
                            name = HttpHeaders.RETRY_AFTER,
                            description = "Whole seconds to wait before retrying",
                            schema = @Schema(type = "integer")),
            content = @Content)
    @PostMapping("/login")
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @Operation(summary = "Exchange a refresh token", description = "Access tokens are rejected here.")
    @ApiResponse(responseCode = "200", description = "A new token pair")
    @ApiResponse(responseCode = "401", description = "Invalid or wrong-type token", content = @Content)
    @PostMapping("/refresh")
    TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }
}
