package com.leylink.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.leylink.core.port.Signer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Ed25519 signing (RFC 8032) with the BouncyCastle lightweight API, which works on every Android version
 * we support (the platform only offers Ed25519 from Android 13).
 *
 * Key handling:
 *  - On first launch a random 32-byte seed is generated.
 *  - The seed is never stored in the clear: it is encrypted with AES-256-GCM using a key that lives in
 *    the Android Keystore (hardware-backed where available, never exportable) and only the ciphertext is
 *    kept in app-private SharedPreferences.
 *  - After loading, the seed lives in memory only as the signing key object.
 *
 * What this protects against: a nearby phone forging or altering an SOS in someone else's name, and
 * tampering by relays. What it does NOT protect against: a phone that has been rooted/compromised, a
 * first-contact impostor (trust on first use), or anybody reading the message (nothing is encrypted).
 */
class KeystoreEd25519Signer(context: Context) : Signer {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val privateKey: Ed25519PrivateKeyParameters = loadOrCreate()
    private val publicKeyBytes: ByteArray = privateKey.generatePublicKey().encoded

    override fun publicKey(): ByteArray = publicKeyBytes.copyOf()

    override fun sign(data: ByteArray): ByteArray = synchronized(this) {
        Ed25519Signer().apply {
            init(true, privateKey)
            update(data, 0, data.size)
        }.generateSignature()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        publicKey.size == KEY_SIZE && signature.size == SIGNATURE_SIZE &&
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(publicKey, 0))
                update(data, 0, data.size)
            }.verifySignature(signature)
    } catch (e: RuntimeException) {
        false // hostile input must never crash the router
    }

    // ------------------------------------------------------------------ key storage

    private fun loadOrCreate(): Ed25519PrivateKeyParameters {
        val stored = prefs.getString(PREF_SEED, null)
        if (stored != null) {
            decrypt(stored)?.let { return Ed25519PrivateKeyParameters(it, 0) }
            // The Keystore key was lost (e.g. backup restore, lock screen change on some devices):
            // fall through and make a new identity. Peers will see a key mismatch for our old nodeId.
        }
        val seed = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_SEED, encrypt(seed)).apply()
        return Ed25519PrivateKeyParameters(seed, 0)
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(seed: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val sealed = cipher.doFinal(seed)
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun decrypt(encoded: String): ByteArray? = try {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, raw, 0, GCM_IV_BYTES))
        }
        cipher.doFinal(raw, GCM_IV_BYTES, raw.size - GCM_IV_BYTES).takeIf { it.size == KEY_SIZE }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val PREFS = "leylink_identity"
        const val PREF_SEED = "ed25519_seed_wrapped"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "leylink_seed_wrapping_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_BITS = 256
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64
    }
}
