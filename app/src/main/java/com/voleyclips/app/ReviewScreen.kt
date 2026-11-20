package com.voleyclips.app

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ReviewScreen(matchId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val loaded = remember { Store.load(ctx, matchId) }
    if (loaded == null) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No encontré este partido.")
            TextButton(onClick = onBack) { Text("Volver") }
        }
    } else {
        ReviewContent(loaded, onBack)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReviewContent(m0: Match, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember { Store.videoFile(ctx, m0.id) }
    val ratio = remember { if (file.exists()) Store.ratioOf(file) else 16f / 9f }
    val player = remember { ExoPlayer.Builder(ctx).build() }

    var clips by remember { mutableStateOf(m0.clips.sortedBy { it.tMs }) }
    var selId by remember { mutableStateOf<Long?>(null) }
    var focusMode by remember { mutableStateOf(false) }
    var videoDeleted by remember { mutableStateOf(m0.videoDeleted || !file.exists()) }
    var confirmDelete by remember { mutableStateOf(false) }

    var posMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(m0.durationMs) }
    var isPlaying by remember { mutableStateOf(false) }

    var previewId by remember { mutableStateOf<Long?>(null) }
    var pz by remember { mutableFloatStateOf(1f) }
    var pcx by remember { mutableFloatStateOf(0f) }
    var pcy by remember { mutableFloatStateOf(0f) }
    var boxW by remember { mutableFloatStateOf(1f) }
    var boxH by remember { mutableFloatStateOf(1f) }

    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun persist() {
        Store.save(ctx, m0.copy(clips = clips, videoDeleted = videoDeleted))
    }

    fun updateClip(n: Clip) {
        clips = clips.map { if (it.id == n.id) n else it }
        persist()
    }

    LaunchedEffect(Unit) {
        if (!videoDeleted) {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0) durationMs = player.duration
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Reloj de la pantalla: posición, vista previa con zoom y progreso de la exportación.
    LaunchedEffect(Unit) {
        val holder = ProgressHolder()
        while (true) {
            posMs = player.currentPosition
            val pid = previewId
            if (pid != null) {
                val c = clips.firstOrNull { it.id == pid }
                if (c == null || !player.playWhenReady || posMs >= clipEnd(c, durationMs)) {
                    player.pause()
                    previewId = null
                    pz = 1f
                } else {
                    val s = zoomFactor(c.zoom, (posMs - c.tMs).toFloat())
                    pz = s
                    pcx = cropOrigin(c.fx, s)
                    pcy = cropOrigin(c.fy, s)
                }
            }
            val t = transformer
            if (exporting && t != null) {
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
            }
            delay(33)
        }
    }

    fun startExport(list: List<Clip>, label: String, onSaved: (Boolean) -> Unit = {}) {
        if (exporting || list.isEmpty() || videoDeleted) return
        player.pause()
        previewId = null
        pz = 1f
        exporting = true
        progress = 0f
        message = null
        val out = File(ctx.cacheDir, "export-" + System.currentTimeMillis() + ".mp4")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        val displayName = m0.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').ifEmpty { "partido" } + "-" + label + "-" + stamp + ".mp4"
        try {
            val composition = Exporter.buildComposition(Uri.fromFile(file), list, durationMs)
            transformer = Exporter.start(
                ctx, composition, out,
                onDone = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Exporter.saveToGallery(ctx, out, displayName) }
                        val size = out.length()
                        out.delete()
                        exporting = false
                        transformer = null
                        message = if (ok) {
                            val orig = file.length()
                            val pct = if (orig > 0) maxOf(0, ((1.0 - size.toDouble() / orig) * 100).toInt()) else 0
                            "Listo. Guardado en tu galería (Movies/VoleyClips): " + fmtSize(orig) + " pasó a " + fmtSize(size) + " (" + pct + "% menos)."
                        } else {
                            "El video se armó pero no pude guardarlo en la galería."
                        }
                        onSaved(ok)
                    }
                },
                onError = { msg ->
                    out.delete()
                    exporting = false
                    transformer = null
                    message = "No se pudo armar el video: $msg"
                    onSaved(false)
                },
            )
        } catch (e: Exception) {
            out.delete()
            exporting = false
            message = "No se pudo armar el video: " + (e.message ?: "error desconocido")
            onSaved(false)
        }
    }

    /** Guarda cada jugada como video suelto en la galería y, si todas salieron bien, borra el video completo. */
    fun saveEachAndDelete(queue: List<Clip>, i: Int, startSize: Long) {
        if (i >= queue.size) {
            player.pause()
            player.clearMediaItems()
            file.delete()
            videoDeleted = true
            persist()
            message = "Listo: " + queue.size + " jugadas en tu galería (Movies/VoleyClips). Borré el video completo y liberé " + fmtSize(startSize) + "."
            return
        }
        val c = queue[i]
        startExport(listOf(c), "jugada" + (i + 1)) { ok ->
            if (ok) {
                saveEachAndDelete(queue, i + 1, startSize)
            } else {
                message = "Se detuvo en la jugada " + (i + 1) + " de " + queue.size + ". El video completo NO se borró."
            }
        }
    }

    val selected = clips.firstOrNull { it.id == selId }
    val totalMs = clips.sumOf { clipEnd(it, durationMs) - clipStart(it) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /* ---------- Izquierda: video ---------- */
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    player.pause()
                    onBack()
                }) { Text("← Partidos") }
                Text(m0.name + " · " + modeLabel(m0.mode), fontWeight = FontWeight.Bold, maxLines = 1)
            }
            if (m0.finalScore.isNotEmpty()) {
                Text("Marcador final: " + m0.finalScore, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            }
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (videoDeleted) {
                    Text("El video completo ya se borró.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .clipToBounds()
                            .background(Color.Black)
                            .onSizeChanged {
                                boxW = it.width.toFloat()
                                boxH = it.height.toFloat()
                            },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0f)
                                    scaleX = pz
                                    scaleY = pz
                                    translationX = -pcx * boxW * pz
                                    translationY = -pcy * boxH * pz
                                },
                        ) {
                            // TextureView (no SurfaceView) para que el zoom con graphicsLayer sí se vea.
                            AndroidView(
                                factory = { c ->
                                    val exo = player
                                    TextureView(c).also { tv -> exo.setVideoTextureView(tv) }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (focusMode) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .border(3.dp, VcAccent)
                                    .pointerInput(selId) {
                                        detectTapGestures { offset ->
                                            val c = clips.firstOrNull { it.id == selId }
                                            if (c != null) {
                                                updateClip(
                                                    c.copy(
                                                        fx = (offset.x / size.width).coerceIn(0f, 1f),
                                                        fy = (offset.y / size.height).coerceIn(0f, 1f),
                                                        fset = true,
                                                    )
                                                )
                                            }
                                            focusMode = false
                                        }
                                    },
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Text(
                                    "Toca al jugador o al balón",
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .background(VcAccent, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    color = VcOnAccent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        val s = selected
                        if (s != null && (s.fset || focusMode) && previewId == null) {
                            Canvas(Modifier.fillMaxSize()) {
                                val center = Offset(s.fx * size.width, s.fy * size.height)
                                drawCircle(VcAccent, radius = 22.dp.toPx(), center = center, style = Stroke(width = 3.dp.toPx()))
                                drawCircle(VcAccent, radius = 3.dp.toPx(), center = center)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        previewId = null
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    enabled = !videoDeleted && !exporting,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text(if (isPlaying) "⏸" else "▶") }
                OutlinedButton(
                    onClick = { previewId = null; player.seekTo(maxOf(0L, player.currentPosition - 5000)) },
                    enabled = !videoDeleted && !exporting,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("−5 s") }
                OutlinedButton(
                    onClick = { previewId = null; player.seekTo(minOf(durationMs, player.currentPosition + 5000)) },
                    enabled = !videoDeleted && !exporting,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("+5 s") }
                Text(fmt(posMs) + " / " + fmt(durationMs), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            Slider(
                value = posMs.toFloat().coerceIn(0f, maxOf(1f, durationMs.toFloat())),
                onValueChange = {
                    previewId = null
                    player.seekTo(it.toLong())
                    posMs = it.toLong()
                },
                valueRange = 0f..maxOf(1f, durationMs.toFloat()),
                enabled = !videoDeleted && !exporting,
            )
        }

        /* ---------- Derecha: jugadas ---------- */
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Jugadas (" + clips.size + ")", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        val (pre, post) = defaultWindow(m0.mode)
                        val c = Clip(id = System.nanoTime(), tMs = player.currentPosition, preMs = pre, postMs = post)
                        clips = (clips + c).sortedBy { it.tMs }
                        selId = c.id
                        persist()
                    },
                    enabled = !videoDeleted && !exporting,
                ) { Text("Marcar aquí") }
            }
            Button(
                onClick = { startExport(clips, "resumen") },
                enabled = clips.isNotEmpty() && !videoDeleted && !exporting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VcAccent, contentColor = VcOnAccent),
            ) {
                Text(
                    if (clips.isEmpty()) "Marca jugadas para armar el resumen"
                    else "Descargar resumen (" + clips.size + " jugadas · " + (totalMs / 1000) + " s)",
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = { saveEachAndDelete(clips, 0, file.length()) },
                enabled = clips.isNotEmpty() && !videoDeleted && !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Guardar jugadas sueltas y borrar el video completo", fontSize = 13.sp) }
            if (exporting) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Armando el video… " + (progress * 100).toInt() + "%", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        transformer?.cancel()
                        transformer = null
                        exporting = false
                        message = "Cancelado."
                    }) { Text("Cancelar") }
                }
            }
            val msg = message
            if (msg != null) Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (clips.isEmpty()) {
                    item {
                        Text(
                            "Todavía no hay jugadas. Reproduce el video y toca «Marcar aquí» cuando caiga un punto.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(clips, key = { it.id }) { c ->
                    ClipCard(
                        c = c,
                        durationMs = durationMs,
                        selected = c.id == selId,
                        enabled = !exporting && !videoDeleted,
                        onSelect = { selId = c.id; focusMode = false },
                        onChange = { updateClip(it) },
                        onDelete = {
                            clips = clips.filter { it.id != c.id }
                            if (selId == c.id) selId = null
                            persist()
                        },
                        onView = {
                            selId = c.id
                            focusMode = false
                            player.seekTo(clipStart(c))
                            player.playWhenReady = true
                            previewId = c.id
                        },
                        onFocus = {
                            selId = c.id
                            previewId = null
                            player.pause()
                            player.seekTo(c.tMs)
                            focusMode = true
                        },
                        onDownload = { startExport(listOf(c), c.tag.lowercase() + "-" + fmt(c.tMs).replace(':', 'm')) },
                    )
                }
                item {
                    if (!videoDeleted) {
                        TextButton(onClick = { confirmDelete = true }, enabled = !exporting) {
                            Text("Borrar el video completo para liberar espacio", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar el video completo?") },
            text = {
                Text("Las jugadas que ya descargaste se quedan en tu galería. Después de borrarlo no vas a poder sacar más jugadas de este partido.")
            },
            confirmButton = {
                TextButton(onClick = {
                    player.pause()
                    player.clearMediaItems()
                    file.delete()
                    videoDeleted = true
                    previewId = null
                    pz = 1f
                    persist()
                    confirmDelete = false
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipCard(
    c: Clip,
    durationMs: Long,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onChange: (Clip) -> Unit,
    onDelete: () -> Unit,
    onView: () -> Unit,
    onFocus: () -> Unit,
    onDownload: () -> Unit,
) {
    val start = clipStart(c)
    val end = clipEnd(c, durationMs)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable { onSelect() },
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt(start) + " – " + fmt(end),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(((end - start) / 1000).toString() + " s", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                TextButton(onClick = onDelete, enabled = enabled) { Text("Quitar") }
            }
            if (c.score.isNotEmpty()) {
                Text("Marcador: " + c.score, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TAGS.forEach { t ->
                    FilterChip(selected = c.tag == t, onClick = { onChange(c.copy(tag = t)) }, label = { Text(t, fontSize = 12.sp) }, enabled = enabled)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Antes", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(46.dp))
                    Stepper(enabled, (c.preMs / 1000).toString() + " s",
                        onMinus = { onChange(c.copy(preMs = (c.preMs - 1000).coerceIn(1000L, 30000L))) },
                        onPlus = { onChange(c.copy(preMs = (c.preMs + 1000).coerceIn(1000L, 30000L))) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Después", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(62.dp))
                    Stepper(enabled, (c.postMs / 1000).toString() + " s",
                        onMinus = { onChange(c.copy(postMs = (c.postMs - 1000).coerceIn(1000L, 20000L))) },
                        onPlus = { onChange(c.copy(postMs = (c.postMs + 1000).coerceIn(1000L, 20000L))) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZOOMS.forEach { z ->
                    FilterChip(
                        selected = c.zoom == z,
                        onClick = { onChange(c.copy(zoom = z)) },
                        label = { Text(if (z == 1f) "Sin zoom" else fmtZoom(z), fontSize = 12.sp) },
                        enabled = enabled,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onView, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("▶ Ver", fontSize = 13.sp) }
                OutlinedButton(onClick = onFocus, enabled = enabled && c.zoom > 1f, modifier = Modifier.weight(1.2f), contentPadding = PaddingValues(4.dp)) {
                    Text(if (c.fset) "Cambiar foco" else "Fijar foco", fontSize = 13.sp)
                }
                Button(onClick = onDownload, enabled = enabled, modifier = Modifier.weight(1.2f), contentPadding = PaddingValues(4.dp)) { Text("Descargar", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun Stepper(enabled: Boolean, label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onMinus, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("−") }
        Text(label, fontFamily = FontFamily.Monospace, modifier = Modifier.width(40.dp), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onClick = onPlus, enabled = enabled, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(34.dp)) { Text("+") }
    }
}
