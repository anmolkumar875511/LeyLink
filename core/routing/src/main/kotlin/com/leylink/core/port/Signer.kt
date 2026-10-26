package com.leylink.core.port

/** Ed25519 signing port. Implemented with Google Tink on Android in Stage 6. */
interface Signer {
    fun publicKey(): ByteArray

    fun sign(data: ByteArray): ByteArray

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}
