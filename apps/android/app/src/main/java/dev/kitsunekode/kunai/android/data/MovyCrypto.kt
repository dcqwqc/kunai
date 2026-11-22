package dev.kitsunekode.kunai.android.data

import java.util.Base64

/**
 * Direct Kotlin port of Kunai's packages/providers/src/movy/streamcrypto.ts.
 * Retains the original 32-bit overflow, unsigned modulo and sparse KDF rules.
 * No JNI, WebView, Bun or external machine is needed to resolve sources.
 */
internal object MovyCrypto {
    private const val GOLDEN = -0x61c88647 // 0x9e3779b9
    private val SHA256_K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b,
        0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c
    )
    private const val FNV = 0x01000193
    private const val FNV_SEED = -0x7ee3623b // 0x811c9dc5
    private const val FMIX_A = -0x7a143595 // 0x85ebca6b
    private const val FMIX_B = -0x3d4d51cb // 0xc2b2ae35

    private fun fmix(value: Int): Int {
        var x = value
        x = x xor (x ushr 16)
        x *= FMIX_A
        x = x xor (x ushr 13)
        x *= FMIX_B
        return x xor (x ushr 16)
    }

    private fun keyedFnv(value: String): Int {
        var state = FNV_SEED
        for (char in value) state = (state xor char.code) * FNV
        return fmix(state)
    }
    private fun unsignedMod(value: Int, mod: Int): Int =
        ((value.toLong() and 0xffffffffL) % mod).toInt()

    private data class State(var acc: Int, val values: MutableMap<Int, Int>)
    private fun initialize(seed: String, mediaId: Int): State {
        require(seed.isNotBlank() && seed.length <= 240)
        val sbox = mutableMapOf<Int, Int>()
        var n = fmix(keyedFnv(seed) xor fmix(mediaId xor GOLDEN))
        for (i in 0 until 8) {
            val slot = unsignedMod(n, 61)
            n = Integer.rotateLeft(n + GOLDEN, 7 + (i and 7))
            sbox[slot] = n xor fmix(n)
            n = fmix(n + slot)
        }
        return State(fmix(-0x5a5a5a5b xor n), sbox)
    }
    private fun next(state: State, counter: Int): Int {
        val slot = unsignedMod(state.acc, 61)
        val inTable = if (state.values.containsKey(slot)) -1 else 0
        val r = state.values[slot] ?: 0
        val c = GOLDEN * (counter + 1)
        val mixed = r xor c
        val b = (state.acc xor mixed) or (state.acc and mixed and inTable)
        val word = fmix(
            (Integer.rotateLeft(b + state.acc, slot and 31) xor
                Integer.rotateLeft(state.acc, (slot * 7) and 31)) + GOLDEN
        )
        state.values[slot] = word
        state.acc = word
        return word
    }

    fun decrypt(payload: String, seed: String, mediaId: Int): String {
        require(payload.length in 8..300_000)
        val cipher = Base64.getUrlDecoder().decode(payload)
        require(cipher.size in 5..225_000)
        val plain = ByteArray(cipher.size)
        val state = initialize(seed, mediaId)
        var position = 0
        var count = 0
        while (position < cipher.size) {
            val word = next(state, count++)
            for (i in 0 until 4) {
                if (position >= cipher.size) break
                plain[position] =
                    (cipher[position].toInt() xor ((word ushr (8 * i)) and 255)).toByte()
                position++
            }
        }
        require(plain[0] == 109.toByte() && plain[1] == 118.toByte() &&
            plain[2] == 109.toByte() && plain[3] == 49.toByte()) {
            "Movy source verification failed"
        }
        return String(plain, 4, plain.size - 4, Charsets.UTF_8)
    }
}
