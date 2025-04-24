package org.cardanofoundation.lob.app.organisation.domain.entity;



import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;


@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode
public class OpeningBalance {
    private BigDecimal balanceFCY;

    private BigDecimal balanceLCY;

    private String originalCurrencyIdFCY;

    private String originalCurrencyIdLCY;

    @Enumerated(EnumType.STRING)
    private OperationType balanceType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate date;
}
