package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceCodeService {

    private final ReferenceCodeRepository referenceCodeRepository;
    private final OrganisationService organisationService;

    public List<ReferenceCodeView> getAllReferenceCodes(String orgId) {
        return referenceCodeRepository.findAllByOrgId(orgId).stream()
                .map(ReferenceCodeView::fromEntity)
                .toList();
    }

    public Optional<ReferenceCodeView> getReferenceCode(String orgId, String referenceCode) {
        return referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCode)
                .map(ReferenceCodeView::fromEntity);
    }

    @Transactional
    public ReferenceCodeView upsertReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail(STR."Unable to find Organisation by Id: \{orgId}")
                    .withStatus(Status.NOT_FOUND)
                    .build());
        }

        ReferenceCode referenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode()).orElse(
                ReferenceCode.builder()
                        .id(new ReferenceCode.Id(orgId, referenceCodeUpdate.getReferenceCode()))
                        .build()
        );

        referenceCode.setName(referenceCodeUpdate.getName());
        ReferenceCode referenceCodeS = referenceCodeRepository.save(referenceCode);

        return ReferenceCodeView.fromEntity(referenceCodeS);
    }
}
