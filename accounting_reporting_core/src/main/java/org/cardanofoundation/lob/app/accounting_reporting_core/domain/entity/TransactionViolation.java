package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import static jakarta.persistence.EnumType.STRING;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import javax.annotation.Nullable;

import lombok.*;

import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.Type;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import org.hibernate.envers.Audited;
import org.javers.core.metamodel.annotation.DiffIgnore;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Source;
import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.TransactionViolationCode;

@Embeddable
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@Audited
public class TransactionViolation {

    @Nullable
    private String txItemId;

    @NotNull
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private TransactionViolationCode code;

    @NotNull
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Violation.Severity severity;

    @Nullable
    private String subCode;

    @NotNull
    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private Source source;

    @NotBlank
    private String processorModule;

    @NotNull
    @Builder.Default
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    @DiffIgnore
    private Map<String, Object> bag = new HashMap<>();

    @Override
    public String toString() {
        return "TransactionViolation{code=%s, txItemId=%s".formatted(code, txItemId);
    }

    public void setTxItemId(Optional<String> txItemId) {
        this.txItemId = txItemId.orElse(null);
    }

    public Optional<String> getTxItemId() {
        return Optional.ofNullable(txItemId);
    }

    public Optional<String> getSubCode() {
        return Optional.ofNullable(subCode);
    }

    public void setSubCode(Optional<String> subCode) {
        this.subCode = subCode.orElse(null);
    }

}
