package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import jakarta.persistence.Embeddable;


@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Setter
public class AccountEvent {

    private String code;

    private String name;

}
