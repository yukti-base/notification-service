package org.yuktisetu.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.yuktisetu.core.security.JwtAuthenticationFilter;
import org.yuktisetu.core.security.JwtTokenVerifier;
import org.yuktisetu.core.security.RestAccessDeniedHandler;
import org.yuktisetu.core.security.RestAuthenticationEntryPoint;

/**
 * Two populations hit this service and they authenticate differently.
 *
 * Students read their own bell with the ordinary user JWT, verified by core's
 * filter exactly as in every other service. Sibling services post dispatches
 * with a shared service token. Both filters run; whichever populates the context
 * first wins, and the @PreAuthorize on each controller states which it expects.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    @Value("${yuktisetu.notification.service-token:}")
    private String serviceToken;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtTokenVerifier tokenVerifier) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new RestAuthenticationEntryPoint())
                    .accessDeniedHandler(new RestAccessDeniedHandler())
            )
            .addFilterBefore(new ServiceTokenFilter(serviceToken),
                    UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new JwtAuthenticationFilter(tokenVerifier),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
