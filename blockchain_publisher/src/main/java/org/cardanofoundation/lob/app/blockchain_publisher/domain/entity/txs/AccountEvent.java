package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import java.util.Objects;

import jakarta.persistence.Embeddable;

import lombok.*;

@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Setter
public class AccountEvent {

    private String code;

    private String name;

    @Override
    public int hashCode() {
        return Objects.hash(code, name);
    }

}
