package org.cardanofoundation.lob.app.organisation.domain.csv;

import java.math.BigDecimal;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opencsv.bean.CsvBindByName;

import org.cardanofoundation.lob.app.organisation.domain.core.OperationType;
import org.cardanofoundation.lob.app.organisation.domain.entity.OpeningBalance;
import org.cardanofoundation.lob.app.organisation.domain.request.ChartOfAccountUpdate;
import org.cardanofoundation.lob.app.support.date.FlexibleDateParser;

/**
 * We need a flat map for the CSV file, so we need to create a new class
 */
public class ChartOfAccountUpdateCsv extends ChartOfAccountUpdate {

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
    private String date;

    public void fillOpeningBalance() throws IllegalArgumentException{
        this.setOpeningBalance(new OpeningBalance(
                this.balanceFCY,
                this.balanceLCY,
                this.originalCurrencyIdFCY,
                this.originalCurrencyIdLCY,
                this.balanceType,
                FlexibleDateParser.parse(this.date)
        ));
    }
}
