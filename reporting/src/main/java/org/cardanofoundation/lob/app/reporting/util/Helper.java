package org.cardanofoundation.lob.app.reporting.util;

import org.zalando.problem.Problem;
import org.zalando.problem.Status;

public class Helper {

    private Helper() {
        // Utility class
    }

    public static Problem buildDataModeError(String dataModeStr) {
        if (dataModeStr == null || dataModeStr.isBlank()) {
            return Problem.builder()
                    .withTitle(Constants.DATA_MODE_MISSING)
                    .withDetail("Data mode must be either SYSTEM or USER")
                    .withStatus(Status.BAD_REQUEST)
                    .build();
        }
        return Problem.builder()
                .withTitle(Constants.INVALID_DATA_MODE)
                .withDetail("Data mode must be either SYSTEM or USER")
                .withStatus(Status.BAD_REQUEST)
                .build();
    }
}
