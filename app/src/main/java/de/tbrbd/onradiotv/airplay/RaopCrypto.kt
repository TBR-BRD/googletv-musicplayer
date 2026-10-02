package de.tbrbd.onradiotv.airplay

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Classic RAOP (AirPlay 1) encrypts its RTP audio payload with AES-128-CBC,
 * with the per-session AES key itself RSA-encrypted using Apple's
 * long-published RAOP public key - not a secret, just a fixed protocol
 * parameter every open-source AirPlay implementation (shairport-sync among
 * them, where this copy was verified against) embeds verbatim. Several real
 * receivers (confirmed: a Denon AVR-X2000) accept an unencrypted
 * ANNOUNCE/SETUP/RECORD handshake without complaint, then simply never
 * produce audio - their decode path apparently assumes an encrypted stream
 * unconditionally regardless of what was negotiated. */
class RaopCrypto {
    private val aesKey = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val aesIv = ByteArray(16).also { SecureRandom().nextBytes(it) }

    val rsaAesKeyBase64: String
    val aesIvBase64: String = encodeNoPadding(aesIv)

    init {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, RAOP_PUBLIC_KEY)
        rsaAesKeyBase64 = encodeNoPadding(cipher.doFinal(aesKey))
    }

    /** Encrypts in place and returns the same array: whole 16-byte blocks
     * are AES-CBC encrypted with the IV reset to the session IV on every
     * call (RAOP's packets are independently decryptable so UDP loss or
     * reordering can't desync the cipher state); any trailing partial
     * block is left in the clear, per the protocol's convention. */
    fun encryptPacket(payload: ByteArray): ByteArray {
        val fullBlockBytes = (payload.size / 16) * 16
        if (fullBlockBytes == 0) return payload
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val encrypted = cipher.doFinal(payload, 0, fullBlockBytes)
        System.arraycopy(encrypted, 0, payload, 0, fullBlockBytes)
        return payload
    }

    companion object {
        private fun encodeNoPadding(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(bytes).trimEnd('=')

        // Verified 2026-10-02 against shairport-sync's super_secret_key
        // (common.c) by deriving the public key with `openssl rsa -pubout`
        // and comparing moduli - this is the standard X.509
        // SubjectPublicKeyInfo encoding, which KeyFactory parses directly.
        private const val RAOP_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA59dE8qLieItsH1WgjrcF
RKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabW
JmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/A
Mj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf
/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcr
tij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hE
ewIDAQAB
-----END PUBLIC KEY-----"""

        private val RAOP_PUBLIC_KEY by lazy {
            val base64 = RAOP_PUBLIC_KEY_PEM.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
            val keySpec = X509EncodedKeySpec(Base64.getDecoder().decode(base64))
            KeyFactory.getInstance("RSA").generatePublic(keySpec)
        }
    }
}
