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

    @CsvBindByName(column = "Open Balance FCY")
    private BigDecimal balanceFCY;

    @CsvBindByName(column = "Open Balance LCY")
    private BigDecimal balanceLCY;

    @CsvBindByName(column = "Open Balance Currency ID FCY")
    private String openBalanceCurrencyFcy;

    @CsvBindByName(column = "Open Balance Currency ID LCY")
    private String openBalanceCurrencyLcy;

    @Enumerated(EnumType.STRING)
    @CsvBindByName(column = "Open Balance Type")
    private OperationType balanceType;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @CsvBindByName(column = "Open Balance Date")
    private String date;

    public void fillOpeningBalance() throws IllegalArgumentException{
        this.setOpeningBalance(new OpeningBalance(
                this.balanceFCY,
                this.balanceLCY,
                this.openBalanceCurrencyFcy,
                this.openBalanceCurrencyLcy,
                this.balanceType,
                FlexibleDateParser.parse(this.date)
        ));
    }
}
