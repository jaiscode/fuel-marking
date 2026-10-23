package org.jai.fuelmarking.central.config;

import org.jai.fuelmarking.central.auth.AudienceValidator;
import org.jai.fuelmarking.central.auth.AuthProperties;
import org.jai.fuelmarking.central.auth.RoleConverter;
import org.jai.fuelmarking.central.master.ApiKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    /** Marking sites (machines): API key per site. */
    @Bean
    @Order(1)
    SecurityFilterChain syncChain(HttpSecurity http, ApiKeys apiKeys) throws Exception {
        return http.securityMatcher("/api/sync/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new SiteKeyFilter(apiKeys), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("SITE"))
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (req, res, ex) -> res.sendError(401, "Invalid or missing site credentials")))
                .build();
    }

    /** How the web app finds the identity provider. Public by design and contains nothing secret. */
    @Bean
    @Order(2)
    SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/api/public/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    /**
     * People using the web app: OIDC access tokens (bearer). Administration needs the admin role; reading reports needs
     * the viewer role (admins can read too). Bearer tokens are not sent by the browser on its own, so CSRF does not apply.
     */
    @Bean
    @Order(3)
    SecurityFilterChain webChain(HttpSecurity http, AuthProperties props) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        RoleConverter roles = new RoleConverter(props);
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.securityMatcher("/api/admin/**", "/api/web/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("VIEWER", "ADMIN"))
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain denyRest(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll() // status codes set via sendError are re-dispatched here
                        // the web app's static files; its data calls are protected under /api/**
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/*.js", "/*.css", "/*.ico", "/*.txt", "/media/**", "/assets/**").permitAll()
                        .requestMatchers(HttpMethod.GET, ClientRoutes.PATHS).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * Validates signature (keys fetched from the provider), expiry, issuer and - when configured - audience.
     * The provider is contacted lazily so the backend can start while it is briefly unreachable.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer, AuthProperties props) {
        if (issuer.isBlank())
            throw new IllegalStateException("Set spring.security.oauth2.resourceserver.jwt.issuer-uri (env OIDC_ISSUER_URI) to your OIDC provider's issuer URL.");
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            OAuth2TokenValidator<Jwt> validator = props.audience().isBlank()
                    ? JwtValidators.createDefaultWithIssuer(issuer)
                    : new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), new AudienceValidator(props.audience()));
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }
}
