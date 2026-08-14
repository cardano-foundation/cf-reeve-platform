package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class NetSuiteClientTest {

    private static String pem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();

        pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded)
                + "\n-----END PRIVATE KEY-----";
    }

    private NetSuiteClient clientWith(String privateKeyPem) {
        return new NetSuiteClient(new ObjectMapper(), RestClient.create(),
                "https://base", "https://token", privateKeyPem, "cert-1", "client-1", 100);
    }

    private Object loadPrivateKey(NetSuiteClient client) throws Exception {
        Method method = NetSuiteClient.class.getDeclaredMethod("loadPrivateKey");
        method.setAccessible(true);

        return method.invoke(client);
    }

    @Test
    void parsesAPemSuppliedDirectlyRatherThanFromAFile() throws Exception {
        assertThat(loadPrivateKey(clientWith(pem))).isNotNull();
    }

    @Test
    void toleratesPemsWithCarriageReturnsAndSurroundingWhitespace() {
        String crlfPem = "  \r\n" + pem.replace("\n", "\r\n") + "\r\n  ";

        assertThatCode(() -> loadPrivateKey(clientWith(crlfPem))).doesNotThrowAnyException();
    }

    @Test
    void exposesTheBaseUrlItWasConstructedWith() {
        assertThat(clientWith(pem).getBaseUrl()).isEqualTo("https://base");
    }

}
