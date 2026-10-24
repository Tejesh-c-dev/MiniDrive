package com.minidrive.config;

import com.minidrive.security.JwtAuthenticationFilter;
import com.minidrive.security.RateLimitFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.ObjectPostProcessor;

import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(
            HttpSecurity http) throws Exception {

        RequestMatcher localHealth = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/actuator/health"),
                new IpAddressMatcher("127.0.0.1"));
        RequestMatcher localHealthComponents = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/actuator/health/**"),
                new IpAddressMatcher("127.0.0.1"));
        RequestMatcher localPrometheus = new AndRequestMatcher(
                PathPatternRequestMatcher.pathPattern("/actuator/prometheus"),
                new IpAddressMatcher("127.0.0.1"));

        http
                .securityMatcher(PathPatternRequestMatcher.pathPattern("/actuator/**"))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(localHealth, localHealthComponents, localPrometheus)
                        .permitAll()
                        .anyRequest()
                        .denyAll());

        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http) throws Exception {

        http
                .securityMatcher(PathPatternRequestMatcher.pathPattern("/api/**"))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))

                .csrf(csrf -> csrf.disable())

                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

                // StreamingResponseBody commits from an async thread while the
                // request thread is still unwinding Spring Security's filters.
                // Write security headers before entering that async work so
                // HeaderWriterFilter cannot race with Tomcat while it serializes
                // the response headers.
                .headers(headers -> headers.withObjectPostProcessor(
                        new ObjectPostProcessor<HeaderWriterFilter>() {
                            @Override
                            public <O extends HeaderWriterFilter> O postProcess(O filter) {
                                filter.setShouldWriteHeadersEagerly(true);
                                return filter;
                            }
                        }))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/health",
                                "/api/auth/register",
                                "/api/auth/login")
                        .permitAll()
                        // Public share links: anonymous read/download only. The
                        // match is narrowed to GET so no write method under
                        // /api/share can ever be reached anonymously, and
                        // /api/files/** stays fully authenticated.
                        .requestMatchers(HttpMethod.GET, "/api/share/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())

                .addFilterBefore(
                        rateLimitFilter,
                        UsernamePasswordAuthenticationFilter.class);

        http.addFilterAfter(jwtAuthenticationFilter, RateLimitFilter.class);

        return http.build();
    }
}
