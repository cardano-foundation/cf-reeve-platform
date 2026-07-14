package org.cardanofoundation.lob.app.document_vault.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import org.springframework.data.domain.Persistable;

@Getter
@Setter
@NoArgsConstructor
@Entity(name = "document_vault.WrappedRecordEntity")
@Table(name = "document_vault_wrapped_record")
public class WrappedRecordEntity extends VaultBaseEntity implements Persistable<WrappedRecordId> {

    @EmbeddedId
    private WrappedRecordId id;

    /** Opaque, client-encrypted blob. The server must never parse or transform it (blueprint B2). */
    @NotBlank
    @ToString.Exclude
    @Column(name = "record", nullable = false, columnDefinition = "text")
    private String record;

    @Column(name = "version", nullable = false)
    private int version;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
