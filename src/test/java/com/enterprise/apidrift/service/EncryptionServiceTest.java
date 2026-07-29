package com.enterprise.apidrift.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionServiceTest {

    private EncryptionService service;

    @BeforeEach
    void setUp() {
        service = new EncryptionService();
        ReflectionTestUtils.setField(service, "configuredKey", "test-key-32-bytes-long-for-aes!!");
        service.init();
    }

    @Test
    @DisplayName("Encrypt then decrypt returns original plaintext")
    void roundTrip() {
        String original = "my-secret-api-token-12345";
        String encrypted = service.encrypt(original);
        String decrypted = service.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    @DisplayName("Encryption produces different output each time (random IV)")
    void encryptionIsNonDeterministic() {
        String plaintext = "token";
        String enc1 = service.encrypt(plaintext);
        String enc2 = service.encrypt(plaintext);
        assertThat(enc1).isNotEqualTo(enc2);
    }

    @Test
    @DisplayName("Encrypted output is Base64")
    void encryptedIsBase64() {
        String encrypted = service.encrypt("test");
        assertThat(encrypted).matches("^[A-Za-z0-9+/=]+$");
    }

    @Test
    @DisplayName("Encrypt null returns null")
    void encryptNullReturnsNull() {
        assertThat(service.encrypt(null)).isNull();
    }

    @Test
    @DisplayName("Encrypt blank returns null")
    void encryptBlankReturnsNull() {
        assertThat(service.encrypt("")).isNull();
        assertThat(service.encrypt("   ")).isNull();
    }

    @Test
    @DisplayName("Decrypt null returns null")
    void decryptNullReturnsNull() {
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("Decrypt blank returns null")
    void decryptBlankReturnsNull() {
        assertThat(service.decrypt("")).isNull();
        assertThat(service.decrypt("   ")).isNull();
    }

    @Test
    @DisplayName("Decrypt garbage throws RuntimeException")
    void decryptGarbageThrows() {
        assertThatThrownBy(() -> service.decrypt("not-valid-base64!!!"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Token decryption failed");
    }

    @Test
    @DisplayName("Decrypt tampered ciphertext throws RuntimeException")
    void decryptTamperedThrows() {
        String encrypted = service.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";
        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Token decryption failed");
    }

    @Test
    @DisplayName("Long token round trip works")
    void longTokenRoundTrip() {
        String longToken = "a".repeat(500);
        String encrypted = service.encrypt(longToken);
        assertThat(service.decrypt(encrypted)).isEqualTo(longToken);
    }

    @Test
    @DisplayName("Special characters round trip works")
    void specialCharsRoundTrip() {
        String token = "token!@#$%^&*()_+-=[]{}|;':\",./<>?`~";
        String encrypted = service.encrypt(token);
        assertThat(service.decrypt(encrypted)).isEqualTo(token);
    }
}
