package org.cardanofoundation.lob.app.accounting_reporting_core.domain.core;

import java.util.Optional;

import jakarta.validation.constraints.Pattern;

import lombok.*;

@AllArgsConstructor
@Getter
@Builder
@EqualsAndHashCode
@ToString
@NoArgsConstructor
public class CoreCurrency {

    private IsoStandard currencyISOStandard;

    @Pattern(regexp = "^[A-Z]{3,6}$")
    private String currencyISOCode;

    @Builder.Default
    private Optional<String> isoUniqueId = Optional.empty();

    private String name;

    public static CoreCurrency fromId(String id, String name) {
        if (id.split(":").length == 3) {
            return CoreCurrency.builder()
                    .currencyISOStandard(IsoStandard.valueOf(id.split(":")[0]))
                    .currencyISOCode(id.split(":")[1])
                    .isoUniqueId(Optional.of(id.split(":")[2]))
                    .name(name)
                    .build();
        }

        return CoreCurrency.builder()
                .currencyISOStandard(IsoStandard.valueOf(id.split(":")[0]))
                .currencyISOCode(id.split(":")[1])
                .name(name)
                .build();
    }

    public String toExternalId() {
        if (isoUniqueId.isEmpty()) {
            return "%s:%s".formatted(currencyISOStandard, currencyISOCode);
        }

        return "%s:%s:%s".formatted(
                currencyISOStandard, currencyISOCode, isoUniqueId.orElseThrow()
        );
    }

    public enum IsoStandard {
        ISO_4217,
        ISO_24165
    }

}
