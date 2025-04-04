package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.*;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity.RejectionReason;
import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionItemsRejectionRequest extends BaseRequest {

    @Schema(example = "ReadyToApprove_3_8a283b41eab57add98278561ab51d23f3f3daa461b84ab4")
    @NotBlank
    private String transactionId;

    @ArraySchema(arraySchema = @Schema(example = "[ " +
            "{\"txItemId\": \"1a8fd26dc5bcc99e8b5cc16545c4ce383aabf6862652015c0e45ff7dbfa69263\",\"rejectionReason\":\"INCORRECT_VAT_CODE\"}," +
            "{\"txItemId\": \"f02873e91884eb28160b5d56a7fe1cd2ef3e079ab25875f39779ad3fca36c063\",\"rejectionReason\":\"REVIEW_PARENT_PROJECT_CODE\"}" +
            "]"))
    @NotNull
    @Size(min = 1)
    @Valid
    private Set<TxItemRejectionRequest> transactionItemsRejections;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class TxItemRejectionRequest {

        @Schema(example = "e7a70ff49619cea765db827cc3a7e6a320d8e0859f7818ed43b6c4bc8025fc60")
        @NotBlank
        private String txItemId;

        @Schema(example = "INCORRECT_VAT_CODE")
        @NotNull
        private RejectionReason rejectionReason;

        public void setRejectionCode(RejectionReason rejectionCode) {
            rejectionReason = rejectionCode;
        }

    }

}
