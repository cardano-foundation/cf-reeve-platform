package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.lang.NonNullApi;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * A successful verification has no message, so {@code validationMessage} is passed as null.
     * This package is {@code @NonNullApi}, which makes Spring Data wrap the repository proxy in a
     * {@code NullnessMethodInvocationValidator} that throws {@code IllegalArgumentException} on a
     * null argument — so without the explicit opt-out the *success* path fails at runtime while
     * the failure path works.
     * <p>
     * NetSuiteConfigServiceTest cannot catch this: it asserts against a Mockito mock, which has no
     * proxy and happily accepts null.
     */
    @Test
    void theOptionalVerdictMessageOptsOutOfThePackageNonNullDefault() throws Exception {
        assertThat(NetSuiteConfigRepository.class.getPackage().getAnnotation(NonNullApi.class))
                .as("premise of this test: the repository package declares @NonNullApi")
                .isNotNull();

        Method method = NetSuiteConfigRepository.class.getMethod(
                "recordValidationVerdict", String.class, long.class, String.class, String.class);

        Parameter validationMessage = method.getParameters()[3];

        assertThat(validationMessage.getAnnotation(Nullable.class))
                .as("validationMessage is null on the success path; without @Nullable, Spring Data "
                        + "rejects the call and every successful verification blows up at runtime")
                .isNotNull();
    }

    /**
     * {@code SimpleJpaRepository} is {@code @Transactional(readOnly = true)} at class level and
     * custom query methods inherit it. NetSuiteConfigService is deliberately non-transactional — an
     * outbound HTTP call must not hold a connection — so a {@code @Modifying} query here has no
     * writable transaction to join and Hibernate throws
     * {@code TransactionRequiredException: Executing an update/delete query}.
     * <p>
     * Like the nullability guard above, a Mockito mock cannot catch this.
     */
    @Test
    void everyModifyingQueryDeclaresItsOwnWritableTransaction() {
        for (Method method : NetSuiteConfigRepository.class.getDeclaredMethods()) {
            if (method.getAnnotation(Modifying.class) == null) {
                continue;
            }

            Transactional transactional = method.getAnnotation(Transactional.class);

            assertThat(transactional)
                    .as("%s is @Modifying but not @Transactional — it would inherit "
                            + "SimpleJpaRepository's read-only transaction and fail at runtime", method.getName())
                    .isNotNull();

            assertThat(transactional.readOnly())
                    .as("%s cannot write in a read-only transaction", method.getName())
                    .isFalse();
        }
    }

}
