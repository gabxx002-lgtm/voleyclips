package com.voleyclips.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Recorta cada jugada del video completo, le mete el zoom al punto y las une en un solo archivo.
 * Todo pasa dentro del celular con Media3 Transformer.
 */
object Exporter {

    fun buildComposition(videoUri: Uri, clips: List<Clip>, durationMs: Long): Composition {
        val items = clips.map { c ->
            val startMs = clipStart(c)
            val endMs = clipEnd(c, durationMs)
            val mediaItem = MediaItem.Builder()
                .setUri(videoUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            val effects: Effects = if (c.zoom > 1f) {
                val markOffsetMs = (c.tMs - startMs).toFloat()
                var firstUs = -1L
                val zoomEffect = MatrixTransformation { presentationTimeUs ->
                    // El tiempo se mide desde el primer cuadro de esta jugada.
                    if (firstUs < 0L) firstUs = presentationTimeUs
                    val sinceStartMs = (presentationTimeUs - firstUs) / 1000f
                    val s = zoomFactor(c.zoom, sinceStartMs - markOffsetMs)
                    val half = 1f - 1f / s
                    // Coordenadas normalizadas del GL: x a la derecha, y hacia arriba, de -1 a 1.
                    val px = (2f * c.fx - 1f).coerceIn(-half, half)
                    val py = (1f - 2f * c.fy).coerceIn(-half, half)
                    Matrix().apply {
                        postTranslate(-px, -py)
                        postScale(s, s)
                    }
                }
                Effects(emptyList<AudioProcessor>(), listOf<Effect>(zoomEffect))
            } else {
                Effects.EMPTY
            }
            EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        }
        return Composition.Builder(EditedMediaItemSequence(items)).build()
    }

    fun start(
        ctx: Context,
        composition: Composition,
        out: File,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ): Transformer {
        val encoderFactory = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(6_000_000).build()
            )
            .build()
        val transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    onError(exportException.message ?: "No se pudo armar el video.")
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        return transformer
    }

    /** Copia el video a la galería (Movies/VoleyClips). No pide permisos en Android 10 o más. */
    fun saveToGallery(ctx: Context, file: File, displayName: String): Boolean {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VoleyClips")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            false
        }
    }
}
