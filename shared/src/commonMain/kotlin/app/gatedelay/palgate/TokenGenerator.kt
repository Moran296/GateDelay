package app.gatedelay.palgate

/** Token types as returned by PalGate device linking (`secondary` field). */
object TokenType {
    const val SMS = 0
    const val PRIMARY = 1   // first linked device
    const val SECONDARY = 2 // second linked device
}

/**
 * Port of pylgate's `token_generator.py`: derives the short-lived `x-bt-token` header.
 * Each derived token is only valid for a few seconds, so generate it right before a request.
 */
object TokenGenerator {
    private const val TOKEN_SIZE = 23
    const val TIMESTAMP_OFFSET = 2

    private val T_C_KEY = byteArrayOf(
        0xfa.toByte(), 0xd3.toByte(), 0x25, 0x72, 0x81.toByte(), 0x29, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0xb4.toByte(), 0x5a, 0x65,
    )

    /**
     * @param sessionToken 16-byte base token from device linking
     * @param phoneNumber  user id (phone in international format, e.g. 972501234567)
     * @param tokenType    [TokenType]
     * @param timestampSec seconds since epoch
     */
    fun generate(
        sessionToken: ByteArray,
        phoneNumber: Long,
        tokenType: Int,
        timestampSec: Long,
        timestampOffset: Int = TIMESTAMP_OFFSET,
    ): String {
        require(sessionToken.size == PalgateAes.BLOCK_SIZE) { "Invalid session token" }

        val step2Key = step1(sessionToken, phoneNumber)
        val step2Result = step2(step2Key, timestampSec + timestampOffset)

        val result = ByteArray(TOKEN_SIZE)
        result[0] = when (tokenType) {
            TokenType.SMS -> 0x01
            TokenType.PRIMARY -> 0x11
            TokenType.SECONDARY -> 0x21
            else -> throw IllegalArgumentException("unknown token type: $tokenType")
        }
        // bytes 1..6: phone number, 48-bit big endian
        for (i in 0 until 6) result[1 + i] = (phoneNumber shr (8 * (5 - i))).toByte()
        step2Result.copyInto(result, destinationOffset = 7)
        return result.toHex()
    }

    private fun step1(sessionToken: ByteArray, phoneNumber: Long): ByteArray {
        val key = T_C_KEY.copyOf()
        for (i in 0 until 6) key[6 + i] = (phoneNumber shr (8 * (5 - i))).toByte()
        return PalgateAes.process(sessionToken, key, isEncrypt = true)
    }

    private fun step2(step1Result: ByteArray, timestamp: Long): ByteArray {
        val state = ByteArray(PalgateAes.BLOCK_SIZE)
        state[1] = 0x0a
        state[2] = 0x0a
        for (i in 0 until 4) state[10 + i] = (timestamp shr (8 * (3 - i))).toByte()
        return PalgateAes.process(state, step1Result, isEncrypt = false)
    }
}

private const val HEX = "0123456789ABCDEF"

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xff
        append(HEX[v shr 4]); append(HEX[v and 0x0f])
    }
}

internal fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0 && clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Not a hex string" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
