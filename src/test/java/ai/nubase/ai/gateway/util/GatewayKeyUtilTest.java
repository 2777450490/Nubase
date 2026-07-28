package ai.nubase.ai.gateway.util;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GatewayKeyUtilTest {

    @Test
    void generateProducesParseableKey() {
        String key = GatewayKeyUtil.generate("app20260108abc", 48);
        assertTrue(key.startsWith("nbk_app20260108abc_"));
        assertTrue(GatewayKeyUtil.isGatewayKey(key));
        assertEquals("app20260108abc", GatewayKeyUtil.parseAppCode(key));
    }

    @Test
    void parseAppCodeHandlesAppCodeContainingUnderscores() {
        // secret is everything after the LAST underscore, so an appCode with underscores
        // is still recovered correctly.
        String key = "nbk_app_with_underscores_SeCrEt123";
        assertEquals("app_with_underscores", GatewayKeyUtil.parseAppCode(key));
    }

    @Test
    void parseAppCodeRejectsNonGatewayKeys() {
        assertNull(GatewayKeyUtil.parseAppCode("sk-ant-123"));
        assertNull(GatewayKeyUtil.parseAppCode(null));
        assertNull(GatewayKeyUtil.parseAppCode("nbk_noSecret")); // no trailing _secret
        assertFalse(GatewayKeyUtil.isGatewayKey("Bearer xyz"));
    }

    @Test
    void serviceRoleJwtIsAcceptedAsGatewayCredential() {
        String key = serviceRoleKey("project_one", "service_role");

        assertEquals("project_one", GatewayKeyUtil.parseServiceRoleAppCode(key));
        assertTrue(GatewayKeyUtil.isGatewayCredential(key));
        assertNull(GatewayKeyUtil.parseServiceRoleAppCode(serviceRoleKey("project_one", "authenticated")));
        assertNull(GatewayKeyUtil.parseServiceRoleAppCode("header.payload.signature"));
        assertFalse(GatewayKeyUtil.isGatewayCredential("project_one"));
    }

    @Test
    void sha256HexIsStableAnd64Chars() {
        String a = GatewayKeyUtil.sha256Hex("nbk_app_secret");
        String b = GatewayKeyUtil.sha256Hex("nbk_app_secret");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, GatewayKeyUtil.sha256Hex("nbk_app_secret2"));
    }

    @Test
    void displayPrefixIsTruncated() {
        String key = GatewayKeyUtil.generate("appABC", 48);
        assertEquals(20, GatewayKeyUtil.displayPrefix(key).length());
    }

    private String serviceRoleKey(String appCode, String role) {
        return Jwts.builder()
                .claim("ref", appCode)
                .claim("role", role)
                .signWith(Keys.hmacShaKeyFor(
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
