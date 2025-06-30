package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import java.util.Objects;

import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class Currency {

    private String customerCode;

    private String id;

    @Override
    public int hashCode() {
        return Objects.hash(customerCode, id);
    }

}
