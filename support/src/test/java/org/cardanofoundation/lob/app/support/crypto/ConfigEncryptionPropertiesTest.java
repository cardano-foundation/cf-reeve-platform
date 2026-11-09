package org.cardanofoundation.lob.app.support.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigEncryptionPropertiesTest {

    @Test
    void toStringNeverContainsKeyMaterial() {
        ConfigEncryptionProperties properties = new ConfigEncryptionProperties("PUBLIC-KEY-MATERIAL", "PRIVATE-KEY-MATERIAL");

        assertThat(properties.toString())
                .doesNotContain("PUBLIC-KEY-MATERIAL")
                .doesNotContain("PRIVATE-KEY-MATERIAL");
    }

    @Test
    void toStringStillShowsWhichKeysAreConfigured() {
        assertThat(new ConfigEncryptionProperties("pub", null).toString())
                .contains("publicKey=<set>")
                .contains("privateKey=<unset>");
    }

}
