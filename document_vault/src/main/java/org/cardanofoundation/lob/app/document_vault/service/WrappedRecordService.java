package org.cardanofoundation.lob.app.document_vault.service;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vavr.control.Either;

import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordEntity;
import org.cardanofoundation.lob.app.document_vault.domain.entity.WrappedRecordId;
import org.cardanofoundation.lob.app.document_vault.domain.request.UpsertWrappedRecordRequest;
import org.cardanofoundation.lob.app.document_vault.domain.view.PagedResponse;
import org.cardanofoundation.lob.app.document_vault.domain.view.WrappedRecordView;
import org.cardanofoundation.lob.app.document_vault.repository.WrappedRecordRepository;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Blueprint B2: opaque wrapped-record store keyed by (accountId, credentialId). Blobs are stored
 * and returned verbatim — the server must never parse, normalise or transform them.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WrappedRecordService {

    private final WrappedRecordRepository recordRepository;
    private final KeycloakSecurityHelper securityHelper;

    @Value("${lob.document_vault.max-record-bytes:8192}")
    private long maxRecordBytes;

    public Either<ProblemDetail, WrappedRecordView> upsert(String credentialId, UpsertWrappedRecordRequest request) {
        if (request.getRecord().getBytes(StandardCharsets.UTF_8).length > maxRecordBytes) {
            return Either.left(VaultProblems.payloadTooLarge(
                    "Wrapped record exceeds the maximum of %d bytes.".formatted(maxRecordBytes)));
        }
        WrappedRecordEntity entity = recordRepository
                .findById(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId))
                .orElseGet(() -> {
                    WrappedRecordEntity fresh = new WrappedRecordEntity();
                    fresh.setId(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId));
                    return fresh;
                });
        entity.setRecord(request.getRecord());
        entity.setVersion(request.getVersion());
        return Either.right(toView(recordRepository.save(entity)));
    }

    @Transactional(readOnly = true)
    public Either<ProblemDetail, WrappedRecordView> get(String credentialId) {
        return recordRepository.findById(new WrappedRecordId(securityHelper.getCurrentUserId(), credentialId))
                .<Either<ProblemDetail, WrappedRecordView>>map(entity -> Either.right(toView(entity)))
                .orElseGet(() -> Either.left(VaultProblems.notFound(VaultProblems.RECORD_NOT_FOUND,
                        "No wrapped record for credential %s on the current account.".formatted(credentialId))));
    }

    @Transactional(readOnly = true)
    public PagedResponse<WrappedRecordView> listMine(Pageable pageable) {
        return PagedResponse.of(recordRepository.findByIdAccountId(securityHelper.getCurrentUserId(), pageable),
                this::toView);
    }

    private WrappedRecordView toView(WrappedRecordEntity entity) {
        return new WrappedRecordView(entity.getId().getCredentialId(), entity.getRecord(),
                entity.getVersion(), entity.getUpdatedAt());
    }
}
