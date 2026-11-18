package org.cardanofoundation.lob.app.support.security.access;

/**
 * Keeps the identity provider up to date with the organisations: called after one is created or renamed.
 */
public interface OrganisationAccessProvisioner {

    void organisationCreated(String organisationId, String organisationName);

    void organisationRenamed(String organisationId, String organisationName);

}
