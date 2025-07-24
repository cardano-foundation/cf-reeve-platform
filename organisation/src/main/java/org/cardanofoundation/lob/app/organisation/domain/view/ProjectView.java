package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.organisation.domain.csv.ProjectUpdate;
import org.cardanofoundation.lob.app.organisation.domain.entity.Project;

@Getter
@Builder
@AllArgsConstructor
public class ProjectView {

    private String customerCode;

    private String externalCustomerCode;

    private String name;

    private ProjectView parent;

    private String parentCustomerCode;

    private Problem error;

    public static ProjectView fromEntity(Project project) {
        ProjectView.ProjectViewBuilder builder = ProjectView.builder()
                .customerCode(project.getId() == null ? null : project.getId().getCustomerCode())
                .externalCustomerCode(project.getExternalCustomerCode())
                .name(project.getName());
        if (project.getParent().isPresent()) {
            builder.parent(ProjectView.fromEntity(project.getParent().get()));
        }
        return builder.build();
    }

    public static ProjectView createFail(ProjectUpdate projectUpdate, Problem error) {
        return ProjectView.builder()
                .customerCode(projectUpdate.getCustomerCode())
                .externalCustomerCode(projectUpdate.getExternalCustomerCode())
                .name(projectUpdate.getName())
                .parentCustomerCode(projectUpdate.getParentCustomerCode())
                .error(error)
                .build();
    }
}
