package com.game.client;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts/decrypts a saved password using AES-256-GCM.
 * The key is derived from the user's email address (SHA-256 of pepper + email),
 * so the stored blob is useless without the correct email.
 * A random 12-byte IV is prepended to the ciphertext and the whole thing is Base64-encoded.
 */
public final class PasswordCrypto {

    private static final String APP_PEPPER   = "multiplayer-game-pwd-v1";
    private static final int    IV_LEN       = 12;
    private static final int    GCM_TAG_BITS = 128;

    private PasswordCrypto() {}

    /** Encrypt {@code plaintext} using {@code email} as the key salt. Returns Base64 or "" on error. */
    public static String encrypt(String plaintext, String email) {
        try {
            byte[] iv  = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(email), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(IV_LEN + ct.length);
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            return "";
        }
    }

    /** Decrypt a value produced by {@link #encrypt}. Returns "" on failure (wrong email / corrupt data). */
    public static String decrypt(String encoded, String email) {
        try {
            byte[]     data = Base64.getDecoder().decode(encoded);
            ByteBuffer buf  = ByteBuffer.wrap(data);

            byte[] iv = new byte[IV_LEN];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyFor(email), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKeySpec keyFor(String email) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update((APP_PEPPER + ":" + email.trim().toLowerCase()).getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(sha.digest(), "AES");
    }
}
