package org.cardanofoundation.lob.app.organisation.domain.entity;


import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;

import lombok.*;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;


@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode
public class OpeningBalance {
    private BigDecimal balance;

    private String originalCurrencyId;

    private OperationType balanceType;

    private LocalDate date;
}
