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
    public static final String DOCUMENT_PUBLISHING_UNAVAILABLE = "DOCUMENT_PUBLISHING_UNAVAILABLE";
    // Key cards
    public static final String CARD_CONTAINS_PRIVATE_KEY = "CARD_CONTAINS_PRIVATE_KEY";
    public static final String UNSUPPORTED_CARD_VERSION = "UNSUPPORTED_CARD_VERSION";

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

    /** Capability is switched off in this deployment (no IPFS → no publishing). */
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
