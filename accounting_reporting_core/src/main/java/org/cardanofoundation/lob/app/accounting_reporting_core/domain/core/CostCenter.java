package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.util.Optional;

import jakarta.validation.constraints.Size;

import lombok.*;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.annotations.LOBVersionSourceRelevant;

@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class CostCenter {

    @LOBVersionSourceRelevant
    private @Size(min = 1, max =  255) String customerCode;

    @Builder.Default
    private Optional<@Size(min = 1, max =  255) String> externalCustomerCode = Optional.empty();

    @Builder.Default
    private Optional<@Size(min = 1, max =  255) String> name = Optional.empty();

}
