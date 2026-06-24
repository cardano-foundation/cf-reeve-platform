package org.cardanofoundation.lob.app.blockchain_common.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A receipt for a blockchain submission (e.g. {@code CARDANO_L1} + tx hash). Shared across modules via
 * {@code blockchain_common} so ledger-update events do not need module-specific receipt types.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class BlockchainReceipt {

    private String type;

    private String hash;

}
