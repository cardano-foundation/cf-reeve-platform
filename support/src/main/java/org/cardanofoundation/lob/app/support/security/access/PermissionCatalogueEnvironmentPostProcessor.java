package org.cardanofoundation.lob.app.support.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * Adds the platform's permission catalogue ({@value #LOCATION}) as the last property source, so every application
 * using this module has lob.permissions without configuring it, and anything the application sets wins.
 * Registered in META-INF/spring.factories.
 */
public class PermissionCatalogueEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String LOCATION = "org/cardanofoundation/lob/support/reeve-permissions.yml";
    public static final String PROPERTY_SOURCE_NAME = "reevePermissions";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            read().forEach(environment.getPropertySources()::addLast);
        }
    }

    /**
     * @return the catalogue the platform ships, bound without starting Spring Boot (e.g. for tests)
     */
    public static PermissionProperties shippedCatalogue() {
        return new Binder(ConfigurationPropertySources.from(read()))
                .bind("lob.permissions", PermissionProperties.class)
                .orElseThrow(() -> new IllegalStateException(LOCATION + " has no lob.permissions"));
    }

    private static MutablePropertySources read() {
        try {
            MutablePropertySources sources = new MutablePropertySources();
            new YamlPropertySourceLoader().load(PROPERTY_SOURCE_NAME, new ClassPathResource(LOCATION)).forEach(sources::addLast);
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the permission catalogue " + LOCATION, e);
        }
    }

    @Override
    public int getOrder() {
        // After the application's own config files are loaded; the position among sources comes from addLast
        return Ordered.LOWEST_PRECEDENCE;
    }

}
