package com.leslie.colorosnote.core.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore-backed AES-GCM encryption for secrets such as cloud API keys
 * (spec 32). Keys never leave the TEE; we persist only ciphertext + IV.
 * No custom cryptography. Android-dependent, unit-tested via the pure helper
 * {@link SecretCodec} for encoding only.
 */
public final class KeystoreSecretStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private KeystoreSecretStore() {
    }

    public static SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(alias)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(alias, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Encrypts a secret; returns iv || ciphertext (single blob for storage). */
    public static byte[] encrypt(String alias, String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    public static String decrypt(String alias, byte[] blob) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[blob.length - IV_BYTES];
        System.arraycopy(blob, 0, iv, 0, IV_BYTES);
        System.arraycopy(blob, IV_BYTES, ct, 0, ct.length);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }
}
