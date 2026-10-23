package org.cardanofoundation.lob.app.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The organisation module must never reference the netsuite module.
 * <p>
 * In a decentralized deployment the netsuite tables hold no data on the organisation tier, so
 * any such reference would work in a merged deployment and fail silently in a split one. The
 * two modules communicate exclusively through the domain events in
 * {@code organisation.domain.event.netsuite}.
 * <p>
 * Nothing else in this repository enforces module boundaries — there is no ArchUnit and no
 * Spring Modulith verification — so this test is the only guard.
 */
class ModuleBoundaryTest {

    private static final String NETSUITE_MODULE = "netsuite_altavia_erp_adapter";

    @Test
    void organisationDoesNotDependOnTheNetsuiteModule() throws IOException {
        Path sourceRoot = Path.of("src/main/java");

        assertThat(sourceRoot).as("organisation module source root should exist").exists();

        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            List<String> offenders = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(NetsuiteReference::isPresentIn)
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .toList();

            assertThat(offenders)
                    .as("organisation module must not reference %s — cross-module access goes through domain events only",
                            NETSUITE_MODULE)
                    .isEmpty();
        }
    }

    private static final class NetsuiteReference {

        static boolean isPresentIn(Path path) {
            try {
                return Files.readString(path).contains(NETSUITE_MODULE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
