// Thin JNI wrapper around Apple's open-source ALAC reference encoder
// (vendored under alac/) - RaopClient needs actual Apple Lossless frames,
// not raw PCM, because real AirPlay 1 receivers (confirmed: a Denon
// AVR-X2000) accept a plain-PCM SDP announcement without complaint but
// then produce no audible output, strongly suggesting their RAOP decode
// path is hardwired to ALAC for payload type 96 regardless of what the
// SDP claims.
#include <jni.h>
#include <cstdint>
#include <cstring>
#include <vector>

#include "alac/ALACEncoder.h"
#include "alac/ALACAudioTypes.h"

namespace {

AudioFormatDescription MakeFormat(jint sampleRate, jint channels, jint framesPerPacket, bool isAlacOutput) {
    AudioFormatDescription format = {};
    format.mSampleRate = sampleRate;
    format.mChannelsPerFrame = channels;
    if (isAlacOutput) {
        format.mFormatID = kALACFormatAppleLossless;
        format.mFormatFlags = 1; // kTestFormatFlag_16BitSourceData - see InitializeEncoder()'s switch
        format.mFramesPerPacket = framesPerPacket;
        // VBR output - these are meaningless for compressed data (mirrors convert-utility's SetOutputFormat()).
        format.mBytesPerPacket = format.mBytesPerFrame = format.mBitsPerChannel = format.mReserved = 0;
    } else {
        format.mFormatID = kALACFormatLinearPCM;
        format.mFormatFlags = kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked; // native (little) endian
        format.mBitsPerChannel = 16;
        format.mFramesPerPacket = 1;
        format.mBytesPerFrame = format.mBytesPerPacket = (16 >> 3) * channels;
    }
    return format;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_tbrbd_onradiotv_airplay_AlacEncoder_nativeCreate(JNIEnv *, jobject, jint sampleRate, jint channels, jint framesPerPacket) {
    auto *encoder = new ALACEncoder();
    encoder->SetFrameSize(static_cast<uint32_t>(framesPerPacket));
    AudioFormatDescription outputFormat = MakeFormat(sampleRate, channels, framesPerPacket, true);
    encoder->InitializeEncoder(outputFormat);
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT void JNICALL
Java_de_tbrbd_onradiotv_airplay_AlacEncoder_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<ALACEncoder *>(handle);
}

// pcm: interleaved 16-bit little-endian samples, exactly framesPerPacket*channels*2 bytes
// (the caller always supplies a full frame - RaopClient already chunks its
// decoded PCM into fixed FRAMES_PER_PACKET-sized pieces before calling this).
JNIEXPORT jbyteArray JNICALL
Java_de_tbrbd_onradiotv_airplay_AlacEncoder_nativeEncode(JNIEnv *env, jobject, jlong handle, jbyteArray pcm, jint sampleRate, jint channels) {
    auto *encoder = reinterpret_cast<ALACEncoder *>(handle);
    jsize pcmLength = env->GetArrayLength(pcm);

    AudioFormatDescription inputFormat = MakeFormat(sampleRate, channels, 1, false);

    jbyte *pcmBytes = env->GetByteArrayElements(pcm, nullptr);
    std::vector<uint8_t> outBuffer(static_cast<size_t>(pcmLength) + kALACMaxEscapeHeaderBytes);
    int32_t ioNumBytes = pcmLength;
    // Encode()'s 2nd AudioFormatDescription parameter is read nowhere inside
    // it (confirmed against ALACEncoder.cpp and how Apple's own
    // convert-utility calls this - it passes the same struct twice), so
    // reusing inputFormat here matches the reference usage exactly.
    encoder->Encode(inputFormat, inputFormat, reinterpret_cast<unsigned char *>(pcmBytes), outBuffer.data(), &ioNumBytes);
    env->ReleaseByteArrayElements(pcm, pcmBytes, JNI_ABORT);

    jbyteArray result = env->NewByteArray(ioNumBytes);
    env->SetByteArrayRegion(result, 0, ioNumBytes, reinterpret_cast<jbyte *>(outBuffer.data()));
    return result;
}

} // extern "C"
