package org.cardanofoundation.lob.app.accounting_reporting_core.domain.entity;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.Embeddable;

import lombok.*;

import org.hibernate.annotations.Type;
import org.hibernate.envers.Audited;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Getter
@Setter
@EqualsAndHashCode
@Audited
public class Details {

    private String code;
    private String subCode;

    @Builder.Default
    @Type(value = io.hypersistence.utils.hibernate.type.json.JsonType.class)
    private Map<String, Object> bag = new HashMap<>();

}
