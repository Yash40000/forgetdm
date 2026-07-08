package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticApiSafetyTest {
    @Test void apiGeneratorRequiresExplicitAllowlist() {
        assertThrows(ApiException.class, () ->
                SyntheticApiSafety.validate("https://api.example.test/customer/1", ""));
    }

    @Test void apiGeneratorAllowsExactHostAndWildcardSuffix() {
        URI exact = SyntheticApiSafety.validate("https://api.example.test/customer/1", "api.example.test");
        assertEquals("api.example.test", exact.getHost());

        URI child = SyntheticApiSafety.validate("https://svc.bank.example/customer/1", "*.bank.example");
        assertEquals("svc.bank.example", child.getHost());
    }

    @Test void apiGeneratorRejectsUnsafeUrlShapes() {
        assertThrows(ApiException.class, () ->
                SyntheticApiSafety.validate("file:///etc/passwd", "example.test"));
        assertThrows(ApiException.class, () ->
                SyntheticApiSafety.validate("https://user:pass@api.example.test/customer/1", "api.example.test"));
        assertThrows(ApiException.class, () ->
                SyntheticApiSafety.validate("https://api.example.test/customer/1#frag", "api.example.test"));
    }
}
