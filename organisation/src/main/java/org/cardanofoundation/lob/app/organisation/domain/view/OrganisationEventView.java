package org.cardanofoundation.lob.app.organisation.domain.view;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;



@Getter
@Builder
@AllArgsConstructor
public class OrganisationEventView {

    private String id;

    private String organisationId;

    private String name;

}
