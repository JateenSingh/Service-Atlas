package com.serviceatlas.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled Angular app (§2 Packaging).
 *
 * <p>Angular owns client-side routing, so a request for {@code /workspaces/3/canvas} must return
 * {@code index.html} and let the router take over. Anything under {@code /api/} is left alone, so a
 * mistyped API path still produces a JSON 404 rather than an HTML page.
 *
 * <p>When the jar is built with {@code -PskipFrontend} there is no {@code index.html} at all; the
 * resolver then falls through to a normal 404 instead of failing at startup.
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    private static final String STATIC_ROOT = "classpath:/static/";
    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_ROOT)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/") || !INDEX.exists()) {
                            return null;
                        }
                        return INDEX;
                    }
                });
    }
}
