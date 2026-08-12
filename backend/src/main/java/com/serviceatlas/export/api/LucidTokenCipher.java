package com.serviceatlas.export.api;

import com.serviceatlas.config.ServiceAtlasProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts Lucid OAuth tokens before they are stored (FR-6.2, Q-3).
 *
 * <p>AES-GCM with a random 12-byte nonce per encryption, prefixed to the ciphertext. The key comes
 * from configuration — in practice an environment variable — and is stretched to 256 bits with
 * SHA-256, so an operator can supply a passphrase rather than exact key bytes.
 *
 * <p>If no key is configured, encryption fails loudly rather than falling back to plaintext: a
 * token silently stored in the clear is worse than a failed connection.
 */
@Component
public class LucidTokenCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final ServiceAtlasProperties properties;
    private final SecureRandom random = new SecureRandom();

    public LucidTokenCipher(ServiceAtlasProperties properties) {
        this.properties = properties;
    }

    public boolean isConfigured() {
        String key = properties.getLucid().getTokenEncryptionKey();
        return key != null && !key.isBlank();
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the Lucid token", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] nonce = java.util.Arrays.copyOfRange(combined, 0, NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(combined, NONCE_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt the stored Lucid token", e);
        }
    }

    private SecretKeySpec key() throws Exception {
        String configured = properties.getLucid().getTokenEncryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "No Lucid token encryption key is configured. Set LUCID_TOKEN_KEY before connecting "
                            + "a Lucid account.");
        }
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(configured.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }
}
