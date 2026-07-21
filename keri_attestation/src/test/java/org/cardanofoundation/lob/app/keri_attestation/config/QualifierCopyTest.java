package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

import org.springframework.beans.factory.annotation.Qualifier;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.service.KeriAgentService;
import org.cardanofoundation.lob.app.keri_attestation.service.KeriOobiService;
import org.cardanofoundation.signify.app.clienting.SignifyClient;

/**
 * Pins the fix for the {@code SignifyClient} qualifier being inert at runtime: {@code KeriAgentService}
 * and {@code KeriOobiService} put {@code @Qualifier("keriAttestationSignifyClient")} on the
 * {@code SignifyClient} field while relying on Lombok's {@code @RequiredArgsConstructor} to generate the
 * constructor. Spring only reads qualifiers off constructor <em>parameters</em>, and Lombok does not copy
 * field annotations onto the generated constructor unless {@code lombok.copyableAnnotations} lists them —
 * see the module-scoped {@code keri_attestation/lombok.config}. This test asserts, via reflection on the
 * actually-generated constructor, that the annotation really lands on the parameter — proof the config is
 * picked up, not just present on disk.
 */
class QualifierCopyTest {

    @Test
    void keriAgentServiceConstructor_carriesQualifierOnSignifyClientParameter() {
        assertQualifierOnSignifyClientParameter(KeriAgentService.class);
    }

    @Test
    void keriOobiServiceConstructor_carriesQualifierOnSignifyClientParameter() {
        assertQualifierOnSignifyClientParameter(KeriOobiService.class);
    }

    private static void assertQualifierOnSignifyClientParameter(Class<?> type) {
        Constructor<?> constructor = soleConstructor(type);

        Parameter signifyClientParameter = null;
        for (Parameter parameter : constructor.getParameters()) {
            if (parameter.getType().equals(SignifyClient.class)) {
                signifyClientParameter = parameter;
                break;
            }
        }
        assertThat(signifyClientParameter)
                .as("%s's generated constructor should have a SignifyClient parameter", type.getSimpleName())
                .isNotNull();

        Qualifier qualifier = signifyClientParameter.getAnnotation(Qualifier.class);
        assertThat(qualifier)
                .as("%s's SignifyClient constructor parameter should carry @Qualifier, copied from the field by "
                        + "lombok.config's lombok.copyableAnnotations", type.getSimpleName())
                .isNotNull();
        assertThat(qualifier.value()).isEqualTo("keriAttestationSignifyClient");
    }

    private static Constructor<?> soleConstructor(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        assertThat(constructors)
                .as("%s is expected to have exactly one (Lombok-generated) constructor", type.getSimpleName())
                .hasSize(1);
        return constructors[0];
    }
}
