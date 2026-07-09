package org.cardano.foundation.lob.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.List;

import org.cardano.foundation.lob.repository.AddressBalance;
import org.cardano.foundation.lob.repository.AddressBalanceRepository;
import org.junit.jupiter.api.Test;

class AddressBalanceServiceTest {

    @Test
    void returnsBalancesFromRepository() {
        var repository = mock(AddressBalanceRepository.class);
        when(repository.findAddressBalances()).thenReturn(List.of(
                balance("addr_test1aaa", 5_000_000L),
                balance("addr_test1bbb", 12_345_678L)
        ));

        var service = new AddressBalanceService(repository);

        assertThat(service.getAllAddressBalances())
                .containsEntry("addr_test1aaa", 5_000_000L)
                .containsEntry("addr_test1bbb", 12_345_678L)
                .hasSize(2);
    }

    @Test
    void returnsEmptyMapWhenNoUtxosStored() {
        var repository = mock(AddressBalanceRepository.class);
        when(repository.findAddressBalances()).thenReturn(List.of());

        var service = new AddressBalanceService(repository);

        assertThat(service.getAllAddressBalances()).isEmpty();
    }

    @Test
    void nullTotalIsCoercedToZero() {
        var repository = mock(AddressBalanceRepository.class);
        when(repository.findAddressBalances()).thenReturn(List.of(
                balance("addr_test1ccc", null)
        ));

        var service = new AddressBalanceService(repository);

        assertThat(service.getAllAddressBalances()).containsEntry("addr_test1ccc", 0L);
    }

    private static AddressBalance balance(String ownerAddr, Long total) {
        BigInteger value = total != null ? BigInteger.valueOf(total) : null;
        return new AddressBalance() {
            @Override public String getOwnerAddr() { return ownerAddr; }
            @Override public BigInteger getTotal() { return value; }
        };
    }
}
