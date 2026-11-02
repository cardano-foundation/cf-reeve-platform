package org.cardanofoundation.lob.app.support.security.access;

/**
 * Called after an organisation is created, so the identity provider knows about it and the creator can access it.
 */
public interface OrganisationAccessProvisioner {

    void organisationCreated(String organisationId, String organisationName);

}
