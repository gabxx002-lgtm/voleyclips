package com.voleyclips.app

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una jugada marcada. Todos los tiempos en milisegundos del video completo. */
data class Clip(
    val id: Long,
    val tMs: Long,
    val preMs: Long,
    val postMs: Long,
    val tag: String = "Punto",
    val zoom: Float = 2f,
    val fx: Float = 0.5f,
    val fy: Float = 0.5f,
    val fset: Boolean = false,
    /** Marcador en el momento de la jugada, por ejemplo "Set 2 · 14–12". */
    val score: String = "",
)

data class Match(
    val id: String,
    val name: String,
    val mode: String, // "oficial" o "parque"
    val createdAt: Long,
    val durationMs: Long,
    val videoDeleted: Boolean,
    val clips: List<Clip>,
    val teamA: String = "Equipo A",
    val teamB: String = "Equipo B",
    /** Resultado final, por ejemplo "Equipo A 2 – 1 Equipo B (25–20, 22–25, 15–12)". */
    val finalScore: String = "",
)

/* ---------------- Marcador ---------------- */

/** Marcador de un partido. Los sets terminados quedan guardados en [played]. */
data class Score(
    val ptsA: Int = 0,
    val ptsB: Int = 0,
    val setsA: Int = 0,
    val setsB: Int = 0,
    val played: List<String> = emptyList(),
)

/** Puntos que hay que llegar en el set actual. Oficial: 25, y 15 en el quinto set. Parque: lo que se elija. */
fun setTarget(mode: String, parkTarget: Int, s: Score): Int =
    if (mode == "oficial") (if (s.setsA + s.setsB >= 4) 15 else 25) else parkTarget

/** Oficial termina cuando alguien gana 3 sets. En parque se sigue jugando sets hasta que se detenga. */
fun matchOver(mode: String, s: Score): Boolean = mode == "oficial" && (s.setsA >= 3 || s.setsB >= 3)

/** Suma un punto al equipo 1 (A) o 2 (B). El set se cierra al llegar al objetivo con 2 puntos de diferencia. */
fun addPoint(s: Score, team: Int, mode: String, parkTarget: Int): Score {
    if (matchOver(mode, s)) return s
    val a = s.ptsA + if (team == 1) 1 else 0
    val b = s.ptsB + if (team == 2) 1 else 0
    val target = setTarget(mode, parkTarget, s)
    return when {
        a >= target && a - b >= 2 -> s.copy(ptsA = 0, ptsB = 0, setsA = s.setsA + 1, played = s.played + "$a–$b")
        b >= target && b - a >= 2 -> s.copy(ptsA = 0, ptsB = 0, setsB = s.setsB + 1, played = s.played + "$a–$b")
        else -> s.copy(ptsA = a, ptsB = b)
    }
}

/** Texto corto para ponerle a cada jugada. */
fun scoreLabel(s: Score): String =
    "Set " + (s.setsA + s.setsB + 1) + " · " + s.ptsA + "–" + s.ptsB +
        (if (s.setsA + s.setsB > 0) " (sets " + s.setsA + "–" + s.setsB + ")" else "")

fun finalScoreText(s: Score, teamA: String, teamB: String): String =
    teamA + " " + s.setsA + " – " + s.setsB + " " + teamB +
        (if (s.played.isNotEmpty()) " (" + s.played.joinToString(", ") + ")" else "") +
        (if (s.ptsA + s.ptsB > 0) " · set sin terminar " + s.ptsA + "–" + s.ptsB else "")

val TAGS = listOf("Punto", "Ace", "Bloqueo", "Defensa", "Remate")
val ZOOMS = listOf(1f, 1.5f, 2f, 3f)

/** Segundos que guarda cada marca antes y después, según el tipo de juego. */
fun defaultWindow(mode: String): Pair<Long, Long> =
    if (mode == "oficial") 8000L to 4000L else 6000L to 3000L

fun modeLabel(mode: String) = if (mode == "oficial") "Oficial" else "Parque"

fun clipStart(c: Clip): Long = maxOf(0L, c.tMs - c.preMs)
fun clipEnd(c: Clip, durationMs: Long): Long =
    if (durationMs > 0) minOf(durationMs, c.tMs + c.postMs) else c.tMs + c.postMs

fun fmt(ms: Long): String {
    val t = maxOf(0L, ms) / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun fmtZoom(z: Float): String = (if (z % 1f == 0f) z.toInt().toString() else z.toString().replace('.', ',')) + "×"

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_073_741_824L) "%.1f GB".format(bytes / 1_073_741_824.0).replace('.', ',')
    else "${maxOf(1L, bytes / 1_048_576L)} MB"

/* ---- Zoom: la misma matemática para la vista previa y para el video exportado ---- */

const val ZOOM_RAMP_MS = 600f

/** Zoom a los [msSinceMark] ms de la marca. Antes de la marca no hay zoom; después entra suave. */
fun zoomFactor(zoom: Float, msSinceMark: Float): Float {
    if (zoom <= 1f) return 1f
    val k = (msSinceMark / ZOOM_RAMP_MS).coerceIn(0f, 1f)
    val e = k * k * (3f - 2f * k)
    return 1f + (zoom - 1f) * e
}

/** Esquina (0..1) del recorte para que quede centrado en [focus] sin salirse del cuadro. */
fun cropOrigin(focus: Float, scale: Float): Float =
    (focus - 0.5f / scale).coerceIn(0f, 1f - 1f / scale)

/* ---- Almacenamiento en la carpeta privada de la app ---- */

object Store {
    private fun root(ctx: Context) = File(ctx.filesDir, "matches").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }
    fun videoFile(ctx: Context, id: String) = File(dir(ctx, id), "video.mp4")

    fun save(ctx: Context, m: Match) {
        val arr = JSONArray()
        m.clips.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id).put("tMs", c.tMs).put("preMs", c.preMs).put("postMs", c.postMs)
                    .put("tag", c.tag).put("zoom", c.zoom.toDouble())
                    .put("fx", c.fx.toDouble()).put("fy", c.fy.toDouble()).put("fset", c.fset)
                    .put("score", c.score)
            )
        }
        val o = JSONObject()
            .put("id", m.id).put("name", m.name).put("mode", m.mode)
            .put("createdAt", m.createdAt).put("durationMs", m.durationMs)
            .put("videoDeleted", m.videoDeleted).put("clips", arr)
            .put("teamA", m.teamA).put("teamB", m.teamB).put("finalScore", m.finalScore)
        File(dir(ctx, m.id), "match.json").writeText(o.toString())
    }

    fun load(ctx: Context, id: String): Match? {
        return try {
            val o = JSONObject(File(dir(ctx, id), "match.json").readText())
            val arr = o.optJSONArray("clips") ?: JSONArray()
            val clips = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Clip(
                    id = c.getLong("id"), tMs = c.getLong("tMs"),
                    preMs = c.getLong("preMs"), postMs = c.getLong("postMs"),
                    tag = c.optString("tag", "Punto"), zoom = c.optDouble("zoom", 2.0).toFloat(),
                    fx = c.optDouble("fx", 0.5).toFloat(), fy = c.optDouble("fy", 0.5).toFloat(),
                    fset = c.optBoolean("fset", false),
                    score = c.optString("score", ""),
                )
            }
            Match(
                id = o.getString("id"), name = o.optString("name", "Partido"),
                mode = o.optString("mode", "oficial"), createdAt = o.optLong("createdAt", 0L),
                durationMs = o.optLong("durationMs", 0L), videoDeleted = o.optBoolean("videoDeleted", false),
                clips = clips,
                teamA = o.optString("teamA", "Equipo A"), teamB = o.optString("teamB", "Equipo B"),
                finalScore = o.optString("finalScore", ""),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun list(ctx: Context): List<Match> =
        (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(ctx, it.name) }
            .sortedByDescending { it.createdAt }

    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
    }

    fun durationOf(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    /** Ancho / alto del video ya con la rotación aplicada. */
    fun ratioOf(file: File): Float {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h / w else w / h
        } catch (e: Exception) {
            16f / 9f
        } finally {
            r.release()
        }
    }
}
