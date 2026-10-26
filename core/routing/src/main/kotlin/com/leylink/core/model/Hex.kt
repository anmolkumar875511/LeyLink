package com.leylink.core.model

internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
