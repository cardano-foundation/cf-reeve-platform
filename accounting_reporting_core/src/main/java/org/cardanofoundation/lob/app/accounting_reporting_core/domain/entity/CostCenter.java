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
@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
@Audited
public class CostCenter {

    @NotBlank
    @LOBVersionSourceRelevant
    private String customerCode;

    @Nullable
    private String externalCustomerCode;

    @Nullable
    private String name;

    public Optional<String> getName() {
        return Optional.ofNullable(name);
    }

    public Optional<String> getExternalCustomerCode() {
        return Optional.ofNullable(externalCustomerCode);
    }

}
