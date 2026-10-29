package org.cardanofoundation.lob.app.document_vault.domain.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.cardanofoundation.lob.app.document_vault.domain.card.KeyCardDto;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

/** Extends BaseRequest so OrganisationCheckInterceptor guards the organisationId as well. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportCardRequest extends BaseRequest {

    @NotNull
    @Valid
    private KeyCardDto card;
}
