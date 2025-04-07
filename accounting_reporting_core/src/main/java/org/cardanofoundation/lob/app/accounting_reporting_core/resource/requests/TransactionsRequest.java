package org.cardanofoundation.lob.app.accounting_reporting_core.resource.requests;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import org.cardanofoundation.lob.app.support.spring_web.BaseRequest;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TransactionsRequest extends BaseRequest {

    @ArraySchema(arraySchema = @Schema(example = "[ {" +
            "\"id\": \"7e9e8bcbb38a283b41eab57add98278561ab51d23a16f3e3baf3daa461b84ab4\"}," +
            "{\"id\": \"7bce71783ff8e6501b33ce9797097f5633c069f17e4731d96467cdb311693fcb\"}," +
            "{\"id\": \"38e7e04304c86c1156128f7bdc548d51f175d5bdf83df1b3edda1832cac385dd\"}," +
            "{\"id\": \"95b5fb0d3ea32847d9d6bda2ff9da0be11bd5ba3175aad6f3cacafd14f9d28a3\"}," +
            "{\"id\": \"8b346f4d914fe652bde477fa3f6b630fbcf7ffd9859daf8df4fc63cdd1562e5c\"}," +
            "{\"id\": \"48335c2b63cffcef2a3cd0678b65c4fb16420f51110033024209957fbd58ec4e\"}" +
            "]"))
    @Size(min = 1)
    private Set<TransactionId> transactionIds;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TransactionId {

        @Schema(example = "7e9e8bcbb38a283b41eab57add98278561ab51d23a16f3e3baf3daa461b84ab4")
        @NotBlank
        private String id;

    }

}
