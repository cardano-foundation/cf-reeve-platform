package org.cardanofoundation.lob.app.support.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-bound configuration for the secret cipher key material. Bound once per context via
 * {@code @EnableConfigurationProperties} on the module crypto configs; the values arrive through
 * the usual property sources (application.yml maps {@code LOB_CONFIG_ENCRYPTION_PUBLIC_KEY} /
 * {@code LOB_CONFIG_ENCRYPTION_PRIVATE_KEY} onto these keys, and Spring's relaxed binding accepts
 * the canonical env forms as well).
 * <p>
 * Values are Base64 of DER: public = X.509 SubjectPublicKeyInfo, private = PKCS#8. Validation
 * does NOT happen here — the cipher constructors own it, so a missing or malformed key fails only
 * on the tier that actually needs that key, with a message naming which key (public or private)
 * is wrong.
 * <p>
 * {@link #toString()} is redacted: the record holds decrypt-capable key material, and any log
 * line, exception or diagnostic dump that stringifies it must not leak the key.
 *
 * @param publicKey  encrypt-only half, required where {@code lob.organisation.enabled=true}
 * @param privateKey decrypt-only half, required where {@code lob.netsuite.enabled=true}
 */
@ConfigurationProperties(prefix = "lob.security.config-encryption")
public record ConfigEncryptionProperties(String publicKey, String privateKey) {

    public ConfigEncryptionProperties {
        publicKey = publicKey == null ? "" : publicKey;
        privateKey = privateKey == null ? "" : privateKey;
    }

    @Override
    public String toString() {
        return "ConfigEncryptionProperties[publicKey=%s, privateKey=%s]"
                .formatted(redacted(publicKey), redacted(privateKey));
    }

    private static String redacted(String value) {
        return value.isBlank() ? "<unset>" : "<set>";
    }

}
