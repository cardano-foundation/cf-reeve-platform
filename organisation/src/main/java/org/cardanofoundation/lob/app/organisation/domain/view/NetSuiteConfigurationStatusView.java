package org.cardanofoundation.lob.app.organisation.domain.view;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;

/**
 * Status of an organisation's NetSuite configuration. Never carries the private key — only a
 * fingerprint, so an admin can tell which key is installed without being able to read it.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class NetSuiteConfigurationStatusView {

    private boolean configured;
    private String baseUrl;
    private String tokenUrl;
    private String clientId;
    private String certificateId;
    private String privateKeyFingerprint;
    private String syncState;
    private String syncMessage;
    private Boolean netsuiteValid;
    private Instant lastValidatedAt;
    private String validationMessage;
    private Instant updatedAt;
    private String updatedBy;

    public static NetSuiteConfigurationStatusView notConfigured() {
        return NetSuiteConfigurationStatusView.builder()
                .configured(false)
                .build();
    }

    public static NetSuiteConfigurationStatusView of(NetSuiteConfigState state) {
        return NetSuiteConfigurationStatusView.builder()
                .configured(true)
                .baseUrl(state.getBaseUrl())
                .tokenUrl(state.getTokenUrl())
                .clientId(state.getClientId())
                .certificateId(state.getCertificateId())
                .privateKeyFingerprint(state.getPrivateKeyFingerprint())
                .syncState(state.getSyncState().name())
                .syncMessage(state.getSyncMessage())
                .netsuiteValid(state.getNetsuiteValid())
                .lastValidatedAt(state.getLastValidatedAt())
                .validationMessage(state.getValidationMessage())
                .updatedAt(state.getUpdatedAt())
                .updatedBy(state.getUpdatedBy())
                .build();
    }

}
