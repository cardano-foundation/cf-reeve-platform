package org.cardano.foundation.lob.domain.view;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.cardano.foundation.lob.domain.CardanoNetwork;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response body for the address-balances endpoint: a map of monitored address -> current
 * UTXO balance in lovelace, together with the network the balances were computed on.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class AddressBalancesResponse {

    private Map<String, Long> balances = new LinkedHashMap<>();

    private CardanoNetwork network;
}
