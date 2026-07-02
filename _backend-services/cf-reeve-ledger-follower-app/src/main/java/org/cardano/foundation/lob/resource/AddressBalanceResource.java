package org.cardano.foundation.lob.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardano.foundation.lob.domain.CardanoNetwork;
import org.cardano.foundation.lob.domain.view.AddressBalancesResponse;
import org.cardano.foundation.lob.service.AddressBalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class AddressBalanceResource {

    private final AddressBalanceService addressBalanceService;
    private final CardanoNetwork network;

    @Tag(name = "AddressBalances", description = "Address Balances API")
    @Operation(description = "Returns all monitored addresses and their current UTXO balances (lovelace), "
            + "computed from the address_utxo table populated by the yaci-store utxo indexer.",
            responses = {
                    @ApiResponse(content =
                            @Content(mediaType = APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = AddressBalancesResponse.class)))
            })
    @GetMapping(value = "/address-balances", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<AddressBalancesResponse> getAddressBalances() {
        var balances = addressBalanceService.getAllAddressBalances();
        return ResponseEntity.ok(new AddressBalancesResponse(balances, network));
    }
}
