package org.cardanofoundation.lob.app.document_vault.domain.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class WrappedRecordId implements Serializable {

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "credential_id", nullable = false)
    private String credentialId;
}
