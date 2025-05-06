package org.cardanofoundation.lob.app.support.spring_audit;


import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import jakarta.persistence.Transient;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.javers.core.metamodel.annotation.DiffIgnore;

@Setter
@Getter
@MappedSuperclass
@NoArgsConstructor
public abstract class CommonDateOnlyLockableEntity extends CommonDateOnlyEntity {

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "locked_at")
    @DiffIgnore
    protected LocalDateTime lockedAt;

    @Transient
    @DiffIgnore
    protected boolean isNew = true;

    public Optional<LocalDateTime> getLockedAt() {
        return Optional.ofNullable(lockedAt);
    }

}
