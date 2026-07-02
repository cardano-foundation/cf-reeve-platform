package org.cardano.foundation.lob.service;

import org.cardano.foundation.lob.repository.AddressBalanceRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AddressBalanceServiceTest {

    @Test
    void returnsBalancesFromRepository() {
        var repository = mock(AddressBalanceRepository.class);
        Map<String, Long> stub = new LinkedHashMap<>();
        stub.put("addr_test1aaa", 5_000_000L);
        stub.put("addr_test1bbb", 12_345_678L);
        when(repository.findAllAddressBalances()).thenReturn(stub);

        var service = new AddressBalanceService(repository);

        assertThat(service.getAllAddressBalances())
                .containsExactlyEntriesOf(stub);
    }

    @Test
    void returnsEmptyMapWhenNoUtxosStored() {
        var repository = mock(AddressBalanceRepository.class);
        when(repository.findAllAddressBalances()).thenReturn(Map.of());

        var service = new AddressBalanceService(repository);

        assertThat(service.getAllAddressBalances()).isEmpty();
    }
}
