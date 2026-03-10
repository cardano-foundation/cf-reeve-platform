package org.cardanofoundation.lob.app.accounting_reporting_core.resource.views;

import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import org.springframework.http.ProblemDetail;

@Getter
@Setter
@AllArgsConstructor
public class BatchReprocessView {

    private String batchId;

    private boolean success;

    private Optional<ProblemDetail> error;

    public static BatchReprocessView createSuccess(String batchId) {
        return new BatchReprocessView(
                batchId,
                true,
                Optional.empty()
        );
    }

    public static BatchReprocessView createFail(String batchId,
                                                ProblemDetail error) {
        return new BatchReprocessView(batchId, false, Optional.of(error));
    }

}
