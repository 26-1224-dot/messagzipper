package kr.messazipper.demo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * 외부 음원 파일 없이, 지퍼가 잠기고 풀릴 때 나는 '지지직' 소리를
 * 코드에서 직접 합성해서 재생한다. reverse=true 면 풀리는(음이 내려가는) 소리.
 */
object SoundFx {
    private const val SR = 22050

    fun playZip(reverse: Boolean) {
        try {
            val data = buildZip(reverse)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            Thread.sleep((data.size * 1000L / SR) + 60)
            track.stop()
            track.release()
        } catch (_: Exception) { /* 소리는 연출일 뿐, 실패해도 잠금 동작엔 영향 없음 */ }
    }

    private fun buildZip(reverse: Boolean): ShortArray {
        val clicks = 22
        val clickMs = 9
        val gapMs = 5
        val totalMs = clicks * (clickMs + gapMs) + 20
        val samples = ShortArray(SR * totalMs / 1000)
        var pos = 0
        for (i in 0 until clicks) {
            val idx = if (reverse) clicks - 1 - i else i
            val freq = 1400.0 + idx * 55.0
            val n = SR * clickMs / 1000
            for (s in 0 until n) {
                if (pos + s >= samples.size) break
                val t = s.toDouble() / SR
                val env = 1.0 - s.toDouble() / n
                val tone = sin(2 * PI * freq * t) * env
                val noise = (Random.nextDouble() * 2 - 1) * 0.35 * env
                val v = ((tone * 0.65 + noise) * Short.MAX_VALUE * 0.5).toInt()
                samples[pos + s] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            pos += n + SR * gapMs / 1000
        }
        return samples
    }
}
