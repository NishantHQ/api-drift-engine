package com.enterprise.apidrift.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for vendor API tokens at rest.
 * BRD NFR-3: All vendor API tokens stored in DB must be encrypted at rest using AES-256-GCM.
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // bits

    @Value("${encryption.aes-key:changeme-32byte-key-plaintext}")
    private String configuredKey;

    private SecretKeySpec secretKey;

    @PostConstruct
    void init() {
        // Ensure the key is 32 bytes for AES-256
        byte[] keyBytes = new byte[32];
        byte[] configuredBytes = configuredKey.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(configuredBytes, 0, keyBytes, 0,
                Math.min(configuredBytes.length, 32));
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        log.info("Encryption service initialized with AES-256-GCM");
    }

    /**
     * Encrypts a plaintext token and returns the Base64-encoded IV + ciphertext.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Encryption failed: {}", e.getMessage());
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded IV + ciphertext back to plaintext.
     */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return null;
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed: {}", e.getMessage());
            throw new RuntimeException("Token decryption failed", e);
        }
    }
}
