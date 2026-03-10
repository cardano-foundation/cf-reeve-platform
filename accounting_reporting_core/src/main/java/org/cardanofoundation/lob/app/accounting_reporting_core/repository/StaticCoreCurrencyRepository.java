package org.cardanofoundation.lob.app.accounting_reporting_core.repository;

import static java.util.Locale.ENGLISH;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency.IsoStandard.ISO_24165;
import static org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency.IsoStandard.ISO_4217;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

import org.springframework.stereotype.Service;

import org.cardanofoundation.lob.app.accounting_reporting_core.domain.core.CoreCurrency;

@Service
@Slf4j
public class StaticCoreCurrencyRepository implements CoreCurrencyRepository {

    private final List<CoreCurrency> currencies = new ArrayList<>();

    @PostConstruct
    public void init() {
        loadFromJVM();
        loadFromDTIFRegistry(); // TODO from DTIF registry file (json file)

        log.info("StaticCurrencyRepository init completed.");
    }

    private void loadFromDTIFRegistry() {
        currencies.add(
                new CoreCurrency(
                        ISO_24165,
                        "ADA",
                        Optional.of("HWGL1C2CK"),
                        "Cardano")
        );

        currencies.add(
                new CoreCurrency(
                        ISO_24165,
                        "NIG",
                        Optional.of("HWGL1C2CK"), // Not existing for Night, so setting it to the same as ADA
                        "Midnight")
        );

        currencies.add(
                new CoreCurrency(
                        ISO_24165,
                        "BTC",
                        Optional.of("4H95J0R2X"),
                        "Bitcoin")
        );

        currencies.add(
                new CoreCurrency(
                        ISO_24165,
                        "BSV",
                        Optional.of("2L8HS2MNP"),
                        "Bitcoin Satoshi Vision")
        );

        currencies.add(
                new CoreCurrency(
                        ISO_24165,
                        "BCH",
                        Optional.of("J9K583ZGG"),
                        "Bitcoin Cash")
        );
    }

    private void loadFromJVM() {
        java.util.Currency.getAvailableCurrencies().forEach(currency -> {
            val c = new CoreCurrency(
                    ISO_4217,
                    currency.getCurrencyCode(),
                    Optional.empty(),
                    currency.getDisplayName(ENGLISH)
            );

            currencies.add(c);
        });
    }

    @Override
    public List<CoreCurrency> allCurrencies() {
        return List.copyOf(currencies);
    }

    @Override
    public Optional<CoreCurrency> findByCurrencyId(String currencyId) {
        return currencies.stream()
                .filter(currency -> currency.toExternalId().equals(currencyId))
                .findFirst();
    }

}
