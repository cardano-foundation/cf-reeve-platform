package org.cardanofoundation.lob.app.funding.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;

import com.opencsv.CSVWriter;

import org.cardanofoundation.lob.app.funding.domain.entity.MilestoneEntity;
import org.cardanofoundation.lob.app.funding.domain.entity.ProjectEntity;
import org.cardanofoundation.lob.app.funding.repository.FundingProjectRepository;
import org.cardanofoundation.lob.app.funding.util.Problems;
import org.cardanofoundation.lob.app.organisation.OrganisationPublicApiIF;
import org.cardanofoundation.lob.app.support.security.KeycloakSecurityHelper;

/**
 * Exports an organisation's current Projects+Milestones tree as a CSV in exactly the shape
 * {@link FundingCsvTemplateService} hands out blank (same header, same column order — reused from
 * there rather than duplicated here, see its Javadoc) — but populated with every row's real data,
 * including the {@code proId} the backend assigned it (see LOB-2384). This is what makes an
 * auto-assigned sub-project/milestone {@code proId} discoverable after the fact, so bulk-import
 * creation doesn't need to force the user to invent one at upload time (see
 * {@link FundingBulkImportService#upsertSubProject}).
 *
 * <p>The exported file is itself a valid re-upload: every row's IDs are populated, so a
 * re-import matches every existing row by {@code proId} instead of by title.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingCsvExportService {

    private final FundingProjectRepository projectRepository;
    private final MilestoneService milestoneService;
    private final KeycloakSecurityHelper keycloakSecurityHelper;
    private final OrganisationPublicApiIF organisationPublicApi;

    /**
     * Checked eagerly, before the controller commits to a 200 response and starts streaming — a
     * {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody} can no
     * longer change the HTTP status once Spring starts invoking it, so authorization/existence must
     * be validated up front, the same as every other org-scoped read in this module.
     */
    public Optional<ProblemDetail> validateExport(String organisationId) {
        if (!keycloakSecurityHelper.canUserAccessOrg(organisationId)) {
            return Optional.of(Problems.unauthorized());
        }
        if (organisationPublicApi.findByOrganisationId(organisationId).isEmpty()) {
            return Optional.of(Problems.organisationNotFound(organisationId));
        }
        return Optional.empty();
    }

    /**
     * Writes the export. Callers must have already checked {@link #validateExport} — this method
     * assumes it passed. {@code proIds}, when non-empty, restricts the export to those root
     * projects (and their full descendant tree) instead of every root project in the organisation —
     * same "organisationId always, proIds narrows it" filter shape as the rest of this module's
     * list endpoints (e.g. {@code ProjectController#listProjects}'s organisationId+Pageable), which
     * this mirrors: {@code pageable} paginates the *root* projects returned, each expanded in full.
     */
    public void writeProjectsMilestonesExport(String organisationId, @Nullable List<String> proIds,
            Pageable pageable, OutputStream outputStream) {
        try (Writer writer = new OutputStreamWriter(outputStream)) {
            CSVWriter csvWriter = new CSVWriter(writer);
            csvWriter.writeNext(FundingCsvTemplateService.PROJECTS_MILESTONES_HEADER, false);

            Page<ProjectEntity> roots = (proIds == null || proIds.isEmpty())
                    ? projectRepository.findByOrganisationIdAndParentProjectIsNull(organisationId, pageable)
                    : projectRepository.findByOrganisationIdAndProIdInAndParentProjectIsNull(organisationId, proIds, pageable);
            for (ProjectEntity root : roots) {
                writeProjectRows(csvWriter, root);
            }
            csvWriter.flush();
        } catch (IOException e) {
            log.error("Failed to write Projects+Milestones CSV export for organisation: {}", organisationId, e);
        }
    }

    /** Writes one row per leaf under {@code root} — see the class Javadoc for the row shape this produces. */
    private void writeProjectRows(CSVWriter csvWriter, ProjectEntity root) {
        List<ProjectEntity> subProjects = projectRepository.findByParentProjectId(root.getId());
        if (subProjects.isEmpty()) {
            writeLeafRows(csvWriter, root, null, milestoneService.findByProjectId(root.getId()));
            return;
        }
        for (ProjectEntity sub : subProjects) {
            writeLeafRows(csvWriter, root, sub, milestoneService.findByProjectId(sub.getId()));
        }
    }

    /** A project (root or sub-) with no milestones yet still gets one row, so it isn't dropped from the export. */
    private void writeLeafRows(CSVWriter csvWriter, ProjectEntity root, @Nullable ProjectEntity sub, List<MilestoneEntity> milestones) {
        if (milestones.isEmpty()) {
            csvWriter.writeNext(row(root, sub, null), false);
            return;
        }
        for (MilestoneEntity milestone : milestones) {
            csvWriter.writeNext(row(root, sub, milestone), false);
        }
    }

    private static String[] row(ProjectEntity root, @Nullable ProjectEntity sub, @Nullable MilestoneEntity milestone) {
        return new String[]{
                root.getProjectTitle(), root.getProId(), str(root.getTotalAmount()), nullToEmpty(root.getCurrency()),
                sub != null ? sub.getProjectTitle() : "",
                sub != null ? sub.getProId() : "",
                sub != null ? str(sub.getTotalAmount()) : "",
                milestone != null ? milestone.getMilestoneTitle() : "",
                milestone != null ? milestone.getProId() : "",
                milestone != null ? str(milestone.getMilestoneAmount()) : "",
                milestone != null && milestone.getMilestoneDate() != null ? milestone.getMilestoneDate().toString() : ""
        };
    }

    private static String str(@Nullable BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

}
