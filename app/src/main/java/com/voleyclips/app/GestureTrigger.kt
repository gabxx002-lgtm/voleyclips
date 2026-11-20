package com.voleyclips.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/** Modelo oficial de MediaPipe para reconocer gestos de mano (unos 8 MB). Se baja una sola vez. */
const val GESTURE_MODEL_URL =
    "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task"

/** Códigos que devuelve el vigilante además de 10, 20 y 30 (segundos de jugada). */
const val GESTURE_POINT_A = 101 // ☝ un dedo arriba: punto para el equipo A
const val GESTURE_POINT_B = 102 // 🤟 "te quiero" (pulgar, índice y meñique): punto para el equipo B

object GestureModel {
    fun file(ctx: Context) = File(ctx.filesDir, "gesture_recognizer.task")

    fun isReady(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 1_000_000L }

    /** Descarga el modelo. Devuelve null si salió bien, o un mensaje con lo que falló. */
    fun download(ctx: Context): String? {
        val tmp = File(ctx.filesDir, "gesture_recognizer.task.part")
        try {
            val conn = URL(GESTURE_MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) return "el servidor respondió " + conn.responseCode
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() < 1_000_000L) {
                tmp.delete()
                return "el archivo del modelo llegó incompleto"
            }
            if (!tmp.renameTo(file(ctx))) return "no pude guardar el modelo"
            return null
        } catch (e: Exception) {
            tmp.delete()
            return e.message ?: "no hay internet"
        }
    }
}

/**
 * Mira la cámara y avisa cuando alguien sostiene un gesto de mano frente al lente:
 * 👍 = 10 s, ✌ = 20 s, ✋ = 30 s. Hay que sostenerlo medio segundo para evitar falsas alarmas.
 */
class GestureWatcher(
    private val ctx: Context,
    private val onGesture: (Int) -> Unit,
) {
    private var recognizer: GestureRecognizer? = null
    private var lastName = ""
    private var streak = 0
    private var cooldownUntil = 0L
    private var lastFrameAt = 0L

    @Volatile
    private var closed = false

    /** Devuelve null si quedó listo, o un mensaje con el motivo. */
    fun start(): String? {
        return try {
            val bytes = GestureModel.file(ctx).readBytes()
            val buf = ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes)
            buf.rewind()
            val base = BaseOptions.builder().setModelAssetBuffer(buf).build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.6f)
                .setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.6f)
                .setResultListener { result, _ -> handle(result) }
                .setErrorListener { _ -> }
                .build()
            recognizer = GestureRecognizer.createFromOptions(ctx, options)
            null
        } catch (e: Exception) {
            e.message ?: "no se pudo iniciar el reconocimiento de gestos"
        }
    }

    private fun handle(result: GestureRecognizerResult) {
        val name = result.gestures().firstOrNull()?.firstOrNull()?.categoryName() ?: ""
        val secs = when (name) {
            "Thumb_Up" -> 10
            "Victory" -> 20
            "Open_Palm" -> 30
            "Pointing_Up" -> GESTURE_POINT_A
            "ILoveYou" -> GESTURE_POINT_B
            else -> 0
        }
        if (secs == 0) {
            streak = 0
        } else if (name == lastName) {
            streak++
        } else {
            streak = 1
        }
        lastName = name
        val now = SystemClock.elapsedRealtime()
        if (secs != 0 && streak >= HOLD_FRAMES && now >= cooldownUntil) {
            cooldownUntil = now + COOLDOWN_MS
            streak = 0
            onGesture(secs)
        }
    }

    /** Se llama con cada imagen del ImageAnalysis de CameraX. Siempre cierra la imagen. */
    fun analyze(image: ImageProxy) {
        try {
            val rec = recognizer
            val now = SystemClock.elapsedRealtime()
            if (rec == null || closed || now - lastFrameAt < FRAME_GAP_MS) return
            lastFrameAt = now
            val bmp = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else {
                bmp
            }
            rec.recognizeAsync(BitmapImageBuilder(upright).build(), SystemClock.uptimeMillis())
        } catch (e: Exception) {
            // un cuadro perdido no importa
        } finally {
            image.close()
        }
    }

    fun close() {
        closed = true
        try {
            recognizer?.close()
        } catch (e: Exception) {
            // ya estaba cerrado
        }
        recognizer = null
    }

    companion object {
        const val HOLD_FRAMES = 3      // unos 0,6 s con el gesto firme
        const val COOLDOWN_MS = 4_000L // después de guardar una jugada, espera antes de aceptar otro gesto
        const val FRAME_GAP_MS = 200L  // 5 cuadros por segundo alcanzan y dejan al celular libre para grabar fluido
    }
}
