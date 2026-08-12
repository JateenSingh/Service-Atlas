package com.serviceatlas.export.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serviceatlas.config.ServiceAtlasProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Q-3 — OAuth tokens are encrypted at rest, and never silently stored in the clear. */
class LucidTokenCipherTest {

    private LucidTokenCipher cipherWithKey(String key) {
        ServiceAtlasProperties properties = new ServiceAtlasProperties();
        properties.getLucid().setTokenEncryptionKey(key);
        return new LucidTokenCipher(properties);
    }

    @Test
    @DisplayName("A token round-trips through encryption")
    void roundTrips() {
        LucidTokenCipher cipher = cipherWithKey("a-long-passphrase");
        String token = "{\"access_token\":\"abc123\",\"refresh_token\":\"def456\"}";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).doesNotContain("abc123").doesNotContain("access_token");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    @DisplayName("Each encryption uses a fresh nonce, so identical tokens do not look identical")
    void usesAFreshNonce() {
        LucidTokenCipher cipher = cipherWithKey("key");

        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("Without a configured key, encryption fails rather than storing plaintext")
    void refusesWithoutAKey() {
        LucidTokenCipher cipher = cipherWithKey(null);

        assertThat(cipher.isConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("token"))
                .hasRootCauseMessage(
                        "No Lucid token encryption key is configured. Set LUCID_TOKEN_KEY before "
                                + "connecting a Lucid account.");
    }

    @Test
    @DisplayName("A token encrypted under a different key cannot be read")
    void refusesTheWrongKey() {
        String encrypted = cipherWithKey("first-key").encrypt("secret");

        assertThatThrownBy(() -> cipherWithKey("second-key").decrypt(encrypted))
                .hasMessageContaining("Could not decrypt");
    }

    @Test
    @DisplayName("Tampered ciphertext is rejected rather than decrypted to garbage")
    void detectsTampering() {
        LucidTokenCipher cipher = cipherWithKey("key");
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cipher.decrypt(tampered)).hasMessageContaining("Could not decrypt");
    }
}
