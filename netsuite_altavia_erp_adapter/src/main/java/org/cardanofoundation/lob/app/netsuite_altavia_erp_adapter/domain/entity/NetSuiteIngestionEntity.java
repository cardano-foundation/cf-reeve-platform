package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.domain.entity;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.domain.Persistable;

import org.cardanofoundation.lob.app.support.spring_audit.CommonEntity;

@Entity
@Table(name = "netsuite_adapter_ingestion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NetSuiteIngestionEntity extends CommonEntity implements Persistable<String> {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "adapter_instance_id", nullable = false)
    private String adapterInstanceId;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JoinColumn(name = "netsuite_ingestion_id", referencedColumnName = "id")
    private List<NetsuiteIngestionBody> ingestionBodies = new ArrayList<>();

    @Override
    public String getId() {
        return id;
    }

    public void addBody(NetsuiteIngestionBody body) {
        this.ingestionBodies.add(body);
    }

}
