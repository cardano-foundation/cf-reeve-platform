package org.cardanofoundation.lob.app.keri_attestation.cesr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CESRStreamUtilTest {

    private static final String STREAM = "{\"v\":\"KERI10JSON\",\"t\":\"vcp\",\"a\":{\"x\":1}}-AABATT1"
            + "{\"v\":\"ACDC10JSON\",\"t\":\"iss\"}-BBBATT2"
            + "{\"v\":\"ACDC10JSON\",\"d\":\"E1\"}";

    @Test
    void parsesEventsWithTheirAttachmentsAndRoundTripsByteForByte() {
        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData(STREAM);

        assertEquals(3, parsed.size());
        assertEquals("-AABATT1", parsed.get(0).get("atc"));
        assertEquals("-BBBATT2", parsed.get(1).get("atc"));
        assertEquals("", parsed.get(2).get("atc"));
        assertEquals(STREAM, CESRStreamUtil.makeCESRStream(parsed));
    }

    @Test
    void skipsLeadingBytesBeforeTheFirstEvent() {
        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData("junk{\"t\":\"vcp\"}-ATT");

        assertEquals(1, parsed.size());
        assertEquals("-ATT", parsed.get(0).get("atc"));
    }

    /** A truncated stream (never-closed brace) used to spin forever without advancing the index. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void aTruncatedEventEndsTheParseInsteadOfLoopingForever() {
        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData("{\"t\":\"vcp\"}-ATT{\"t\":\"iss\"");

        assertEquals(1, parsed.size());
        assertEquals("-ATT", parsed.get(0).get("atc"));
    }

    @Test
    void anUnparseableEventIsSkipped() {
        List<Map<String, Object>> parsed = CESRStreamUtil.parseCESRData("{not json}-ATT{\"t\":\"iss\"}");

        assertEquals(1, parsed.size());
        assertTrue(parsed.get(0).get("event") instanceof Map);
    }
}
