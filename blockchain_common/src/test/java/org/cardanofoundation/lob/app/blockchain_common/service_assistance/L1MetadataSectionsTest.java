package org.cardanofoundation.lob.app.blockchain_common.service_assistance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.metadata.MetadataMap;

import org.junit.jupiter.api.Test;

class L1MetadataSectionsTest {

    private static String hex(MetadataMap map) throws CborException {
        return HexFormat.of().formatHex(CborSerializationUtil.serialize(map.getMap()));
    }

    @Test
    void metadataSectionEmitsSlotTimestampAndVersion() {
        MetadataMap section = L1MetadataSections.metadataSection(
                42L, Instant.parse("2026-01-01T00:00:00Z"), "1.2");

        assertThat(section.get("creation_slot")).isEqualTo(BigInteger.valueOf(42L));
        assertThat(section.get("timestamp")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(section.get("version")).isEqualTo("1.2");
    }

    /**
     * The version must stay a parameter: each publishable type has its own, and collapsing them would
     * change on-chain bytes for three of the four.
     */
    @Test
    void metadataSectionVersionIsCallerSupplied() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(L1MetadataSections.metadataSection(1L, at, "1.0").get("version")).isEqualTo("1.0");
        assertThat(L1MetadataSections.metadataSection(1L, at, "1.1").get("version")).isEqualTo("1.1");
        assertThat(L1MetadataSections.metadataSection(1L, at, "1.2").get("version")).isEqualTo("1.2");
    }

    @Test
    void orgSectionEmitsAllFiveFields() {
        MetadataMap section = L1MetadataSections.orgSection(
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        assertThat(section.get("id")).isEqualTo("org-1");
        assertThat(section.get("name")).isEqualTo("Acme");
        assertThat(section.get("tax_id_number")).isEqualTo("TAX-1");
        assertThat(section.get("currency_id")).isEqualTo("ISO_4217:CHF");
        assertThat(section.get("country_code")).isEqualTo("CH");
    }

    /**
     * Pins the encoded bytes of both sections. Together with
     * {@code CborCharacterizationTest} in blockchain_publisher, this is what makes the extraction
     * provably output-preserving.
     */
    @Test
    void sectionsEncodeToStableCbor() throws CborException {
        MetadataMap metadata = L1MetadataSections.metadataSection(
                1_000_000L, Instant.parse("2026-01-01T00:00:00Z"), "1.0");
        MetadataMap org = L1MetadataSections.orgSection(
                "org-1", "Acme", "TAX-1", "ISO_4217:CHF", "CH");

        assertThat(hex(metadata)).isEqualTo(
                "a36776657273696f6e63312e306974696d657374616d70743230"
                        + "32362d30312d30315430303a30303a30305a6d6372656174696f6e5f736c6f741a000f4240");
        assertThat(hex(org)).isEqualTo(
                "a5626964656f72672d31646e616d656441636d656b63757272656e63795f69646c49534f5f343231373a4348466c636f756e7472795f636f64656243486d7461785f69645f6e756d626572655441582d31");
    }
}
