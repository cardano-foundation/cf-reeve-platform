package org.cardanofoundation.lob.app.organisation.domain.entity;



import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotNull;

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
    @NotNull(message = "Balance FCY is required")
    private BigDecimal balanceFCY;

    @CsvBindByName(column = "Balance LCY")
    @NotNull(message = "Balance LCY is required")
    private BigDecimal balanceLCY;

    @CsvBindByName(column = "Original Currency ID FCY")
    @NotNull(message = "Original Currency ID FCY is required")
    private String originalCurrencyIdFCY;

    @CsvBindByName(column = "Original Currency ID LCY")
    @NotNull(message = "Original Currency ID LCY is required")
    private String originalCurrencyIdLCY;

    @Enumerated(EnumType.STRING)
    @CsvBindByName(column = "Balance Type")
    @NotNull(message = "Balance Type is required")
    private OperationType balanceType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @CsvBindByName(column = "Balance Date")
    @NotNull(message = "Balance Date is required")
    private LocalDate date;

    public boolean allNull() {
        return balanceFCY == null && balanceLCY == null && originalCurrencyIdFCY == null
                && originalCurrencyIdLCY == null && balanceType == null && date == null;
    }
}
