package com.voleyclips.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Lo que se eligió en la pantalla de inicio antes de grabar. */
data class RecordSetup(
    val name: String,
    val mode: String,
    val teamA: String,
    val teamB: String,
    val parkTarget: Int,
    val quality: String, // "HD" o "FHD"
)

/** Las teclas de volumen llegan por la actividad; la pantalla de grabación se registra acá. */
object KeyBus {
    var onVolume: (() -> Boolean)? = null
}

/** El gesto se hace frente al lente un instante antes de que se note: la jugada termina un poco antes de él. */
const val GESTURE_LEAD_MS = 2500L

@Composable
fun RecordScreen(setup: RecordSetup, onFinished: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current

    fun hasPerms(): Boolean = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPerms()
    }
    LaunchedEffect(Unit) {
        if (!granted) permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    if (!granted) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("VoleyClips necesita la cámara y el micrófono para grabar el partido con sonido.", fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                }) { Text("Dar permisos") }
                TextButton(onClick = onCancel) { Text("Volver") }
            }
        }
    } else {
        RecordContent(setup, onFinished, onCancel)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun RecordContent(setup: RecordSetup, onFinished: (String) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Al grabar la pantalla se pone horizontal; al salir vuelve a vertical.
    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val matchId = remember { System.currentTimeMillis().toString() }
    val createdAt = remember { System.currentTimeMillis() }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE // vista previa más liviana
        }
    }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val startRef = remember { longArrayOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    val clips = remember { mutableStateListOf<Clip>() }
    var score by remember { mutableStateOf(Score()) }
    val history = remember { mutableStateListOf<Score>() }
    var gestureState by remember { mutableStateOf("Preparando los gestos…") }
    val watcherHolder = remember { arrayOfNulls<GestureWatcher>(1) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysisRef = remember { arrayOfNulls<ImageAnalysis>(1) }
    var gesturesOn by remember { mutableStateOf(true) }
    val analyzerFn = remember {
        ImageAnalysis.Analyzer { image ->
            val w = watcherHolder[0]
            if (w != null) w.analyze(image) else image.close()
        }
    }
    val freeGb = remember { StatFs(ctx.filesDir.absolutePath).availableBytes / 1_073_741_824.0 }

    fun nowMs(): Long = if (startRef[0] == 0L) 0L else SystemClock.elapsedRealtime() - startRef[0]

    /** Guarda las últimas [seconds] s. [leadMs] es cuánto antes de ahora terminó la jugada. */
    fun saveClip(seconds: Int, leadMs: Long, via: String) {
        if (!isRecording) return
        val t = maxOf(1000L, nowMs() - leadMs)
        val pre = maxOf(1000L, seconds * 1000L - 1000L)
        clips.add(
            Clip(
                id = System.nanoTime(), tMs = t, preMs = pre, postMs = 1000L,
                tag = "Punto", zoom = 1f, score = scoreLabel(score),
            )
        )
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        info = via + ": jugada de " + seconds + " s guardada (" + scoreLabel(score) + ")"
    }

    fun point(team: Int) {
        val before = score
        val next = addPoint(before, team, setup.mode, setup.parkTarget)
        if (next == before) return
        history.add(before)
        score = next
        if (next.played.size > before.played.size) {
            info = "Set terminado " + next.played.last() + ". Sets: " + next.setsA + "–" + next.setsB
        }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun undo() {
        if (history.isNotEmpty()) score = history.removeAt(history.lastIndex)
    }

    /** Qué hacer cuando la cámara reconoce un gesto. */
    fun onGestureCode(code: Int) {
        when (code) {
            GESTURE_POINT_A -> if (isRecording) {
                point(1)
                info = "Gesto: punto para " + setup.teamA
            }
            GESTURE_POINT_B -> if (isRecording) {
                point(2)
                info = "Gesto: punto para " + setup.teamB
            }
            else -> saveClip(code, GESTURE_LEAD_MS, "Gesto")
        }
    }

    /** Apagar los gestos libera al celular: la cámara deja de analizar imágenes. */
    fun setGestures(on: Boolean) {
        gesturesOn = on
        val a = analysisRef[0] ?: return
        if (on) a.setAnalyzer(analysisExecutor, analyzerFn) else a.clearAnalyzer()
    }

    // Cámara: vista previa + grabación + análisis de gestos. Si el celular no deja las tres a la vez, se apagan los gestos.
    // Se espera a que la pantalla ya esté horizontal para que el video no salga girado.
    DisposableEffect(landscape) {
        if (!landscape) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val q = if (setup.quality == "FHD") Quality.FHD else Quality.HD
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(android.util.Size(480, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysisRef[0] = analysis
                if (gesturesOn) analysis.setAnalyzer(analysisExecutor, analyzerFn)
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                } catch (e: Exception) {
                    provider.unbindAll()
                    provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    gestureState = "Este celular no deja usar gestos mientras graba. Usa los botones o el volumen."
                }
                videoCapture = capture
            } catch (e: Exception) {
                error = "No pude abrir la cámara: " + (e.message ?: "error desconocido")
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            try {
                if (future.isDone) future.get().unbindAll()
            } catch (e: Exception) {
                // la cámara ya estaba liberada
            }
            analysisRef[0] = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    // Modelo de gestos: se baja una vez y después funciona sin internet.
    LaunchedEffect(Unit) {
        if (!GestureModel.isReady(ctx)) {
            gestureState = "Descargando el modelo de gestos (unos 8 MB)…"
            val err = withContext(Dispatchers.IO) { GestureModel.download(ctx) }
            if (err != null) {
                gestureState = "Gestos apagados ($err). Usa los botones o el volumen."
                return@LaunchedEffect
            }
        }
        val w = GestureWatcher(ctx) { code -> mainHandler.post { onGestureCode(code) } }
        val err = withContext(Dispatchers.Default) { w.start() }
        if (err != null) {
            w.close()
            gestureState = "Gestos apagados ($err). Usa los botones o el volumen."
        } else {
            watcherHolder[0] = w
            if (!gestureState.startsWith("Este celular")) gestureState = "Gestos listos"
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            watcherHolder[0]?.close()
            watcherHolder[0] = null
        }
    }

    // Botones de volumen: 1 toque = 10 s, 2 toques = 20 s, 3 toques = 30 s.
    val volPresses = remember { intArrayOf(0) }
    var volJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(isRecording) {
        KeyBus.onVolume = {
            if (isRecording) {
                volPresses[0]++
                volJob?.cancel()
                volJob = scope.launch {
                    delay(1200)
                    val n = volPresses[0].coerceIn(1, 3)
                    volPresses[0] = 0
                    saveClip(n * 10, 600L, "Volumen")
                }
                true
            } else {
                false
            }
        }
        onDispose { KeyBus.onVolume = null }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            val s = nowMs() / 1000 * 1000
            if (s != elapsedMs) elapsedMs = s // solo se redibuja cuando cambia el segundo
            delay(300)
        }
    }

    BackHandler(enabled = isRecording) { /* no salir a media grabación */ }

    fun startRecording() {
        val capture = videoCapture ?: return
        if (isRecording || recording != null) return
        val file = Store.videoFile(ctx, matchId)
        recording = capture.output
            .prepareRecording(ctx, FileOutputOptions.Builder(file).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        startRef[0] = SystemClock.elapsedRealtime()
                        isRecording = true
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        if (event.hasError() && !file.exists()) {
                            error = "La grabación falló (código " + event.error + ")."
                        } else {
                            val fixed = clips.map { c -> c.copy(tMs = minOf(c.tMs, maxOf(1000L, durationMs))) }
                            Store.save(
                                ctx,
                                Match(
                                    id = matchId, name = setup.name, mode = setup.mode, createdAt = createdAt,
                                    durationMs = durationMs, videoDeleted = false, clips = fixed,
                                    teamA = setup.teamA, teamB = setup.teamB,
                                    finalScore = finalScoreText(score, setup.teamA, setup.teamB),
                                ),
                            )
                            onFinished(matchId)
                        }
                    }
                    else -> {}
                }
            }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Reloj, jugadas guardadas y estado de los gestos
        Column(
            Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(10.dp)
                .background(Color(0x99000000), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecording) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(VcRed, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(fmt(elapsedMs), color = Color.White, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(10.dp))
                Text(clips.size.toString() + " jugadas", color = Color.White, fontSize = 14.sp)
            }
            Text(gestureState, color = Color(0xFFBDB0B3), fontSize = 12.sp)
        }

        // Último aviso
        if (info.isNotEmpty()) {
            Text(
                info,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(10.dp)
                    .background(VcAccent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = VcOnAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
        }

        // Panel de control
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(10.dp)
                .width(300.dp)
                .background(Color(0xDD110C0D), RoundedCornerShape(16.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScorePanel(
                score = score, teamA = setup.teamA, teamB = setup.teamB,
                mode = setup.mode, parkTarget = setup.parkTarget,
                canUndo = history.isNotEmpty(), onPoint = { point(it) }, onUndo = { undo() },
            )

            OutlinedButton(onClick = { setGestures(!gesturesOn) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (gesturesOn) "Gestos: activados" else "Gestos: apagados (más fluido)", fontSize = 13.sp)
            }

            if (isRecording) {
                Text("Guardar jugada (los segundos de antes)", color = Color(0xFFBDB0B3), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30).forEach { s ->
                        Button(
                            onClick = { saveClip(s, 500L, "Botón") },
                            colors = ButtonDefaults.buttonColors(containerColor = VcCrimson, contentColor = Color.White),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                        ) { Text("$s s", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
                Text(
                    "Gestos frente al lente:\n👍 10 s · ✌ 20 s · ✋ 30 s\n☝ punto " + setup.teamA + " · 🤟 punto " + setup.teamB + "\nVolumen: 1, 2 o 3 toques",
                    color = Color(0xFFBDB0B3), fontSize = 12.sp,
                )
                Button(
                    onClick = { recording?.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A2E), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Detener y guardar jugadas") }
            } else {
                Button(
                    onClick = { startRecording() },
                    enabled = videoCapture != null,
                    colors = ButtonDefaults.buttonColors(containerColor = VcRed, contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("Grabar", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Text(
                    "Espacio libre: " + "%.1f".format(freeGb).replace('.', ',') + " GB. Mientras grabas el video completo ocupa espacio (aprox. 2 a 4 GB por hora en HD, 4 a 7 GB en Full HD). Al terminar se queda solo con las jugadas.",
                    color = Color(0xFFBDB0B3), fontSize = 12.sp,
                )
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Salir") }
                val err = error
                if (err != null) Text(err, color = VcRed, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ScorePanel(
    score: Score,
    teamA: String,
    teamB: String,
    mode: String,
    parkTarget: Int,
    canUndo: Boolean,
    onPoint: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    val over = matchOver(mode, score)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamColumn(teamA, score.ptsA, score.setsA, !over, Modifier.weight(1f)) { onPoint(1) }
            TeamColumn(teamB, score.ptsB, score.setsB, !over, Modifier.weight(1f)) { onPoint(2) }
        }
        val target = setTarget(mode, parkTarget, score)
        Text(
            if (over) "Partido terminado" else "Set " + (score.setsA + score.setsB + 1) + " · a " + target + ", con 2 de diferencia",
            color = Color(0xFFBDB0B3), fontSize = 12.sp,
        )
        if (score.played.isNotEmpty()) {
            Text("Sets: " + score.played.joinToString(", "), color = Color(0xFFBDB0B3), fontSize = 12.sp)
        }
        OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.fillMaxWidth()) { Text("Deshacer último punto") }
    }
}

@Composable
private fun TeamColumn(name: String, pts: Int, sets: Int, enabled: Boolean, modifier: Modifier, onPlus: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
        Text(pts.toString(), color = VcAccent, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
        Text("Sets: $sets", color = Color(0xFFBDB0B3), fontSize = 12.sp)
        Button(
            onClick = onPlus,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) { Text("+1", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
    }
}
