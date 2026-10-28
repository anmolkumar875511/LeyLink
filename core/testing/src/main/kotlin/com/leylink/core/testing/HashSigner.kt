package com.leylink.core.testing

import com.leylink.core.port.Signer
import java.security.MessageDigest

/**
 * NOT cryptography. A deterministic stand-in with the same shape as Ed25519 (32-byte public key, 64-byte
 * signature, verification needs only the public key) so router tests can exercise signing, tampering and
 * key pinning on the JVM without Android. The real signer is `KeystoreEd25519Signer` in :app.
 */
class HashSigner(seed: Int) : Signer {
    private val key: ByteArray = sha("key-$seed".toByteArray())

    override fun publicKey(): ByteArray = key.copyOf()

    override fun sign(data: ByteArray): ByteArray = signature(key, data)

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        publicKey.size == 32 && signature.contentEquals(signature(publicKey, data))

    private fun signature(pk: ByteArray, data: ByteArray): ByteArray = sha(pk + data) + sha(data + pk)

    private fun sha(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
