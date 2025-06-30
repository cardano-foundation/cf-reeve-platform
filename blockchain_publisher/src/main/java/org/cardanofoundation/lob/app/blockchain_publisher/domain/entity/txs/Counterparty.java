package org.cardanofoundation.lob.app.blockchain_publisher.domain.entity.txs;

import static jakarta.persistence.EnumType.STRING;

import java.util.Objects;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Enumerated;

import lombok.*;

import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

@Embeddable
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@Setter
public class Counterparty {

    private String customerCode;

    @Enumerated(STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    private org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.Counterparty.Type type;

    @Override
    public int hashCode() {
        return Objects.hash(customerCode, type);
    }

}
