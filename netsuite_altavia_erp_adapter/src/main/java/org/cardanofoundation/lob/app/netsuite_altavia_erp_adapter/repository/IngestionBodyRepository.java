package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity.NetsuiteIngestionBody;

public interface IngestionBodyRepository extends JpaRepository<NetsuiteIngestionBody, String> {

}
