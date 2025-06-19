package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import java.math.BigDecimal;
import java.util.Objects;

import jakarta.persistence.Embeddable;

import lombok.*;

@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Setter
public class Vat {

    private String customerCode;
    private BigDecimal rate;

    @Override
    public int hashCode() {
        return Objects.hash(customerCode, rate);
    }

}
