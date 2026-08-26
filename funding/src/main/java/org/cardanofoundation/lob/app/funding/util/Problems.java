package org.cardanofoundation.lob.app.funding.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Factory for the {@link ProblemDetail}s the funding services attach to their view responses. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String detail, String title) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    public static ProblemDetail badRequest(String detail, String title) {
        return of(HttpStatus.BAD_REQUEST, detail, title);
    }

    public static ProblemDetail notFound(String detail, String title) {
        return of(HttpStatus.NOT_FOUND, detail, title);
    }

    public static ProblemDetail conflict(String detail, String title) {
        return of(HttpStatus.CONFLICT, detail, title);
    }

    public static ProblemDetail unauthorized() {
        return of(HttpStatus.UNAUTHORIZED, "User does not have access to this organisation", ErrorTitleConstants.UNAUTHORIZED);
    }

    // --- Shared not-found factories, so every service reports the same title and message shape ---

    public static ProblemDetail projectNotFound(String projectId) {
        return notFound("Project not found: %s".formatted(projectId), ErrorTitleConstants.PROJECT_NOT_FOUND);
    }

    public static ProblemDetail milestoneNotFound(String milestoneId) {
        return notFound("Milestone not found: %s".formatted(milestoneId), ErrorTitleConstants.MILESTONE_NOT_FOUND);
    }

    public static ProblemDetail eventNotFound(String eventId) {
        return notFound("Event not found: %s".formatted(eventId), ErrorTitleConstants.SPENDING_EVENT_NOT_FOUND);
    }

    public static ProblemDetail organisationNotFound(String organisationId) {
        return badRequest("Organisation with id: %s not found".formatted(organisationId),
                ErrorTitleConstants.ORGANISATION_NOT_FOUND);
    }

    public static ProblemDetail projectReferenceNotFound(String projectTitle) {
        return notFound("Project not found for projectTitle: %s".formatted(projectTitle),
                ErrorTitleConstants.PROJECT_REFERENCE_NOT_FOUND);
    }

    public static ProblemDetail subProjectReferenceNotFound(String projectTitle, String subProjectTitle) {
        return notFound("Sub-project not found for Sub Project Title: %s under Project Title (root): %s"
                        .formatted(subProjectTitle, projectTitle),
                ErrorTitleConstants.SUBPROJECT_REFERENCE_NOT_FOUND);
    }

    public static ProblemDetail ambiguousProjectReference(String projectTitle) {
        return badRequest(
                "projectTitle %s matches more than one project in this organisation; set Sub Project Title to name a specific one"
                        .formatted(projectTitle),
                ErrorTitleConstants.AMBIGUOUS_PROJECT_REFERENCE);
    }

    public static ProblemDetail fundingEventIdAlreadyUsed(String fundingId) {
        return conflict(
                "fundingId %s is already used by another FUNDING event".formatted(fundingId),
                ErrorTitleConstants.FUNDING_EVENT_FUNDING_ID_ALREADY_USED);
    }
}
