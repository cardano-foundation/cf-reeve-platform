package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.util.Optional;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;

import javax.annotation.Nullable;

import lombok.*;

import org.hibernate.envers.Audited;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;

@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@Audited
public class Project {

    @NotBlank
    @LOBVersionSourceRelevant

    private String customerCode;

    @Nullable
    private String name;

    @Nullable
    private String externalCustomerCode;

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public Optional<String> getExternalCustomerCode() {
        return Optional.ofNullable(externalCustomerCode);
    }

}
