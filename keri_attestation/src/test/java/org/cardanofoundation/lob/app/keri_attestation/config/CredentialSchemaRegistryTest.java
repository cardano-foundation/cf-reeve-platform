package org.cardanofoundation.lob.app.keri_attestation.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.cardanofoundation.lob.app.keri_attestation.config.CredentialSchema.TrustModel;

/**
 * Startup validation is the whole point of this class, so most of these tests assert a refusal to
 * start.
 *
 * <p>The failure being guarded against is not a crash but a silent one: an empty trust-anchor list
 * reads as "no restriction", and a mis-templated {@code ${VAR:}} produces exactly that. Every case
 * below that expects a throw is a deployment that would otherwise have come up looking healthy while
 * trusting anyone.
 */
class CredentialSchemaRegistryTest {

    private static final String VLEI = "EBfdlu8R27Fbx-ehrqwImnK-8Cm79sqbAQ4MmvEAYqao";
    private static final String EMPLOYEE = "EL9oOWU_7zQn_rD--Xsgi3giCWnFDaNvFMUGTOZx1ARO";
    private static final String GLEIF_ROOT = "EDvAyRTcMlEIhcvIZ3lHfd2SxfXaLoJJlYyUp2Nx7Uwk";
    private static final String ISSUER = "EFoundationIssuerAid000000000000000000000";

    private static CredentialSchema chained(String said, String... roots) {
        return new CredentialSchema(said, "vLEI Legal Entity", TrustModel.CHAINED, List.of(roots),
                List.of(), List.of());
    }

    private static CredentialSchema standalone(String said, String... issuers) {
        return new CredentialSchema(said, "Foundation Employee", TrustModel.STANDALONE, List.of(),
                List.of(issuers), List.of());
    }

    @Test
    void bothTrustModelsCoexistInOneRegistry() {
        CredentialSchemaRegistry registry = new CredentialSchemaRegistry(
                List.of(chained(VLEI, GLEIF_ROOT), standalone(EMPLOYEE, ISSUER)));

        assertTrue(registry.accepts(VLEI));
        assertTrue(registry.accepts(EMPLOYEE));
        assertEquals(TrustModel.CHAINED, registry.find(VLEI).orElseThrow().trustModel());
        assertEquals(TrustModel.STANDALONE, registry.find(EMPLOYEE).orElseThrow().trustModel());
        assertEquals(List.of(GLEIF_ROOT), registry.find(VLEI).orElseThrow().trustAnchors());
        assertEquals(List.of(ISSUER), registry.find(EMPLOYEE).orElseThrow().trustAnchors());
    }

    @Test
    void anUnknownSchemaIsNotAccepted() {
        CredentialSchemaRegistry registry = new CredentialSchemaRegistry(List.of(chained(VLEI, GLEIF_ROOT)));

        assertFalse(registry.accepts(EMPLOYEE));
        assertFalse(registry.accepts(null));
        assertTrue(registry.find("ENotConfigured").isEmpty());
    }

    /** The headline requirement: adding a schema is configuration, not code. */
    @Test
    void aThirdSchemaNeedsNothingButConfiguration() {
        CredentialSchemaRegistry registry = new CredentialSchemaRegistry(List.of(
                chained(VLEI, GLEIF_ROOT), standalone(EMPLOYEE, ISSUER),
                standalone("ESomeFutureSchema", "EFutureIssuer")));

        assertTrue(registry.accepts("ESomeFutureSchema"));
        assertEquals(3, registry.schemaSaids().size());
    }

    @Test
    void aChainedSchemaWithNoTrustedRootsRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(chained(VLEI))));

        assertTrue(e.getMessage().contains("trusted-roots"));
        assertTrue(e.getMessage().contains("would accept any issuer"));
    }

    @Test
    void aStandaloneSchemaWithNoTrustedIssuersRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(standalone(EMPLOYEE))));

        assertTrue(e.getMessage().contains("trusted-issuers"));
    }

    /**
     * The exact shape of a mis-templated {@code ${KERI_GLEIF_ROOT_AID:}}: a list with one blank entry,
     * which a naive filter would reduce to an empty list and therefore to "trust anyone".
     */
    @Test
    void aBlankTrustAnchorIsRejectedRatherThanQuietlyDropped() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(chained(VLEI, GLEIF_ROOT, "  "))));

        assertTrue(e.getMessage().contains("blank entry"));
        assertTrue(e.getMessage().contains("environment variable"));
    }

    @Test
    void aSchemaWithNoTrustModelRefusesToStart() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(
                        new CredentialSchema(VLEI, "x", null, List.of(GLEIF_ROOT), List.of(), List.of()))));

        assertTrue(e.getMessage().contains("trust-model"));
    }

    @Test
    void aSchemaWithNoSaidRefusesToStart() {
        assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(chained("  ", GLEIF_ROOT))));
    }

    @Test
    void aDuplicateSchemaSaidRefusesToStartRatherThanSilentlyPickingOne() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(
                        chained(VLEI, GLEIF_ROOT), standalone(VLEI, ISSUER))));

        assertTrue(e.getMessage().contains("duplicates"));
    }

    /**
     * An empty registry accepts nothing, which is the safe direction and the honest state of a
     * deployment that has not configured credential verification. Refusing to boot on it would punish
     * the safe case while the dangerous one — a schema present but with no trust anchors — is what
     * actually needs to fail.
     */
    @Test
    void anEmptyRegistryStartsAndAcceptsNothing() {
        CredentialSchemaRegistry registry = new CredentialSchemaRegistry(List.of());

        assertTrue(registry.isEmpty());
        assertFalse(registry.accepts(VLEI));
    }

    /** Every problem at once, so a misconfigured deployment is fixed in one pass. */
    @Test
    void allProblemsAreReportedTogether() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CredentialSchemaRegistry(List.of(chained(VLEI), standalone(EMPLOYEE))));

        assertTrue(e.getMessage().contains(VLEI));
        assertTrue(e.getMessage().contains(EMPLOYEE));
    }

    @Test
    void oobisAreCollectedAcrossSchemasWithoutDuplicates() {
        CredentialSchemaRegistry registry = new CredentialSchemaRegistry(List.of(
                new CredentialSchema(VLEI, "a", TrustModel.CHAINED, List.of(GLEIF_ROOT), List.of(),
                        List.of("https://oobi/one", "https://oobi/shared")),
                new CredentialSchema(EMPLOYEE, "b", TrustModel.STANDALONE, List.of(), List.of(ISSUER),
                        List.of("https://oobi/shared", "https://oobi/two"))));

        assertEquals(List.of("https://oobi/one", "https://oobi/shared", "https://oobi/two"),
                registry.allOobis());
    }

    @Test
    void aSchemaWithoutANameDisplaysItsSaid() {
        CredentialSchema unnamed = new CredentialSchema(VLEI, null, TrustModel.CHAINED,
                List.of(GLEIF_ROOT), List.of(), List.of());

        assertEquals(VLEI, unnamed.displayName());
    }
}
