package de.tbrbd.onradiotv.airplay

/** Thin wrapper around Apple's open-source ALAC reference encoder (vendored
 * under app/src/main/cpp/alac/, built via JNI - see alac_jni.cpp). One
 * instance encodes a single, fixed-size (`framesPerPacket`) stream of
 * interleaved 16-bit PCM audio into Apple Lossless frames, one call per
 * RTP packet's worth of audio - see RaopClient, which is the only caller. */
class AlacEncoder(sampleRate: Int, private val channels: Int, framesPerPacket: Int) {
    private val handle = nativeCreate(sampleRate, channels, framesPerPacket)
    private val sampleRateForEncode = sampleRate

    /** pcmLittleEndian must be exactly framesPerPacket * channels * 2 bytes
     * (one full, unpadded frame) - the caller (RaopClient) already chunks
     * decoded PCM into fixed-size pieces before calling this. */
    fun encode(pcmLittleEndian: ByteArray): ByteArray = nativeEncode(handle, pcmLittleEndian, sampleRateForEncode, channels)

    fun close() = nativeDestroy(handle)

    private external fun nativeCreate(sampleRate: Int, channels: Int, framesPerPacket: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEncode(handle: Long, pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray

    companion object {
        init {
            System.loadLibrary("alacjni")
        }
    }
}
