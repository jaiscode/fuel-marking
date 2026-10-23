package org.jai.fuelmarking.central.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lets the web app discover how to sign in, so one build works for every environment. Nothing secret here. */
@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final AuthProperties props;
    private final String issuer;

    public PublicController(AuthProperties props, @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuer) {
        this.props = props;
        this.issuer = issuer;
    }

    @GetMapping("/auth-config")
    Map<String, String> authConfig() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("issuer", issuer);
        m.put("clientId", props.clientId());
        m.put("scope", props.scope());
        if (!props.audience().isBlank()) m.put("audience", props.audience());
        return m;
    }
}
