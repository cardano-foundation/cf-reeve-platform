package org.cardanofoundation.lob.app.keri_attestation.service;

import id.veridian.signify.generated.keria.model.FailedOperation;
import id.veridian.signify.generated.keria.model.Operation;
import id.veridian.signify.generated.keria.model.OperationStatus;

/**
 * Checks the result of {@code operations().wait(...)}.
 *
 * <p>{@code wait} RETURNS a done operation even when KERIA failed it — a {@link FailedOperation} carrying
 * the error — and throws {@code OperationFailedException} only when a DEPENDENCY operation failed. So a
 * bare {@code wait} treats a failed operation as success. Every wait result goes through
 * {@link #requireNotFailed}; callers keep their existing {@code OperationFailedException} handling for
 * the dependency case.
 *
 * <p>Deliberately no assertion on the Completed-subtype: a wrong expectation there would break a working
 * path at runtime. A {@code null} result has nothing to judge and passes through unchanged.
 */
public final class KeriOperations {

    private KeriOperations() {
    }

    /**
     * Returns {@code result} unchanged unless it is a {@link FailedOperation}, in which case it throws a
     * {@link RuntimeException} naming {@code stage}, the operation and KERIA's error code and message.
     */
    public static <T extends Operation> T requireNotFailed(T result, String stage) {
        if (result instanceof FailedOperation failed) {
            OperationStatus error = failed.getError();
            String detail = error == null
                    ? "no error details"
                    : "code " + error.getCode() + ": " + error.getMessage();
            throw new RuntimeException("KERIA " + stage + " operation " + failed.getName()
                    + " failed (" + detail + ")");
        }
        return result;
    }
}
