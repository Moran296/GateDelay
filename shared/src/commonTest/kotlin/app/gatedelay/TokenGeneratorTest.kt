package app.gatedelay

import app.gatedelay.palgate.TokenGenerator
import app.gatedelay.palgate.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values produced by pylgate (Python reference) — see tools/ for the generator. */
class TokenGeneratorTest {
    private data class Vector(val token: String, val phone: Long, val type: Int, val ts: Long, val expected: String)

    private val vectors = listOf(
        Vector("00112233445566778899aabbccddeeff", 972501234567L, 1, 1700000000L, "1100E26D9733871BDCB0B48FEE2B045D18C4E7B206FA1C"),
        Vector("00112233445566778899aabbccddeeff", 972501234567L, 2, 1700000000L, "2100E26D9733871BDCB0B48FEE2B045D18C4E7B206FA1C"),
        Vector("00112233445566778899aabbccddeeff", 972501234567L, 0, 1700000000L, "0100E26D9733871BDCB0B48FEE2B045D18C4E7B206FA1C"),
        Vector("0f1e2d3c4b5a69788796a5b4c3d2e1f0", 972549876543L, 1, 1763193600L, "1100E2707D6B3F89B8828D7BA6072561F2805CCD55087D"),
        Vector("ffffffffffffffffffffffffffffffff", 12025550123L, 2, 1L, "210002CCC7552B5A68D265F3C519BEE9D8BA85916251CA"),
        Vector("a1b2c3d4e5f60718293a4b5c6d7e8f90", 447700900123L, 1, 1893456000L, "1100683D0D991B7C8546A20DACAEF6C95F94CCD0325584"),
    )

    @Test
    fun matchesPythonReference() {
        for (v in vectors) {
            assertEquals(v.expected, TokenGenerator.generate(v.token.hexToBytes(), v.phone, v.type, v.ts), "vector $v")
        }
    }
}
