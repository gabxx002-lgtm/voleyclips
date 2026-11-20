package com.voleyclips.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if ((keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) &&
            (event?.repeatCount ?: 0) == 0
        ) {
            if (KeyBus.onVolume?.invoke() == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoleyTheme {
                App()
            }
        }
    }
}

sealed interface Screen {
    object Home : Screen
    data class Record(val setup: RecordSetup) : Screen
    data class Review(val id: String) : Screen
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    when (val s = screen) {
        is Screen.Home -> HomeScreen(
            onNew = { setup -> screen = Screen.Record(setup) },
            onOpen = { id -> screen = Screen.Review(id) },
        )
        is Screen.Record -> RecordScreen(
            setup = s.setup,
            onFinished = { id -> screen = Screen.Review(id) },
            onCancel = { screen = Screen.Home },
        )
        is Screen.Review -> ReviewScreen(
            matchId = s.id,
            onBack = { screen = Screen.Home },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onNew: (RecordSetup) -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("Partido") }
    var mode by remember { mutableStateOf("oficial") }
    var teamA by remember { mutableStateOf("Equipo A") }
    var teamB by remember { mutableStateOf("Equipo B") }
    var parkTarget by remember { mutableStateOf(21) }
    var quality by remember { mutableStateOf("HD") }
    var matches by remember { mutableStateOf(Store.list(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Match?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val id = System.currentTimeMillis().toString()
                withContext(Dispatchers.IO) {
                    val dest = Store.videoFile(ctx, id)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    val dur = Store.durationOf(dest)
                    Store.save(ctx, Match(id, "Video importado", "oficial", System.currentTimeMillis(), dur, false, emptyList()))
                }
                importing = false
                onOpen(id)
            }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("VoleyClips", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary)
            Text(
                "Graba el partido, marca los puntos y quédate solo con las jugadas.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Nombre del partido") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = teamA, onValueChange = { teamA = it.take(16) }, label = { Text("Equipo A") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = teamB, onValueChange = { teamB = it.take(16) }, label = { Text("Equipo B") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode == "oficial", onClick = { mode = "oficial" }, label = { Text("Oficial (25)") })
                FilterChip(selected = mode == "parque", onClick = { mode = "parque" }, label = { Text("Parque") })
                if (mode == "parque") {
                    FilterChip(selected = parkTarget == 15, onClick = { parkTarget = 15 }, label = { Text("a 15") })
                    FilterChip(selected = parkTarget == 21, onClick = { parkTarget = 21 }, label = { Text("a 21") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Calidad:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilterChip(selected = quality == "HD", onClick = { quality = "HD" }, label = { Text("720p (ocupa menos)") })
                FilterChip(selected = quality == "FHD", onClick = { quality = "FHD" }, label = { Text("1080p") })
            }
            Button(
                onClick = {
                    onNew(
                        RecordSetup(
                            name = name.ifBlank { "Partido" }, mode = mode,
                            teamA = teamA.ifBlank { "Equipo A" }, teamB = teamB.ifBlank { "Equipo B" },
                            parkTarget = parkTarget, quality = quality,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text("Empezar a grabar", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { picker.launch("video/*") },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (importing) "Importando video…" else "Usar un video de la galería") }
        }

        Column(Modifier.weight(1f)) {
            Text("Partidos", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (matches.isEmpty()) {
                Text("Todavía no hay partidos. Cuando grabes uno aparece acá.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(matches, key = { it.id }) { m ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        modeLabel(m.mode) + " · " + SimpleDateFormat("d MMM HH:mm", Locale("es")).format(Date(m.createdAt)) +
                                            " · " + m.clips.size + " jugadas · " + fmt(m.durationMs),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 13.sp,
                                    )
                                    if (m.videoDeleted) {
                                        Text("Video completo borrado", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                    }
                                }
                                TextButton(onClick = { onOpen(m.id) }) { Text("Abrir") }
                                TextButton(onClick = { toDelete = m }) { Text("Borrar") }
                            }
                        }
                    }
                }
            }
        }
    }

    val target = toDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar este partido?") },
            text = { Text("Se borra el video completo y las marcas. Las jugadas que ya descargaste siguen en tu galería.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.delete(ctx, target.id)
                    matches = Store.list(ctx)
                    toDelete = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}
