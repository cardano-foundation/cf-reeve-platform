package org.cardano.foundation.lob.repository;

import java.math.BigInteger;

/**
 * Spring Data JPA projection (interface-based) for the address-balance aggregate query.
 * The alias names in {@link AddressBalanceRepository#findAddressBalances()} match the
 * getter names here so Spring Data can back each row with a proxy implementing this interface.
 */
public interface AddressBalance {

    String getOwnerAddr();

    BigInteger getTotal();
}
