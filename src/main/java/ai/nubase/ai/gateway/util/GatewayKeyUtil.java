package ai.nubase.ai.gateway.util;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI Gateway credential utility.
 * <p>
 * A credential can be either the project's service-role JWT or a random
 * {@code nbk_<appCode>_<secret>} key. Both forms are validated by SHA-256 hash in the tenant
 * database; JWT claims parsed here are untrusted until the filter verifies the signature.
 */
public final class GatewayKeyUtil {

    public static final String PREFIX = "nbk_";

    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private GatewayKeyUtil() {
    }

    /** 生成一个新的完整密钥：{@code nbk_<appCode>_<48位随机串>}。 */
    public static String generate(String appCode, int secretLen) {
        StringBuilder sb = new StringBuilder(secretLen);
        for (int i = 0; i < secretLen; i++) {
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return PREFIX + appCode + "_" + sb;
    }

    /**
     * 从完整密钥解析出 appCode。{@code nbk_<appCode>_<secret>}：去掉前缀后，secret 是最后一个下划线之后的
     * 部分，appCode 是其余部分（appCode 自身可含下划线时仍可正确解析）。
     */
    public static String parseAppCode(String key) {
        if (key == null || !key.startsWith(PREFIX)) {
            return null;
        }
        String rest = key.substring(PREFIX.length());
        int lastUnderscore = rest.lastIndexOf('_');
        if (lastUnderscore <= 0 || lastUnderscore >= rest.length() - 1) {
            return null;
        }
        return rest.substring(0, lastUnderscore);
    }

    /** 是否为网关自路由密钥。 */
    public static boolean isGatewayKey(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    /** Returns the unverified project ref only when the JWT declares the service_role role. */
    public static String parseServiceRoleAppCode(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JSONObject claims = JSONUtil.parseObj(payload);
            String role = claims.getStr("role");
            String appCode = claims.getStr("ref");
            if (!"service_role".equals(role)
                    || appCode == null
                    || !appCode.matches("[a-zA-Z0-9_-]{1,100}")) {
                return null;
            }
            return appCode;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Whether the value can be routed as either a random gateway key or a service-role JWT. */
    public static boolean isGatewayCredential(String key) {
        return isGatewayKey(key) || parseServiceRoleAppCode(key) != null;
    }

    /** 展示用前缀（脱敏），形如 {@code nbk_app123_abcd}。 */
    public static String displayPrefix(String key) {
        if (key == null) {
            return null;
        }
        return key.length() <= 20 ? key : key.substring(0, 20);
    }

    /** 完整密钥的 SHA-256 十六进制摘要。 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
