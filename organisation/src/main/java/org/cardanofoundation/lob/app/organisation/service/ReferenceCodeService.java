package org.cardanofoundation.lob.app.organisation.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;
import org.zalando.problem.Status;

import org.cardanofoundation.lob.app.organisation.domain.entity.Organisation;
import org.cardanofoundation.lob.app.organisation.domain.entity.ReferenceCode;
import org.cardanofoundation.lob.app.organisation.domain.request.ReferenceCodeUpdate;
import org.cardanofoundation.lob.app.organisation.domain.view.ReferenceCodeView;
import org.cardanofoundation.lob.app.organisation.repository.ReferenceCodeRepository;
import org.cardanofoundation.lob.app.organisation.service.csv.CsvParser;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReferenceCodeService {

    private final ReferenceCodeRepository referenceCodeRepository;
    private final OrganisationService organisationService;
    private final CsvParser<ReferenceCodeUpdate> csvParser;
    private final AccountEventService accountEventService;

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
    public ReferenceCodeView insertReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate, boolean isUpsert) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }
        if (referenceCodeUpdate.getParentReferenceCode() != null && !referenceCodeUpdate.getParentReferenceCode().isEmpty()) {
            Optional<ReferenceCode> parentReferenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getParentReferenceCode());
            if (parentReferenceCode.isEmpty()) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle("PARENT_REFERENCE_CODE_NOT_FOUND")
                        .withDetail("Unable to find parent reference Id: %s".formatted(referenceCodeUpdate.getParentReferenceCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(),
                        referenceCodeUpdate);
            }
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());
        ReferenceCode referenceCode = ReferenceCode.builder()
                .id(new ReferenceCode.Id(orgId, referenceCodeUpdate.getReferenceCode()))
                .build();
        if(referenceCodeOpt.isPresent()){
            if(isUpsert) {
                referenceCode = referenceCodeOpt.get();
            } else {
                return ReferenceCodeView.createFail(Problem.builder()
                                .withTitle("REFERENCE_CODE_ALREADY_EXIST")
                                .withDetail("The reference code with code :%s already exists".formatted(referenceCodeUpdate.getReferenceCode()))
                                .withStatus(Status.NOT_FOUND)
                                .build(),
                        referenceCodeUpdate);
            }
        }


        referenceCode.setName(referenceCodeUpdate.getName());
        referenceCode.setParentReferenceCode(referenceCodeUpdate.getParentReferenceCode() == null || referenceCodeUpdate.getParentReferenceCode().isEmpty() ? null : referenceCodeUpdate.getParentReferenceCode());

        referenceCode.setActive(referenceCodeUpdate.isActive());
        // The reference code returning is not the latest version after save
        return ReferenceCodeView.fromEntity(referenceCodeRepository.save(referenceCode));
    }

    @Transactional
    public ReferenceCodeView updateReferenceCode(String orgId, ReferenceCodeUpdate referenceCodeUpdate) {

        Optional<Organisation> organisationChe = organisationService.findById(orgId);
        if (organisationChe.isEmpty()) {
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle("ORGANISATION_NOT_FOUND")
                    .withDetail("Unable to find Organisation by Id: %s".formatted(orgId))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }
        if (referenceCodeUpdate.getParentReferenceCode() != null && !referenceCodeUpdate.getParentReferenceCode().isEmpty()) {
            Optional<ReferenceCode>  parentReferenceCode = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getParentReferenceCode());
            if (parentReferenceCode.isEmpty()) {
                return ReferenceCodeView.createFail(Problem.builder()
                        .withTitle("PARENT_REFERENCE_CODE_NOT_FOUND")
                        .withDetail("Unable to find parent reference Id: %s".formatted(referenceCodeUpdate.getParentReferenceCode()))
                        .withStatus(Status.NOT_FOUND)
                        .build(),
                        referenceCodeUpdate);
            }
        }

        Optional<ReferenceCode> referenceCodeOpt = referenceCodeRepository.findByOrgIdAndReferenceCode(orgId, referenceCodeUpdate.getReferenceCode());

        if(referenceCodeOpt.isEmpty()){
            return ReferenceCodeView.createFail(Problem.builder()
                    .withTitle("REFERENCE_CODE_NOT_FOUND")
                    .withDetail("Unable to find reference Id: %s".formatted(referenceCodeUpdate.getReferenceCode()))
                    .withStatus(Status.NOT_FOUND)
                    .build(),
                    referenceCodeUpdate);
        }

        ReferenceCode referenceCode = referenceCodeOpt.get();
        referenceCode.setName(referenceCodeUpdate.getName());
        referenceCode.setParentReferenceCode(referenceCodeUpdate.getParentReferenceCode() == null || referenceCodeUpdate.getParentReferenceCode().isEmpty() ? null : referenceCodeUpdate.getParentReferenceCode());

        referenceCode.setActive(referenceCodeUpdate.isActive());
        // The reference code returning is not the latest version after save
        referenceCode = referenceCodeRepository.save(referenceCode);
        accountEventService.updateStatus(orgId, referenceCode.getId().getReferenceCode());
        referenceCodeRepository.findChildrenByOrgIdAndReferenceCode(referenceCode.getId().getOrganisationId(), referenceCode.getId().getReferenceCode()).forEach(childReferenceCode -> {
            accountEventService.updateStatus(orgId, childReferenceCode.getId().getReferenceCode());
        });
        return ReferenceCodeView.fromEntity(referenceCode);
    }

    @Transactional
    public Either<Set<Problem>, Set<ReferenceCodeView>> insertReferenceCodeByCsv(String orgId, MultipartFile file) {
        return csvParser.parseCsv(file, ReferenceCodeUpdate.class).fold(
                problem -> Either.left(Set.of(problem)),
                referenceCodeUpdates -> Either.right(referenceCodeUpdates.stream().map(
                        refCodeUpdate -> insertReferenceCode(orgId, refCodeUpdate, true)).collect(Collectors.toSet()))
        );
    }

}
