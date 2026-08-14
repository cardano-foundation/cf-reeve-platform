package org.cardanofoundation.lob.app.organisation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.cardanofoundation.lob.app.organisation.domain.entity.NetSuiteConfigState;

@Repository
public interface NetSuiteConfigStateRepository extends JpaRepository<NetSuiteConfigState, String> {
}
