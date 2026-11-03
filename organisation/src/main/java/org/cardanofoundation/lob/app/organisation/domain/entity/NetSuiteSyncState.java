package org.cardanofoundation.lob.app.organisation.domain.entity;

public enum NetSuiteSyncState {
    /** Written locally, not yet acknowledged by the netsuite module. */
    PENDING,
    /** The netsuite module stored it. */
    APPLIED,
    /** The netsuite module could not store it. */
    FAILED
}
