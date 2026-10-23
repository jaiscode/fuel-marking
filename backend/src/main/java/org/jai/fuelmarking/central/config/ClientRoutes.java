package org.jai.fuelmarking.central.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web app's client-side routes. A reload or deep link (including the identity provider sending the browser back to
 * /signed-out) must get index.html. One list drives both the forwarding and the security rules so they cannot drift:
 * when you add a page to the Angular router, add its path here.
 */
@Configuration
public class ClientRoutes implements WebMvcConfigurer {
    public static final String[] PATHS = {
            "/signed-out", "/no-access", "/auth-error",
            "/markings", "/markings/{id}", "/inventory", "/incidents", "/shifts", "/transfers", "/stock-counts",
            "/admin/sites", "/admin/omcs", "/admin/fuel-types", "/admin/container-types", "/admin/rum-types", "/admin/jerricans", "/admin/users"
    };

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : PATHS) registry.addViewController(path).setViewName("forward:/index.html");
    }
}
