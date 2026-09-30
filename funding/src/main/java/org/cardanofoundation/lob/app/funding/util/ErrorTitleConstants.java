package org.cardanofoundation.lob.app.funding.util;

public final class ErrorTitleConstants {

    private ErrorTitleConstants() {}

    public static final String PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND";
    /** PUT /projects/{projectId} only updates a project's whole structure starting from a root — a sub-project id is rejected (LOB-2365 follow-up). */
    public static final String PROJECT_NOT_ROOT = "PROJECT_NOT_ROOT";
    public static final String PROJECT_ALREADY_EXISTS = "PROJECT_ALREADY_EXISTS";
    public static final String PROJECT_TITLE_ALREADY_EXISTS = "PROJECT_TITLE_ALREADY_EXISTS";
    /** A caller-supplied proId collides with an existing one in the same scope (root: per organisation; sub-project: per parent) — see LOB-2384. */
    public static final String PROJECT_PROID_ALREADY_EXISTS = "PROJECT_PROID_ALREADY_EXISTS";
    public static final String PROJECT_FUNDING_ID_ALREADY_USED = "PROJECT_FUNDING_ID_ALREADY_USED";
    public static final String PROJECT_FIELDS_REQUIRED = "PROJECT_FIELDS_REQUIRED";
    public static final String PROJECT_AMOUNT_INVALID = "PROJECT_AMOUNT_INVALID";
    public static final String CURRENCY_INVALID = "CURRENCY_INVALID";
    /** A project's new total no longer covers its own milestones'/sub-projects' already-declared totals — a hard reject, not a flag; see LOB-2365 follow-up. */
    public static final String PROJECT_AMOUNT_BELOW_MILESTONES = "PROJECT_AMOUNT_BELOW_MILESTONES";
    public static final String PROJECT_AMOUNT_BELOW_SUBPROJECTS = "PROJECT_AMOUNT_BELOW_SUBPROJECTS";
    public static final String PARENT_PROJECT_NOT_FOUND = "PARENT_PROJECT_NOT_FOUND";
    public static final String PARENT_PROJECT_ORG_MISMATCH = "PARENT_PROJECT_ORG_MISMATCH";
    public static final String SUBPROJECT_AMOUNT_EXCEEDS_PARENT = "SUBPROJECT_AMOUNT_EXCEEDS_PARENT";
    public static final String SUBPROJECT_TOTAL_EXCEEDS_PARENT = "SUBPROJECT_TOTAL_EXCEEDS_PARENT";
    public static final String MILESTONE_NOT_FOUND = "MILESTONE_NOT_FOUND";
    public static final String MILESTONE_ALREADY_EXISTS = "MILESTONE_ALREADY_EXISTS";
    public static final String MILESTONE_TITLE_ALREADY_EXISTS = "MILESTONE_TITLE_ALREADY_EXISTS";
    /** A CSV-supplied Milestone ID collides with an existing one in the same project — see LOB-2384. */
    public static final String MILESTONE_PROID_ALREADY_EXISTS = "MILESTONE_PROID_ALREADY_EXISTS";
    public static final String MILESTONE_FIELDS_REQUIRED = "MILESTONE_FIELDS_REQUIRED";
    public static final String MILESTONE_AMOUNT_INVALID = "MILESTONE_AMOUNT_INVALID";
    public static final String MILESTONE_AMOUNT_EXCEEDS_PROJECT = "MILESTONE_AMOUNT_EXCEEDS_PROJECT";
    public static final String MILESTONE_AMOUNT_BELOW_ALLOCATED = "MILESTONE_AMOUNT_BELOW_ALLOCATED";
    public static final String MILESTONE_TOTAL_EXCEEDS_PROJECT = "MILESTONE_TOTAL_EXCEEDS_PROJECT";
    public static final String MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS = "MILESTONE_NOT_ALLOWED_WITH_SUBPROJECTS";
    public static final String SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES = "SUBPROJECT_NOT_ALLOWED_WITH_MILESTONES";
    public static final String ALLOCATION_AMOUNT_INVALID = "ALLOCATION_AMOUNT_INVALID";
    public static final String ALLOCATION_AMOUNT_REQUIRED = "ALLOCATION_AMOUNT_REQUIRED";
    public static final String ALLOCATION_EXCEEDS_SPEND = "ALLOCATION_EXCEEDS_SPEND";
    public static final String MILESTONE_OVERFUNDED = "MILESTONE_OVERFUNDED";
    public static final String SPEND_NOT_FULLY_ALLOCATED = "SPEND_NOT_FULLY_ALLOCATED";
    public static final String SPEND_FIELDS_NOT_ALLOWED = "SPEND_FIELDS_NOT_ALLOWED";
    public static final String SPEND_FIELDS_REQUIRED = "SPEND_FIELDS_REQUIRED";
    public static final String FX_RATE_MISMATCH = "FX_RATE_MISMATCH";
    public static final String EVENT_CURRENCY_MISMATCH = "EVENT_CURRENCY_MISMATCH";
    public static final String EVENT_AMOUNT_INVALID = "EVENT_AMOUNT_INVALID";
    public static final String SPENDING_EVENT_NOT_FOUND = "SPENDING_EVENT_NOT_FOUND";
    public static final String SPENDING_EVENT_ALREADY_EXISTS = "SPENDING_EVENT_ALREADY_EXISTS";
    public static final String FUNDING_EVENT_FUNDING_ID_ALREADY_USED = "FUNDING_EVENT_FUNDING_ID_ALREADY_USED";
    public static final String SPENDING_EVENT_ALREADY_PUBLISHED = "SPENDING_EVENT_ALREADY_PUBLISHED";
    /** LOB-2365: publishing is refused while an event is {@code ERROR} — it no longer fits the current
     * project/milestone structure and must be corrected (which clears it back to {@code DRAFT}) first. */
    public static final String SPENDING_EVENT_HAS_ERROR = "SPENDING_EVENT_HAS_ERROR";
    public static final String CURRENCY_CHANGE_HAS_ALLOCATIONS = "CURRENCY_CHANGE_HAS_ALLOCATIONS";
    /** LOB-2365: a milestone's description/amount/date update is rejected because a published event
     * allocates to it — distinct from {@link #SPENDING_EVENT_ALREADY_PUBLISHED}'s wholesale block, since
     * milestoneTitle stays editable on the very same request. */
    public static final String MILESTONE_LOCKED = "MILESTONE_LOCKED";
    public static final String EVENT_TYPE_IMMUTABLE = "EVENT_TYPE_IMMUTABLE";
    public static final String FUNDING_ENTITY_REQUIRED = "FUNDING_ENTITY_REQUIRED";
    public static final String EVENT_DATE_IN_FUTURE = "EVENT_DATE_IN_FUTURE";
    public static final String EVENT_DATE_REQUIRED = "EVENT_DATE_REQUIRED";
    public static final String AMOUNT_FCY_INVALID = "AMOUNT_FCY_INVALID";
    public static final String FX_RATE_INVALID = "FX_RATE_INVALID";
    public static final String ORGANISATION_NOT_FOUND = "ORGANISATION_NOT_FOUND";
    public static final String ORGANISATION_MISMATCH = "ORGANISATION_MISMATCH";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";

    // --- Bulk CSV import ---
    public static final String UNRECOGNIZED_CSV_FILE_TYPE = "UNRECOGNIZED_CSV_FILE_TYPE";
    public static final String AMBIGUOUS_PROJECT_REFERENCE = "AMBIGUOUS_PROJECT_REFERENCE";
    public static final String PROJECT_REFERENCE_NOT_FOUND = "PROJECT_REFERENCE_NOT_FOUND";
    public static final String SUBPROJECT_REFERENCE_NOT_FOUND = "SUBPROJECT_REFERENCE_NOT_FOUND";
    public static final String CSV_ROW_INVALID = "CSV_ROW_INVALID";
    public static final String NO_FILES_UPLOADED = "NO_FILES_UPLOADED";
    public static final String PROJECT_NOT_CREATED_NO_SUBPROJECT = "PROJECT_NOT_CREATED_NO_SUBPROJECT";
    public static final String DUPLICATE_MILESTONE_ALLOCATION = "DUPLICATE_MILESTONE_ALLOCATION";
    public static final String SUBPROJECT_TITLE_REQUIRED = "SUBPROJECT_TITLE_REQUIRED";

}
