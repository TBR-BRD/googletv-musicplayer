package de.tbrbd.onradiotv.airplay

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.random.Random

private const val TAG = "RaopClient"

// 352 frames/packet is RAOP's conventional chunk size - at 44100 Hz stereo
// 16-bit PCM that's 352*4 = 1408 bytes of audio per RTP packet, matching
// what real AirPlay senders (including Apple's own) use.
private const val FRAMES_PER_PACKET = 352
private const val SAMPLE_RATE = 44100
private const val CHANNELS = 2
private const val BYTES_PER_FRAME = CHANNELS * 2 // 16-bit samples

// Deliberately quiet - see the safety comment on RaopClient.connect(). The
// viewer can turn it up afterwards; starting too loud is the unrecoverable
// mistake, starting too quiet is a one-button fix. Not private: also used
// as AirPlayRendererService's initial tracked volumePercent, so the first
// +/- adjustment starts from what the device was actually just set to.
const val INITIAL_SAFE_VOLUME_PERCENT = 15

/** A from-scratch, deliberately minimal RAOP (classic AirPlay 1 audio)
 * sender: RTSP handshake (OPTIONS/ANNOUNCE/SETUP/RECORD/SET_PARAMETER/
 * TEARDOWN) plus RTP audio streaming, built the same way UpnpRendererService
 * and CastV2Client were - directly against the documented/reverse engineered
 * wire protocol (the one `shairport`, `pyatv`'s RAOP path, etc. all speak),
 * no vendor SDK involved since there isn't an official Android one anyway.
 *
 * First-cut simplifications, called out because they're the most likely
 * things to need revisiting against a real device:
 *  - No RSA/AES encryption (announces and streams in the clear) - plenty of
 *    third-party RAOP receivers accept this, but not all do.
 *  - Raw PCM (`L16/44100/2`), not Apple Lossless - far simpler to produce
 *    (no ALAC encoder needed) but not every receiver advertises/accepts the
 *    PCM codec option, since ALAC is what Apple's own senders always use.
 *  - No timing-port NTP-style exchange - fine for a short-lived session,
 *    but long-running playback may drift or stall on stricter receivers
 *    that expect it.
 * Audio comes from decoding the station's HTTP stream directly via
 * MediaExtractor/MediaCodec (independent of the app's main ExoPlayer
 * instance), assuming a 44.1kHz stereo source - true for the large
 * majority of these radio streams.
 */
class RaopClient(private val host: String, private val port: Int) {
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private var reader: BufferedReader? = null
    private var cseq = 0
    private val sessionPath = Random.nextInt(100_000, 999_999).toString()
    private var rtspSession: String? = null
    private val clientInstance = UUID.randomUUID().toString().replace("-", "").uppercase().take(16)

    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var timingThread: Thread? = null
    private var serverAddress: InetAddress? = null
    private var serverAudioPort: Int = 0

    private var seq = Random.nextInt(0, 0xFFFF)
    private var rtpTimestamp = Random.nextInt(0, Int.MAX_VALUE)
    private val ssrc = Random.nextInt()
    private var firstPacketSent = false

    @Volatile private var streaming = false
    private var decodeThread: Thread? = null
    private val crypto = RaopCrypto()

    // Real RAOP receivers request retransmission of audio packets they
    // believe are missing (e.g. from ordinary UDP reordering) via the
    // control port, and - per real-world receiver behaviour (confirmed
    // against a Denon AVR-X2000: a technically correct, continuous,
    // encrypted ALAC stream with zero errors still produced no audible
    // output at all) - some appear to withhold playback entirely until
    // those requests are satisfied, rather than merely degrading quality.
    // This keeps a short ring buffer of recently sent packets so such
    // requests can be answered.
    private val resendHistorySize = 512
    private val resendHistory = arrayOfNulls<ByteArray>(resendHistorySize)
    private val resendHistorySeq = IntArray(resendHistorySize) { -1 }
    private val resendHistoryLock = Any()
    private var controlListenerThread: Thread? = null

    // All RTSP request() calls (including ones triggered from the UI/Main
    // thread, e.g. a volume adjustment) are serialized through here so the
    // actual socket write/read never runs on Main - mirrors CastV2Client's
    // writerExecutor, which fixed the exact same NetworkOnMainThreadException
    // class of bug there.
    private val rtspExecutor = Executors.newSingleThreadExecutor()

    /** Blocking - caller must run this off the main thread. Performs the
     * full RTSP handshake (OPTIONS -> ANNOUNCE -> SETUP -> RECORD); throws
     * on any step the receiver rejects. */
    fun connect() {
        val s = Socket(InetAddress.getByName(host), port)
        socket = s
        output = s.getOutputStream()
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))

        request("OPTIONS", "*")
        announce()
        setup()
        // Critical safety step: activating a receiver's AirPlay input can
        // make it jump to whatever volume that input was last left at -
        // observed live jumping straight to maximum on a real device, with
        // nothing in our own code having asked for that. Explicitly setting
        // a low, known volume before RECORD (which is what actually starts
        // the input/triggers this) means we're never the reason a device
        // blasts at full volume, regardless of what it would have defaulted
        // to on its own.
        setVolume(INITIAL_SAFE_VOLUME_PERCENT)
        record()
    }

    fun setVolume(percent: Int) {
        // RAOP volume is in dB: -30.0 (practical minimum) to 0.0 (max), or
        // -144.0 for mute - there's no 0-100 scale on the wire.
        val db = if (percent <= 0) -144.0 else -30.0 + (percent.coerceIn(0, 100) / 100.0) * 30.0
        try {
            request(
                "SET_PARAMETER",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = "volume: $db\r\n".toByteArray(Charsets.US_ASCII),
            )
        } catch (exc: Exception) {
            Log.w(TAG, "setVolume failed: $exc")
        }
    }

    fun startStreaming(streamUrl: String) {
        streaming = true
        decodeThread = thread(name = "RaopDecode") { decodeAndStream(streamUrl) }
    }

    fun stopStreaming() {
        streaming = false
        decodeThread?.interrupt()
    }

    fun disconnect() {
        stopStreaming()
        try {
            request("TEARDOWN")
        } catch (_: Exception) {
            // Best-effort - socket may already be unusable.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Nothing useful to do if closing fails.
        }
        try {
            audioSocket?.close()
        } catch (_: Exception) {
            // Nothing useful to do if closing fails.
        }
        try {
            controlSocket?.close()
            timingSocket?.close()
        } catch (_: Exception) {
            // Nothing useful to do if closing fails.
        }
        rtspExecutor.shutdown()
    }

    // --- RTSP handshake steps -------------------------------------------

    private fun announce() {
        val localIp = localAddress()
        val sdp = buildString {
            append("v=0\r\n")
            append("o=iTunes 0 0 IN IP4 $localIp\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 $host\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            // Real AirPlay 1 receivers (confirmed against a Denon AVR-X2000)
            // accept a plain-PCM ("L16") SDP for payload type 96 without
            // complaint during the handshake, then produce no audible
            // output at all - their RAOP decode path is apparently
            // hardwired to Apple Lossless for pt 96 regardless of what the
            // SDP claims. The fmtp numbers are ALAC's fixed encoder
            // defaults for this frame size/depth (not independently
            // configurable - see aglib.h's PB0/MB0/KB0/MAX_RUN_DEFAULT).
            append("a=rtpmap:96 AppleLossless\r\n")
            append(
                "a=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 $CHANNELS 255 0 0 $SAMPLE_RATE\r\n",
            )
            append("a=rsaaeskey:${crypto.rsaAesKeyBase64}\r\n")
            append("a=aesiv:${crypto.aesIvBase64}\r\n")
        }
        request(
            "ANNOUNCE",
            headers = mapOf("Content-Type" to "application/sdp"),
            body = sdp.toByteArray(Charsets.US_ASCII),
        )
    }

    private fun setup() {
        audioSocket = DatagramSocket(0)
        controlSocket = DatagramSocket(0)
        timingSocket = DatagramSocket(0)
        // The receiver actively probes this port for clock sync once
        // streaming is under way (sometimes even before RECORD completes) -
        // a real device was observed taking ~30s to fail RECORD with a 500,
        // consistent with it waiting out a timeout for a timing reply that
        // never came before giving up on the session. A bare, not fully
        // NTP-accurate responder (see respondToTimingRequest()) satisfies
        // that liveness check even without full clock-sync precision, which
        // doesn't matter for audio-only playback anyway.
        startTimingResponder()

        val response = request(
            "SETUP",
            headers = mapOf(
                "Transport" to (
                    "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
                        "control_port=${controlSocket!!.localPort};timing_port=${timingSocket!!.localPort}"
                    ),
            ),
        )
        rtspSession = response.headers["Session"]
        val transport = response.headers["Transport"] ?: error("SETUP response had no Transport header")
        serverAudioPort = Regex("server_port=(\\d+)").find(transport)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("SETUP response's Transport header had no server_port")
        serverAddress = InetAddress.getByName(host)
        Log.d(TAG, "SETUP ok: session=$rtspSession serverAudioPort=$serverAudioPort headers=${response.headers}")
        startControlListener()
    }

    // --- Retransmission (control channel) ---------------------------------
    //
    // A receiver that believes it's missing a packet (ordinary UDP
    // reordering/loss, not necessarily a real gap) sends an 8-byte request
    // on the control port: 0x80, 0x55|0x80, our-own-seq(2), missing-seq(2),
    // count(2) - all big-endian. The reply goes out on the AUDIO port/
    // socket, each missing packet re-sent as a 4-byte resend header
    // (0x80, 0x56|0x80, seq(2)) immediately followed by that packet's
    // original 12-byte RTP header + payload, unchanged. (Verified against
    // mikebrady/shairport-sync's rtp.c: rtp_request_resend() builds the
    // request exactly this way, and audio_receiver_thread() strips exactly
    // a 4-byte prefix off an incoming type-0x56 packet before treating the
    // remainder as a normal RTP audio packet.)

    private fun startControlListener() {
        val sock = controlSocket ?: return
        controlListenerThread = thread(name = "RaopControl") {
            val buffer = ByteArray(32)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    handleControlPacket(packet.data, packet.length)
                } catch (exc: Exception) {
                    break // Socket closed (disconnect()) or similar - stop quietly.
                }
            }
        }
    }

    private fun handleControlPacket(data: ByteArray, length: Int) {
        if (length < 8) return
        val type = data[1].toInt() and 0x7F
        if (type != 0x55) return // only resend requests are meaningful here
        val missingSeq = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val count = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val address = serverAddress ?: return
        for (i in 0 until count) {
            val seqToResend = (missingSeq + i) and 0xFFFF
            val original = synchronized(resendHistoryLock) {
                val slot = seqToResend % resendHistorySize
                if (resendHistorySeq[slot] == seqToResend) resendHistory[slot] else null
            } ?: continue
            val resendPacket = ByteArray(4 + original.size)
            resendPacket[0] = 0x80.toByte()
            resendPacket[1] = 0xD6.toByte() // 0x56 | 0x80
            resendPacket[2] = (seqToResend ushr 8).toByte()
            resendPacket[3] = seqToResend.toByte()
            System.arraycopy(original, 0, resendPacket, 4, original.size)
            try {
                audioSocket?.send(DatagramPacket(resendPacket, resendPacket.size, address, serverAudioPort))
            } catch (exc: Exception) {
                Log.w(TAG, "Resend of seq $seqToResend failed: $exc")
            }
        }
    }

    // --- Timing (clock-sync) responder ------------------------------------
    //
    // RAOP's timing exchange is a simplified NTP-style three-timestamp
    // round trip, all on 32-byte UDP packets:
    //   byte 0:    0x80
    //   byte 1:    0xD2 = request (received), 0xD3 = response (sent back)
    //   bytes 2-3: sequence (echoed back unchanged)
    //   bytes 4-7: unused/zero
    //   bytes 8-15:  "Reference"/"Original" timestamp - the request's own
    //                "Transmit" timestamp, echoed back in the response
    //   bytes 16-23: "Receive" timestamp - when *we* received the request
    //   bytes 24-31: "Transmit" timestamp - when *we* send the response
    // Not aiming for tight clock-sync precision here (irrelevant for
    // audio-only playback) - just answering at all is what keeps the
    // receiver from deciding the client went away.

    private fun startTimingResponder() {
        val sock = timingSocket ?: return
        timingThread = thread(name = "RaopTiming") {
            val buffer = ByteArray(32)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    respondToTimingRequest(packet)
                } catch (exc: Exception) {
                    break // Socket closed (disconnect()) or similar - stop quietly.
                }
            }
        }
    }

    private fun respondToTimingRequest(requestPacket: DatagramPacket) {
        val requestBytes = requestPacket.data
        val receiveTimestamp = ntpNow()
        val reply = ByteArray(32)
        reply[0] = 0x80.toByte()
        reply[1] = 0xD3.toByte()
        reply[2] = requestBytes[2]
        reply[3] = requestBytes[3]
        System.arraycopy(requestBytes, 24, reply, 8, 8) // their Transmit -> our Original
        writeNtpTimestamp(reply, 16, receiveTimestamp)
        writeNtpTimestamp(reply, 24, ntpNow())
        try {
            timingSocket?.send(DatagramPacket(reply, reply.size, requestPacket.address, requestPacket.port))
        } catch (exc: Exception) {
            Log.w(TAG, "Timing response send failed: $exc")
        }
    }

    private fun ntpNow(): Long {
        val secondsSince1900 = (System.currentTimeMillis() / 1000L) + 2_208_988_800L
        val fractionMillis = System.currentTimeMillis() % 1000L
        val fraction = ((fractionMillis.toDouble() / 1000.0) * 4_294_967_296.0).toLong()
        return (secondsSince1900 shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun writeNtpTimestamp(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buffer[offset + i] = (value ushr (56 - i * 8)).toByte()
        }
    }

    private fun record() {
        request(
            "RECORD",
            headers = mapOf(
                "Range" to "npt=0-",
                "RTP-Info" to "seq=$seq;rtptime=$rtpTimestamp",
            ),
        )
    }

    // --- RTSP plumbing ----------------------------------------------------

    private data class RtspResponse(val statusLine: String, val headers: Map<String, String>)

    private fun request(
        method: String,
        uri: String = "rtsp://${localAddress()}/$sessionPath",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): RtspResponse {
        // Submitted to rtspExecutor and blocked on, rather than run inline -
        // request() is called both from background threads (connect()'s own
        // sequence) and from whatever thread the caller happens to be on
        // (TvViewModel's volume adjustment runs on Main), and raw socket I/O
        // on Main throws NetworkOnMainThreadException.
        try {
            return rtspExecutor.submit<RtspResponse> { requestBlocking(method, uri, headers, body) }.get()
        } catch (exc: java.util.concurrent.ExecutionException) {
            // Unwrap so callers see the real exception (RuntimeException with
            // the RTSP failure message, SocketException, etc.) rather than
            // ExecutionException's generic wrapper.
            throw (exc.cause as? Exception) ?: exc
        }
    }

    private fun requestBlocking(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): RtspResponse {
        val out = output ?: error("Not connected")
        val allHeaders = LinkedHashMap<String, String>()
        allHeaders["CSeq"] = (++cseq).toString()
        allHeaders["User-Agent"] = "RadioplayerTV/1.0"
        allHeaders["Client-Instance"] = clientInstance
        rtspSession?.let { allHeaders["Session"] = it }
        allHeaders.putAll(headers)
        if (body != null) allHeaders["Content-Length"] = body.size.toString()

        val requestText = buildString {
            append("$method $uri RTSP/1.0\r\n")
            for ((key, value) in allHeaders) append("$key: $value\r\n")
            append("\r\n")
        }
        Log.d(TAG, "-> $method $uri headers=$allHeaders")
        out.write(requestText.toByteArray(Charsets.US_ASCII))
        if (body != null) out.write(body)
        out.flush()

        val response = readResponse()
        if (!response.statusLine.contains(" 200 ")) {
            throw RuntimeException("$method failed: ${response.statusLine}")
        }
        return response
    }

    private fun readResponse(): RtspResponse {
        val r = reader ?: error("Not connected")
        val statusLine = r.readLine() ?: error("Connection closed while waiting for a response")
        // Case-insensitive: RTSP header names are case-insensitive on the
        // wire, and a lookup like headers["Session"] would silently miss a
        // "session" the server actually sent, leaving rtspSession unset and
        // RECORD unable to identify its session - a likely cause of RECORD
        // failing right after a successful SETUP.
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        while (true) {
            val line = r.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        // We never send a request that gets a response with a body back
        // (ANNOUNCE/SET_PARAMETER bodies are client -> server only), so
        // there's nothing further to read here.
        return RtspResponse(statusLine, headers)
    }

    private fun localAddress(): String = socket?.localAddress?.hostAddress ?: "0.0.0.0"

    // --- Audio decode + RTP streaming -------------------------------------

    private fun decodeAndStream(streamUrl: String) {
        Log.d(TAG, "decodeAndStream starting for $streamUrl")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var alacEncoder: AlacEncoder? = null
        try {
            extractor.setDataSource(streamUrl)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    format = candidate
                    break
                }
            }
            if (audioTrack < 0 || format == null) {
                Log.w(TAG, "No audio track found in $streamUrl")
                return
            }
            Log.d(TAG, "decodeAndStream: selected track format=$format")
            extractor.selectTrack(audioTrack)

            val mediaCodec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = mediaCodec
            mediaCodec.configure(format, null, null, 0)
            mediaCodec.start()
            var packetsSent = 0L
            val encoder = AlacEncoder(SAMPLE_RATE, CHANNELS, FRAMES_PER_PACKET)
            alacEncoder = encoder

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEnd = false
            val pcmBuffer = ByteArrayOutputStream()
            val startNanos = System.nanoTime()
            var framesSent = 0L

            while (streaming) {
                if (!sawInputEnd) {
                    val inputIndex = mediaCodec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = mediaCodec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outputIndex >= 0) {
                    val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.get(chunk)
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    pcmBuffer.write(chunk)

                    val packetBytes = FRAMES_PER_PACKET * BYTES_PER_FRAME
                    while (pcmBuffer.size() >= packetBytes && streaming) {
                        val all = pcmBuffer.toByteArray()
                        val packet = all.copyOfRange(0, packetBytes)
                        pcmBuffer.reset()
                        pcmBuffer.write(all, packetBytes, all.size - packetBytes)

                        sendAudioPacket(encoder.encode(packet))
                        framesSent += FRAMES_PER_PACKET
                        packetsSent++
                        if (packetsSent == 1L || packetsSent % 500 == 0L) {
                            Log.d(TAG, "decodeAndStream: sent $packetsSent audio packets so far")
                        }

                        // Real-time pacing: without this the whole stream
                        // would decode and transmit as fast as the CPU
                        // allows, far faster than the receiver can play it.
                        val targetNanos = (framesSent * 1_000_000_000L) / SAMPLE_RATE
                        val elapsedNanos = System.nanoTime() - startNanos
                        val sleepNanos = targetNanos - elapsedNanos
                        if (sleepNanos > 0) {
                            Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                        }
                    }
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (exc: Exception) {
            Log.w(TAG, "decodeAndStream ended with exception (streaming=$streaming): $exc", exc)
        } finally {
            Log.d(TAG, "decodeAndStream exiting (streaming=$streaming)")
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
                // Nothing useful to do if releasing fails.
            }
            alacEncoder?.close()
            extractor.release()
        }
    }

    private fun sendAudioPacket(alacPayload: ByteArray) {
        val encryptedPayload = crypto.encryptPacket(alacPayload)
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // V=2, P=0, X=0, CC=0
        header[1] = (0x60 or (if (!firstPacketSent) 0x80 else 0)).toByte() // M bit set once, PT=96
        firstPacketSent = true
        val packetSeq = seq
        header[2] = (packetSeq ushr 8).toByte()
        header[3] = packetSeq.toByte()
        seq = (seq + 1) and 0xFFFF
        header[4] = (rtpTimestamp ushr 24).toByte()
        header[5] = (rtpTimestamp ushr 16).toByte()
        header[6] = (rtpTimestamp ushr 8).toByte()
        header[7] = rtpTimestamp.toByte()
        rtpTimestamp += FRAMES_PER_PACKET
        header[8] = (ssrc ushr 24).toByte()
        header[9] = (ssrc ushr 16).toByte()
        header[10] = (ssrc ushr 8).toByte()
        header[11] = ssrc.toByte()

        val packetBytes = header + encryptedPayload
        synchronized(resendHistoryLock) {
            val slot = packetSeq % resendHistorySize
            resendHistory[slot] = packetBytes
            resendHistorySeq[slot] = packetSeq
        }
        val address = serverAddress ?: return
        try {
            audioSocket?.send(DatagramPacket(packetBytes, packetBytes.size, address, serverAudioPort))
        } catch (exc: Exception) {
            if (streaming) Log.w(TAG, "Sending audio packet failed: $exc")
        }
    }
}
