package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.Query;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetSuiteConfigEntity;

/**
 * Checks that every property referenced by {@link NetSuiteConfigRepository}'s JPQL exists on
 * {@link NetSuiteConfigEntity}.
 * <p>
 * This module has no Spring context test, and nothing else on the classpath loads this repository,
 * so a mistyped property would not surface until the application failed to start in a deployed
 * environment. (The organisation module's equivalent query is validated for free, because
 * accounting_reporting_core's Spring tests load that repository.)
 * <p>
 * Scope: property names only. JPQL grammar is not parsed here — that would need an
 * EntityManagerFactory, and a typo'd property is the realistic failure this guards against.
 */
class NetSuiteConfigRepositoryQueryTest {

    /** Matches the {@code c.someProperty} aliases used in the query. */
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("\\bc\\.([a-zA-Z][a-zA-Z0-9]*)");

    @Test
    void everyPropertyReferencedByTheVerdictQueryExistsOnTheEntity() throws Exception {
        Method method = NetSuiteConfigRepository.class.getMethod(
                "recordValidationVerdict", String.class, long.class, String.class, String.class);

        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("recordValidationVerdict must carry a @Query").isNotNull();

        Set<String> referenced = new LinkedHashSet<>();
        Matcher matcher = PROPERTY_REFERENCE.matcher(query.value());
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }

        assertThat(referenced)
                .as("the query should reference the verdict columns and the guard predicate")
                .contains("validationStatus", "validationMessage", "organisationId", "revision");

        Set<String> declared = new LinkedHashSet<>();
        for (var field : NetSuiteConfigEntity.class.getDeclaredFields()) {
            declared.add(field.getName());
        }

        assertThat(declared)
                .as("JPQL references a property that does not exist on NetSuiteConfigEntity — "
                        + "this would fail at application startup, not at compile time")
                .containsAll(referenced);
    }

}
