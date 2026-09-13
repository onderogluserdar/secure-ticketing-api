package com.onderogluserdar.ticketing.security;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.onderogluserdar.ticketing.user.Role;

@Configuration
class SecurityConfig {

    private static final String ADMIN = Role.ADMIN.name();
    private static final String ORGANIZER = Role.ORGANIZER.name();
    private static final String CUSTOMER = Role.CUSTOMER.name();

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("accessTokenDecoder") JwtDecoder accessTokenDecoder,
            AuthenticationEntryPoint authenticationEntryPoint,
            AccessDeniedHandler accessDeniedHandler)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/api/auth/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        .requestMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole(ADMIN)
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/events/public")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events")
                        .hasAnyRole(ORGANIZER, ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/events")
                        .hasAnyRole(ORGANIZER, ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/events/*")
                        .hasAnyRole(ORGANIZER, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/events/*/publish")
                        .hasAnyRole(ORGANIZER, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/events/*/reservations")
                        .hasAnyRole(CUSTOMER, ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/reservations/*/confirm", "/api/reservations/*/cancel")
                        .hasAnyRole(CUSTOMER, ADMIN)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.decoder(accessTokenDecoder).jwtAuthenticationConverter(rolesFromAccessToken())))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    private static JwtAuthenticationConverter rolesFromAccessToken() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
