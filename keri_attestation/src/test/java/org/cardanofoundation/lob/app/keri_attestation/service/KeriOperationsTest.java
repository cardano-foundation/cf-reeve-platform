package org.cardanofoundation.lob.app.keri_attestation.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import id.veridian.signify.generated.keria.model.CompletedOOBIOperation;
import id.veridian.signify.generated.keria.model.FailedOOBIOperation;
import id.veridian.signify.generated.keria.model.OperationStatus;

import org.junit.jupiter.api.Test;

/**
 * {@code operations().wait} RETURNS a done {@code FailedOperation} rather than throwing, so without this
 * check a failed KERIA operation is silently treated as a success.
 */
class KeriOperationsTest {

    @Test
    void failedOperationThrowsWithCodeAndStage() {
        FailedOOBIOperation failed = new FailedOOBIOperation()
                .name("oobi.EXAMPLE")
                .error(new OperationStatus().code(404).message("unable to resolve OOBI"));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> KeriOperations.requireNotFailed(failed, "wallet OOBI resolve"));

        assertTrue(e.getMessage().contains("wallet OOBI resolve"), e.getMessage());
        assertTrue(e.getMessage().contains("oobi.EXAMPLE"), e.getMessage());
        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertTrue(e.getMessage().contains("unable to resolve OOBI"), e.getMessage());
    }

    @Test
    void failedOperationWithoutErrorDetailsStillThrows() {
        FailedOOBIOperation failed = new FailedOOBIOperation().name("oobi.EXAMPLE");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> KeriOperations.requireNotFailed(failed, "schema OOBI resolve"));

        assertTrue(e.getMessage().contains("schema OOBI resolve"), e.getMessage());
    }

    @Test
    void doneNonFailedOperationPasses() {
        CompletedOOBIOperation done = new CompletedOOBIOperation().name("oobi.EXAMPLE");

        assertSame(done, KeriOperations.requireNotFailed(done, "wallet OOBI resolve"));
    }

    @Test
    void nullPassesThrough() {
        // No result to judge: only an explicit FailedOperation is a failure.
        assertNull(KeriOperations.requireNotFailed(null, "wallet OOBI resolve"));
    }
}
