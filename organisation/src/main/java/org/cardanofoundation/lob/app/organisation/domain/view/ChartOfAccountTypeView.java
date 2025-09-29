package org.cardanofoundation.lob.app.organisation.domain.view;

import java.util.LinkedHashSet;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;



@Getter
@Builder
@AllArgsConstructor
public class ChartOfAccountTypeView {

    private Long id;

    private String organisationId;

    private String name;

    @Builder.Default
    private Set<ChartOfAccountSubTypeView> subType = new LinkedHashSet<>();

}
