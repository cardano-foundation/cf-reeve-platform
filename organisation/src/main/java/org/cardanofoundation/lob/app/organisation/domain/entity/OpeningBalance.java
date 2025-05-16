package org.cardanofoundation.lob.app.organisation.domain.entity;



import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import lombok.*;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opencsv.bean.CsvBindByName;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;


@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder(toBuilder = true)
@EqualsAndHashCode
public class OpeningBalance {

    @CsvBindByName(column = "Balance FCY")
    private BigDecimal balanceFCY;

    @CsvBindByName(column = "Balance LCY")
    private BigDecimal balanceLCY;

    @CsvBindByName(column = "Original Currency ID FCY")
    private String originalCurrencyIdFCY;

    @CsvBindByName(column = "Original Currency ID LCY")
    private String originalCurrencyIdLCY;

    @Enumerated(EnumType.STRING)
    @CsvBindByName(column = "Balance Type")
    private OperationType balanceType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @CsvBindByName(column = "Balance Date")
    private LocalDate date;
}
