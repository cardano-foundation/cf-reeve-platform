package org.cardanofoundation.lob.app.organisation.domain.csv;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;
import java.util.Optional;

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
    private String balanceFCY;

    @CsvBindByName(column = "Open Balance LCY")
    private String balanceLCY;

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

    public void fillOpeningBalance() throws IllegalArgumentException, ParseException{
        NumberFormat format = NumberFormat.getInstance(Locale.US);
        this.setOpeningBalance(new OpeningBalance(
                BigDecimal.valueOf(format.parse(this.balanceFCY).doubleValue()),
                BigDecimal.valueOf(format.parse(this.balanceLCY).doubleValue()),
                this.openBalanceCurrencyFcy,
                this.openBalanceCurrencyLcy,
                this.balanceType,
                Optional.ofNullable(this.date).map(d -> FlexibleDateParser.parse(this.date)).orElse(null)
        ));
    }
}
