package org.cardanofoundation.lob.app.document_vault.service;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class VaultProblems {

    public static final String KEY_NOT_FOUND = "KEY_NOT_FOUND";
    public static final String ADDRESSBOOK_ENTRY_NOT_FOUND = "ADDRESSBOOK_ENTRY_NOT_FOUND";
    public static final String DOCUMENT_NOT_FOUND = "DOCUMENT_NOT_FOUND";
    public static final String RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    public static final String ORGANISATION_NOT_FOUND = "ORGANISATION_NOT_FOUND";
    public static final String USER_NOT_IN_ORGANISATION = "USER_NOT_IN_ORGANISATION";
    public static final String DUPLICATE_PUBLIC_KEY = "DUPLICATE_PUBLIC_KEY";
    public static final String NOT_KEY_OWNER = "NOT_KEY_OWNER";
    public static final String RECIPIENT_KEY_MISSING = "RECIPIENT_KEY_MISSING";
    public static final String RECIPIENT_ENTRY_MISSING = "RECIPIENT_ENTRY_MISSING";
    public static final String SENDER_KEY_MISSING = "SENDER_KEY_MISSING";
    public static final String SENDER_KEY_INVALID = "SENDER_KEY_INVALID";
    public static final String SLOT_KEY_INVALID = "SLOT_KEY_INVALID";
    public static final String UNSUPPORTED_ENVELOPE_VERSION = "UNSUPPORTED_ENVELOPE_VERSION";
    public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String TOO_MANY_SLOTS = "TOO_MANY_SLOTS";
    public static final String NOT_DOCUMENT_CREATOR = "NOT_DOCUMENT_CREATOR";
    public static final String DOCUMENT_PUBLISHED_IMMUTABLE = "DOCUMENT_PUBLISHED_IMMUTABLE";
    public static final String ALREADY_PUBLISHED = "ALREADY_PUBLISHED";

    // An attested publish - a request carrying attestationCeremonyId - fails closed on any of these.

    /** The keri_attestation ports are not wired up in this deployment. A plain publish never reaches
     *  this check. */
    public static final String ATTESTATION_UNAVAILABLE = "ATTESTATION_UNAVAILABLE";
    /** {@code attestationCeremonyId} was present but blank. Omit the field entirely for a plain
     *  publish. */
    public static final String ATTESTATION_CEREMONY_ID_BLANK = "ATTESTATION_CEREMONY_ID_BLANK";
    /** No freeze row for this document/ceremony pair, meaning the ceremony never reached ATTEST. */
    public static final String ATTESTATION_FREEZE_MISSING = "ATTESTATION_FREEZE_MISSING";
    /** The envelope's re-serialised SHA-256 no longer matches the freeze; the document changed after
     *  ATTEST and must be re-attested. */
    public static final String ATTESTED_CONTENT_CHANGED = "ATTESTED_CONTENT_CHANGED";
    /** The freeze is older than the configured {@code freeze-max-age} and must be re-attested. */
    public static final String ATTESTED_METADATA_MISMATCH = "ATTESTED_METADATA_MISMATCH";
    // Key cards
    public static final String CARD_CONTAINS_PRIVATE_KEY = "CARD_CONTAINS_PRIVATE_KEY";
    public static final String UNSUPPORTED_CARD_VERSION = "UNSUPPORTED_CARD_VERSION";
    /** A re-import would rewrite attested identity fields with values nothing has signed. The stored
     *  row's name/e-mail/label are covered by its attestation digest, so a card that cannot produce a
     *  matching attestation may not change them. */
    public static final String ATTESTED_CARD_FIELDS_IMMUTABLE = "ATTESTED_CARD_FIELDS_IMMUTABLE";

    private VaultProblems() {
    }

    private static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail notFound(String title, String detail) {
        return of(HttpStatus.NOT_FOUND, title, detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return of(HttpStatus.FORBIDDEN, USER_NOT_IN_ORGANISATION, detail);
    }

    public static ProblemDetail conflict(String title, String detail) {
        return of(HttpStatus.CONFLICT, title, detail);
    }

    public static ProblemDetail unprocessable(String title, String detail) {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, title, detail);
    }

    public static ProblemDetail payloadTooLarge(String detail) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE, PAYLOAD_TOO_LARGE, detail);
    }

    public static ProblemDetail badRequest(String title, String detail) {
        return of(HttpStatus.BAD_REQUEST, title, detail);
    }

    /** A capability this request needs is not available in this deployment. */
    public static ProblemDetail serviceUnavailable(String title, String detail) {
        return of(HttpStatus.SERVICE_UNAVAILABLE, title, detail);
    }

    public static ProblemDetail of403NotCreator() {
        return of(HttpStatus.FORBIDDEN, NOT_DOCUMENT_CREATOR,
                "Only the document creator or an admin may delete a document.");
    }

    public static ProblemDetail of403NotKeyOwner() {
        return of(HttpStatus.FORBIDDEN, NOT_KEY_OWNER,
                "Only the key owner or an admin may delete a key.");
    }
}
