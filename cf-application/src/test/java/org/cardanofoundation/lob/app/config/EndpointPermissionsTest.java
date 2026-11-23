package org.cardanofoundation.lob.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.support.security.access.PermissionCatalogue;
import org.cardanofoundation.lob.app.support.security.access.PermissionCatalogueEnvironmentPostProcessor;

/**
 * Every endpoint declares who may call it, with permissions the catalogue knows: a SpEL expression isn't compiled, so
 * a typo would only show up as nobody having access.
 */
class EndpointPermissionsTest {

    // A fake ERP that only exists in local profiles and that the NetSuite client calls without a token
    private static final Set<String> WITHOUT_PERMISSION = Set.of("AccountingCoreResourceNetSuiteMock");

    private static final Pattern PERMISSION = Pattern.compile("@permissions\\.can(?:Platform)?\\('(\\w+)', '(\\w+)'\\)");

    @Test
    void everyEndpointDeclaresWhoMayCallIt() throws ClassNotFoundException {
        List<String> open = new ArrayList<>();
        for (Method endpoint : endpoints()) {
            if (!WITHOUT_PERMISSION.contains(endpoint.getDeclaringClass().getSimpleName()) && expression(endpoint) == null) {
                open.add(name(endpoint));
            }
        }
        assertThat(open).isEmpty();
    }

    @Test
    void everyPermissionUsedIsInTheCatalogue() throws ClassNotFoundException {
        Set<String> known = catalogue().getPermissions();
        List<String> unknown = new ArrayList<>();
        for (Method endpoint : endpoints()) {
            String expression = expression(endpoint);
            if (expression == null) {
                continue;
            }
            Matcher permission = PERMISSION.matcher(expression);
            while (permission.find()) {
                String name = PermissionCatalogue.permission(permission.group(1), permission.group(2));
                if (!known.contains(name)) {
                    unknown.add(name(endpoint) + " uses " + name);
                }
            }
        }
        assertThat(unknown).isEmpty();
    }

    private static String expression(Method endpoint) {
        PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(endpoint, PreAuthorize.class);
        if (preAuthorize == null) {
            preAuthorize = AnnotatedElementUtils.findMergedAnnotation(endpoint.getDeclaringClass(), PreAuthorize.class);
        }
        return preAuthorize == null ? null : preAuthorize.value();
    }

    private static String name(Method endpoint) {
        return endpoint.getDeclaringClass().getSimpleName() + "." + endpoint.getName();
    }

    private static List<Method> endpoints() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Method> endpoints = new ArrayList<>();
        for (BeanDefinition controller : scanner.findCandidateComponents("org.cardanofoundation.lob.app")) {
            for (Method method : Class.forName(controller.getBeanClassName()).getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    endpoints.add(method);
                }
            }
        }
        assertThat(endpoints).as("the scan must find the platform's controllers").hasSizeGreaterThan(100);
        return endpoints;
    }

    private static PermissionCatalogue catalogue() {
        return new PermissionCatalogue(PermissionCatalogueEnvironmentPostProcessor.shippedCatalogue());
    }

}
