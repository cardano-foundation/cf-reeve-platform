package org.cardanofoundation.lob.app.support.security.access;

import java.util.Map;

import org.springframework.security.oauth2.jwt.Jwt;

final class TestJwts {

    private TestJwts() {
    }

    static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        claims.forEach(builder::claim);
        return builder.build();
    }

}
