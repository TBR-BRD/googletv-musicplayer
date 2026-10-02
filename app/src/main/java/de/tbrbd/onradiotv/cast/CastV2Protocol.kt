package de.tbrbd.onradiotv.cast

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Hand-rolled wire format for Google's `CastMessage` protobuf (the
 * envelope every CASTV2 frame is wrapped in) - just the handful of string
 * fields this app actually needs, encoded/decoded directly rather than
 * pulling in a full protobuf runtime for one tiny fixed schema:
 *
 *   message CastMessage {
 *     required ProtocolVersion protocol_version = 1; // 0 = CASTV2_1_0
 *     required string source_id = 2;
 *     required string destination_id = 3;
 *     required string namespace = 4;
 *     required PayloadType payload_type = 5;          // 0 = STRING
 *     optional string payload_utf8 = 6;
 *   }
 *
 * Framing on the wire is this message prefixed by its own length as a
 * 4-byte big-endian integer - both documented, stable parts of the
 * protocol every CASTV2 client (pychromecast, node-castv2, ...) relies on.
 */

data class DecodedCastMessage(val namespace: String, val sourceId: String, val destinationId: String, val payload: String)

private fun writeVarint(value: Long, out: ByteArrayOutputStream) {
    var v = value
    while (true) {
        if (v and 0x7FL.inv() == 0L) {
            out.write(v.toInt())
            return
        }
        out.write(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
}

private fun writeTag(fieldNumber: Int, wireType: Int, out: ByteArrayOutputStream) {
    writeVarint(((fieldNumber shl 3) or wireType).toLong(), out)
}

private fun writeStringField(fieldNumber: Int, value: String, out: ByteArrayOutputStream) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeTag(fieldNumber, 2, out)
    writeVarint(bytes.size.toLong(), out)
    out.write(bytes)
}

private fun writeVarintField(fieldNumber: Int, value: Int, out: ByteArrayOutputStream) {
    writeTag(fieldNumber, 0, out)
    writeVarint(value.toLong(), out)
}

fun encodeCastMessage(namespace: String, sourceId: String, destinationId: String, payloadJson: String): ByteArray {
    val out = ByteArrayOutputStream()
    writeVarintField(1, 0, out) // protocol_version = CASTV2_1_0
    writeStringField(2, sourceId, out)
    writeStringField(3, destinationId, out)
    writeStringField(4, namespace, out)
    writeVarintField(5, 0, out) // payload_type = STRING
    writeStringField(6, payloadJson, out)
    return out.toByteArray()
}

fun decodeCastMessage(bytes: ByteArray): DecodedCastMessage {
    var pos = 0
    var sourceId = ""
    var destinationId = ""
    var namespace = ""
    var payload = ""

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    while (pos < bytes.size) {
        val tag = readVarint()
        val fieldNumber = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        when (wireType) {
            0 -> readVarint()
            2 -> {
                val length = readVarint().toInt()
                val str = String(bytes, pos, length, Charsets.UTF_8)
                pos += length
                when (fieldNumber) {
                    2 -> sourceId = str
                    3 -> destinationId = str
                    4 -> namespace = str
                    6 -> payload = str
                }
            }
            else -> error("Unexpected CastMessage wire type $wireType")
        }
    }
    return DecodedCastMessage(namespace = namespace, sourceId = sourceId, destinationId = destinationId, payload = payload)
}

fun writeFramedMessage(output: OutputStream, body: ByteArray) {
    val header = byteArrayOf(
        (body.size ushr 24).toByte(),
        (body.size ushr 16).toByte(),
        (body.size ushr 8).toByte(),
        body.size.toByte(),
    )
    output.write(header)
    output.write(body)
    output.flush()
}

/** Returns null on clean EOF (socket closed), as opposed to throwing. */
fun readFramedMessage(input: InputStream): ByteArray? {
    val header = ByteArray(4)
    if (!readFully(input, header)) return null
    val length = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)
    val body = ByteArray(length)
    if (!readFully(input, body)) return null
    return body
}

private fun readFully(input: InputStream, dest: ByteArray): Boolean {
    var offset = 0
    while (offset < dest.size) {
        val read = input.read(dest, offset, dest.size - offset)
        if (read < 0) return false
        offset += read
    }
    return true
}
