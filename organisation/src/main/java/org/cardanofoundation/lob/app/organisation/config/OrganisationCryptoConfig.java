package org.cardanofoundation.lob.app.organisation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import org.cardanofoundation.lob.app.support.crypto.SecretCipherConfig;

/**
 * Makes {@link org.cardanofoundation.lob.app.support.crypto.SecretCipher} available wherever the
 * organisation module's beans are.
 * <p>
 * This deliberately lives <em>inside</em> {@code org.cardanofoundation.lob.app.organisation}
 * rather than alongside {@code OrganisationModuleConfig} (which sits in
 * {@code org.cardanofoundation.lob.app.config}). Anything that component-scans the organisation
 * package picks up {@code NetSuiteConfigAdminService}, which requires a cipher — including test
 * contexts and applications that scan the package directly without activating the module config.
 * Declaring the bean next to the module config would leave those contexts unable to start.
 * <p>
 * Spring registers an imported {@code @Configuration} once regardless of how many places import
 * it, so this coexists with the imports on the module config classes.
 */
@Configuration
@Import(SecretCipherConfig.class)
public class OrganisationCryptoConfig {
}
