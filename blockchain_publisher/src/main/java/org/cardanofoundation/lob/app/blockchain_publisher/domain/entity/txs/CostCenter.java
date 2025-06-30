package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import java.util.Objects;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;

import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@ToString
@Builder
@AllArgsConstructor
public class CostCenter {

    @NotBlank
    private String customerCode;

    @NotBlank
    private String name;

    @Override
    public int hashCode() {
        return Objects.hash(customerCode, name);
    }

}
