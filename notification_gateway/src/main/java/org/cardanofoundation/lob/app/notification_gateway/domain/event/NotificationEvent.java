//package org.cardanofoundation.lob.app.notification_gateway.domain.event;
//
//import org.cardanofoundation.lob.app.notification_gateway.domain.core.NotificationSeverity;
//import org.springframework.http.ProblemDetail;
//import org.jmolecules.event.annotation.DomainEvent;
//
//import java.util.Map;
//import java.util.Objects;
//import java.util.UUID;
//
///**
// * Event responsible for notifying the LOB user of a problem
// *
// * @param id
// * @param severity
// * @param message
// */
/////@Externalized("target")
//@DomainEvent
//// TODO add applicationId, organisationId and creationDate, expirationDate, acked notification?
//public record NotificationEvent(UUID id,
//                                NotificationSeverity severity,
//                                String code,
//                                String message,
//                                Map<String, Object> bag
//) {
//
//    public static NotificationEvent create(NotificationSeverity severity,
//                                           ProblemDetail problem  {
//        return new NotificationEvent(
//                UUID.randomUUID(),
//                severity,
//                Objects.requireNonNull(problem.getTitle()),
//                Objects.requireNonNull(problem.getDetail()),
//                problem.getParameters()
//        );
//    }
//
//}
