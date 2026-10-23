package org.jai.fuelmarking.central.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * OIDC settings. The issuer itself comes from spring.security.oauth2.resourceserver.jwt.issuer-uri.
 *
 * @param clientId   public client the web app signs in with (authorization code + PKCE, no secret)
 * @param scope      scopes the web app requests
 * @param rolesClaim claim holding the user's roles; dotted paths reach nested claims (Keycloak: realm_access.roles)
 * @param adminRole  role allowed to manage master data (sites, users, PINs, API keys)
 * @param viewerRole role allowed to read reports
 * @param audience   if set, tokens must be issued for this audience (strongly recommended in production)
 */
@ConfigurationProperties("fuelmarking.auth")
public record AuthProperties(
        @DefaultValue("fuelmarking-web") String clientId,
        @DefaultValue("openid profile email") String scope,
        @DefaultValue("roles") String rolesClaim,
        @DefaultValue("fuelmarking-admin") String adminRole,
        @DefaultValue("fuelmarking-viewer") String viewerRole,
        @DefaultValue("") String audience) {
}
