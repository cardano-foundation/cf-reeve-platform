package org.cardano.foundation.lob.resource;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.cardano.foundation.lob.domain.CardanoNetwork;
import org.cardano.foundation.lob.domain.ChainTip;
import org.cardano.foundation.lob.domain.OnChainTxDetails;
import org.cardano.foundation.lob.domain.view.LOBOnChainTxStatusRequest;
import org.cardano.foundation.lob.domain.view.LOBOnChainTxStatusResponse;
import org.cardano.foundation.lob.service.BlockchainDataChainTipService;
import org.cardano.foundation.lob.service.BlockchainDataTransactionDetailsService;
import org.cardano.foundation.lob.service.TransactionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.zalando.problem.Problem;

import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@RestController
@CrossOrigin(origins = "http://localhost:3000")
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class LedgerFollowerResource {

    private final BlockchainDataChainTipService blockchainDataChainTipService;
    private final BlockchainDataTransactionDetailsService blockchainDataTransactionDetailsService;
    private final TransactionService transactionService;
    private final CardanoNetwork network;

    @Tag(name = "ChainTip", description = "ChainTip API")
    @Operation(description = "Transaction list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = ChainTip.class)))}
            )
    })
    @GetMapping(value = "/tip", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getChainTip() {
        val chainTipE = blockchainDataChainTipService.getChainTip();

        if  (chainTipE.isLeft()) {
            log.error("Error getting chain tip, issue:{}", chainTipE.getLeft());

            return ResponseEntity.internalServerError().build();
        }

        val chainTip = chainTipE.get();

        return ResponseEntity.ok()
                .body(chainTip);
    }

    @Tag(name = "TxDetails", description = "TxDetails API")
    @Operation(description = "Transaction list", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = OnChainTxDetails.class)))}
            )
    })
    @GetMapping(value = "/tx-details/{txHash}", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getTxDetails(@Valid @PathVariable("txHash") @Parameter(example = "7e9e8bcbb38a283b41eab57add98278561ab51d23a16f3e3baf3daa461b84ab4") String txHash) {
        val txDetailsE = blockchainDataTransactionDetailsService.getTransactionDetails(txHash);

        val txDetailsM = txDetailsE.get();

        if (txDetailsE.isLeft()) {
            log.error("Error getting tx details, issue :{}", txDetailsE.getLeft());

            return ResponseEntity.internalServerError().build();
        }

        if (txDetailsM.isEmpty()) {
            val problem = Problem.builder()
                    .withTitle("TX_NOT_FOUND")
                    .withDetail("Transaction not found, with txHash: " + txHash)
                    .build();

            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
        }

        return ResponseEntity.ok()
                .body(txDetailsM.get());
    }

    @Tag(name = "LOB OnChainStatuses", description = "LOB OnChainStatuses API")
    @Operation(description = "LOB transactions present on chain or not", responses = {
            @ApiResponse(content =
                    {@Content(mediaType = APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = LOBOnChainTxStatusResponse.class)))}
            )
    })
    @PostMapping(value = "/on-chain-statuses", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getOnChainStatuses(@Valid @RequestBody LOBOnChainTxStatusRequest onChainTxStatusRequest) {
        val onChainTxStatuses = onChainTxStatusRequest.getTransactionIds().stream()
                .collect(Collectors.toMap(
                        transactionId -> transactionId,
                        transactionService::exists
                ));

        return ResponseEntity.ok()
                .body(new LOBOnChainTxStatusResponse(onChainTxStatuses, network));
    }

}
