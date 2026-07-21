package org.cardanofoundation.lob.app.keri_attestation.service;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.keri_attestation.domain.core.ConsumedAttestation;

/**
 * The only surface a target-owning module (e.g. document_vault) is allowed to depend on: exchanging
 * a completed ceremony for the attestation it produced. Deliberately narrow — everything else about
 * ceremony orchestration ({@link CeremonyService}'s full API) is an implementation detail of this
 * module (design §4.6).
 */
public interface AttestationConsumptionApi {

    Either<ProblemDetail, ConsumedAttestation> validateAndConsume(
            String ceremonyId, String targetType, String targetId, String userId);

}
