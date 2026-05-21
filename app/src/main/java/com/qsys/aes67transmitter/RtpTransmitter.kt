package com.qsys.aes67transmitter

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────
//  RtpTransmitter — שולח אודיו מהמיקרופון דרך RTP ל-Q-SYS
//  פרוטוקול: RTP/UDP, PCM L16, 48kHz, Mono/Stereo
//  Q-SYS צד: Media Stream Receiver, Protocol=RTP, Format=PCM
// ─────────────────────────────────────────────────────────────

class RtpTransmitter(
    private val destIp: String,
    private val destPort: Int,
    private val sampleRate: Int = 48000,
    private val channels: Int = 1,           // 1=Mono, 2=Stereo
    private val bitDepth: Int = 16,          // L16
    private val packetTimeMs: Int = 1,       // 1ms = AES67 standard
    private val onLevelUpdate: (Float) -> Unit = {}
) {
    companion object {
        private const val TAG = "RtpTransmitter"
        private const val RTP_VERSION = 2
        private const val RTP_PAYLOAD_PCMU = 96   // Dynamic payload for L16/48k
        private const val RTP_HEADER_SIZE = 12
    }

    // מצב
    private val isRunning = AtomicBoolean(false)
    private var txThread: Thread? = null
    private var socket: DatagramSocket? = null

    // RTP state
    private var sequenceNumber: Int = (Math.random() * 65535).toInt()
    private var timestamp: Long = (Math.random() * 0xFFFFFFFFL).toLong()
    private val ssrc: Long = (Math.random() * 0xFFFFFFFFL).toLong()

    // Audio config
    private val samplesPerPacket: Int = (sampleRate * packetTimeMs) / 1000
    private val bytesPerSample: Int = bitDepth / 8
    private val bytesPerPacket: Int = samplesPerPacket * channels * bytesPerSample
    private val channelConfig = if (channels == 1)
        AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = if (bitDepth == 16)
        AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

    // ── התחלה ──────────────────────────────────────────────
    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting RTP → $destIp:$destPort | ${sampleRate}Hz ch=$channels ${packetTimeMs}ms")
        txThread = Thread(::transmitLoop, "RtpTxThread").apply { start() }
    }

    // ── עצירה ──────────────────────────────────────────────
    fun stop() {
        isRunning.set(false)
        txThread?.join(2000)
        txThread = null
        socket?.close()
        socket = null
        Log.i(TAG, "RTP Transmitter stopped")
    }

    val running: Boolean get() = isRunning.get()

    // ── לולאת שידור ────────────────────────────────────────
    private fun transmitLoop() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufSize = maxOf(minBuf, bytesPerPacket * 4)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            isRunning.set(false)
            return
        }

        socket = DatagramSocket()
        val destAddr = InetAddress.getByName(destIp)
        val audioBuffer = ByteArray(bytesPerPacket)
        val rtpPacket = ByteArray(RTP_HEADER_SIZE + bytesPerPacket)

        recorder.startRecording()
        Log.i(TAG, "Recording started, samplesPerPacket=$samplesPerPacket bytesPerPacket=$bytesPerPacket")

        try {
            while (isRunning.get()) {
                // קרא אודיו
                var bytesRead = 0
                while (bytesRead < bytesPerPacket && isRunning.get()) {
                    val n = recorder.read(audioBuffer, bytesRead, bytesPerPacket - bytesRead)
                    if (n < 0) break
                    bytesRead += n
                }
                if (bytesRead <= 0) continue

                // המר L16 ל-big-endian (RTP דורש big-endian)
                val rtpAudio = convertToBigEndian(audioBuffer, bytesRead)

                // חשב רמת אות לUI
                val level = calculateLevel(audioBuffer, bytesRead)
                onLevelUpdate(level)

                // בנה RTP header
                buildRtpHeader(rtpPacket, rtpAudio.size)

                // העתק אודיו לאחר ה-header
                System.arraycopy(rtpAudio, 0, rtpPacket, RTP_HEADER_SIZE, rtpAudio.size)

                // שלח UDP
                val packet = DatagramPacket(
                    rtpPacket, 0, RTP_HEADER_SIZE + rtpAudio.size,
                    destAddr, destPort
                )
                socket?.send(packet)

                // עדכן RTP counters
                sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                timestamp += samplesPerPacket
            }
        } catch (e: Exception) {
            if (isRunning.get()) Log.e(TAG, "TX error: ${e.message}")
        } finally {
            recorder.stop()
            recorder.release()
            Log.i(TAG, "Recording stopped")
        }
    }

    // ── RTP Header builder ──────────────────────────────────
    private fun buildRtpHeader(buf: ByteArray, payloadSize: Int) {
        // Byte 0: V=2, P=0, X=0, CC=0
        buf[0] = ((RTP_VERSION shl 6)).toByte()
        // Byte 1: M=0, PT=96
        buf[1] = (RTP_PAYLOAD_PCMU and 0x7F).toByte()
        // Bytes 2-3: Sequence Number
        buf[2] = (sequenceNumber shr 8).toByte()
        buf[3] = (sequenceNumber and 0xFF).toByte()
        // Bytes 4-7: Timestamp
        val ts = timestamp and 0xFFFFFFFFL
        buf[4] = (ts shr 24).toByte()
        buf[5] = (ts shr 16).toByte()
        buf[6] = (ts shr 8).toByte()
        buf[7] = (ts and 0xFF).toByte()
        // Bytes 8-11: SSRC
        buf[8]  = (ssrc shr 24).toByte()
        buf[9]  = (ssrc shr 16).toByte()
        buf[10] = (ssrc shr 8).toByte()
        buf[11] = (ssrc and 0xFF).toByte()
    }

    // ── המרה ל-Big Endian (אנדרואיד = Little Endian) ────────
    private fun convertToBigEndian(src: ByteArray, len: Int): ByteArray {
        if (bitDepth != 16) return src.copyOf(len)
        val dst = ByteArray(len)
        var i = 0
        while (i < len - 1) {
            dst[i]     = src[i + 1]   // High byte
            dst[i + 1] = src[i]       // Low byte
            i += 2
        }
        return dst
    }

    // ── רמת אות (0.0–1.0) ───────────────────────────────────
    private fun calculateLevel(buf: ByteArray, len: Int): Float {
        var sum = 0L
        var i = 0
        while (i < len - 1) {
            val sample = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            sum += sample.toLong() * sample.toLong()
            i += 2
        }
        val samples = len / 2
        if (samples == 0) return 0f
        val rms = Math.sqrt(sum.toDouble() / samples)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    // ── SDP Description (לשימוש עתידי עם SAP) ──────────────
    fun generateSdp(sessionName: String = "Android AES67 TX"): String {
        return """
            v=0
            o=- ${System.currentTimeMillis()} 1 IN IP4 0.0.0.0
            s=$sessionName
            c=IN IP4 $destIp
            t=0 0
            a=clock-domain:PTPv2 0
            m=audio $destPort RTP/AVP 96
            a=rtpmap:96 L${bitDepth}/${sampleRate}/${channels}
            a=ptime:$packetTimeMs
            a=mediaclk:direct=0
        """.trimIndent()
    }
}
