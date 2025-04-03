package org.cardanofoundation.lob.app.organisation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.cardanofoundation.lob.app.organisation.domain.entity.OrganisationCurrency;
import org.cardanofoundation.lob.app.organisation.repository.OrganisationCurrencyRepository;

@ExtendWith(MockitoExtension.class)
class OrganisationCurrencyServiceTest {

    @Mock
    private OrganisationCurrencyRepository organisationCurrencyRepository;

    @InjectMocks
    private OrganisationCurrencyService organisationCurrencyService;

    private final String organisationId = "org123";
    private final String customerCode = "cust001";
    private OrganisationCurrency.Id currencyId;
    private OrganisationCurrency organisationCurrency;

    @BeforeEach
    void setUp() {
        currencyId = new OrganisationCurrency.Id(organisationId, customerCode);
        organisationCurrency = new OrganisationCurrency(currencyId, "USD");

    }

    @Test
    void testFindByOrganisationIdAndCode_Found() {
        when(organisationCurrencyRepository.findById(currencyId)).thenReturn(Optional.of(organisationCurrency));

        Optional<OrganisationCurrency> result = organisationCurrencyService.findByOrganisationIdAndCode(organisationId, customerCode);

        assertTrue(result.isPresent());
        assertEquals(organisationCurrency, result.get());
        verify(organisationCurrencyRepository).findById(currencyId);
    }

    @Test
    void testFindByOrganisationIdAndCode_NotFound() {
        when(organisationCurrencyRepository.findById(currencyId)).thenReturn(Optional.empty());

        Optional<OrganisationCurrency> result = organisationCurrencyService.findByOrganisationIdAndCode(organisationId, customerCode);

        assertFalse(result.isPresent());
        verify(organisationCurrencyRepository).findById(currencyId);
    }

    @Test
    void testFindAllByOrganisationId() {
        Set<OrganisationCurrency> currencies = Set.of(organisationCurrency);
        when(organisationCurrencyRepository.findAllByOrganisationId(organisationId)).thenReturn(currencies);

        Set<OrganisationCurrency> result = organisationCurrencyService.findAllByOrganisationId(organisationId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains(organisationCurrency));
        verify(organisationCurrencyRepository).findAllByOrganisationId(organisationId);
    }
}
