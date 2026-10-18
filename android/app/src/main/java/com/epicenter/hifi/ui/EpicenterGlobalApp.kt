package com.epicenter.hifi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epicenter.hifi.EpicenterGlobalAudioService
import com.epicenter.hifi.GlobalAudioState
import com.epicenter.hifi.GlobalAudioStatus
import com.epicenter.hifi.ui.components.KnobControl
import com.epicenter.hifi.ui.theme.AccentRed
import com.epicenter.hifi.ui.theme.EpicenterTheme

private enum class GlobalTab(val label: String) {
    HOME("Inicio"), EFFECTS("Efectos"), COMPAT("Compat."), SETTINGS("Ajustes")
}

@Composable
fun EpicenterGlobalApp(
    service: EpicenterGlobalAudioService?,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit
) {
    var selected by remember { mutableStateOf(GlobalTab.HOME) }
    val state by service?.state?.collectAsState() ?: remember { mutableStateOf(GlobalAudioState()) }
    var intensity by remember(service) { mutableStateOf(service?.currentIntensity() ?: 65f) }
    EpicenterTheme(darkTheme = true) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF08080A), contentColor = Color.White) {
          Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(com.epicenter.hifi.R.drawable.epicenter_logo),
                        contentDescription = "EpicenterDSP",
                        modifier = Modifier.size(42.dp)
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("EPICENTERDSP", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
                        Text("GLOBAL", fontSize = 10.sp, letterSpacing = 2.3.sp, color = AccentRed)
                    }
                }
                when (selected) {
                    GlobalTab.HOME -> HomeContent(state, service, intensity, { intensity = it }, onStartCapture, onStopCapture)
                    GlobalTab.EFFECTS -> EffectsContent(service, intensity, { intensity = it })
                    GlobalTab.COMPAT -> CompatibilityContent(state)
                    GlobalTab.SETTINGS -> SettingsContent()
                }
            }
            NavigationBar(containerColor = Color(0xFF111113)) {
                GlobalTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(when (tab) {
                            GlobalTab.HOME -> Icons.Default.Home
                            GlobalTab.EFFECTS -> Icons.Default.GraphicEq
                            GlobalTab.COMPAT -> Icons.Default.Info
                            GlobalTab.SETTINGS -> Icons.Default.Settings
                        }, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 10.sp) }
                    )
                }
            }
          }
        }
    }
}

@Composable
private fun HomeContent(state: GlobalAudioState, service: EpicenterGlobalAudioService?, intensity: Float, onIntensity: (Float) -> Unit, start: () -> Unit, stop: () -> Unit) {
    val bassOnlyEnabled by service?.bassOnlyEnabled?.collectAsState() ?: remember { mutableStateOf(false) }
    var cutoff by remember(service) { mutableStateOf(service?.currentBassCutoff() ?: 80f) }
    var bassGain by remember(service) { mutableStateOf(service?.currentBassGainPercent() ?: 70f) }
    var phaseDelay by remember(service) { mutableStateOf(service?.currentBassPhaseDelay() ?: 0f) }
    var inverted by remember(service) { mutableStateOf(service?.isBassPolarityInverted() ?: false) }
    val active = state.status == GlobalAudioStatus.CAPTURING || state.status == GlobalAudioStatus.SILENCE
    val statusColor = when (state.status) {
        GlobalAudioStatus.CAPTURING -> Color(0xFF64D995)
        GlobalAudioStatus.ERROR, GlobalAudioStatus.LIMITED -> Color(0xFFFF6875)
        else -> Color(0xFFB2B2B8)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(18.dp))
        Text("PROCESAMIENTO GLOBAL", color = Color(0xFF9B9BA2), fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = { if (active) stop() else start() },
            modifier = Modifier.size(190.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = if (active) AccentRed else Color(0xFF202023), contentColor = Color.White)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BoxPulse(active)
                Spacer(Modifier.height(12.dp))
                Text(if (active) "DETENER" else "INICIAR", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(state.message, color = statusColor, fontSize = 13.sp, textAlign = TextAlign.Center)
        if (state.sampleRate > 0) Text("${state.sampleRate / 1000} kHz · pico ${(state.peak * 100).toInt()}%", color = Color(0xFF88888F), fontSize = 11.sp)
        if (active && state.sampleRate > 0) {
            val timing = state.captureToOutputMs?.let { "${"%.1f".format(it)} ms · variación ${"%.1f".format(state.latencyJitterMs ?: 0f)} ms" }
                ?: "latencia de ruta: esperando timestamps"
            Text("Captura→salida estimada: $timing", color = Color(0xFF88888F), fontSize = 10.sp, textAlign = TextAlign.Center)
            if (state.outputRoute.isNotBlank()) Text("Salida: ${state.outputRoute}", color = Color(0xFF77777E), fontSize = 10.sp)
        }
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171719), contentColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("BASS-ONLY PARALLEL", fontWeight = FontWeight.SemiBold)
                        Text(if (bassOnlyEnabled) "Solo bajo generado activo" else "Bajo adicional apagado", color = Color(0xFF9999A0), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Switch(
                        checked = bassOnlyEnabled,
                        onCheckedChange = { service?.setBassOnlyEnabled(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = AccentRed, checkedThumbColor = Color.White)
                    )
                }
                Text("La app fuente sigue normal. Epicenter reproduce únicamente la señal wet generada; voces e instrumentos no se vuelven a emitir. Android no garantiza sincronía de fase.", color = Color(0xFF9999A0), fontSize = 11.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(10.dp))
                Text("PASA-BAJAS · ${cutoff.toInt()} Hz", color = Color.White, fontSize = 12.sp)
                Slider(value = cutoff, onValueChange = { cutoff = it; service?.setBassCutoff(it) }, valueRange = 50f..100f)
                Text("GANANCIA DEL BAJO · ${bassGain.toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(value = bassGain, onValueChange = { bassGain = it; service?.setBassGainPercent(it) }, valueRange = 0f..150f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Invertir polaridad", modifier = Modifier.weight(1f), color = Color.White, fontSize = 12.sp)
                    Switch(checked = inverted, onCheckedChange = { inverted = it; service?.setBassPolarityInverted(it) }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = AccentRed, checkedThumbColor = Color.White))
                }
                Text("AJUSTE DE FASE · RETARDO ${"%.1f".format(phaseDelay)} ms", color = Color.White, fontSize = 12.sp)
                Slider(value = phaseDelay, onValueChange = { phaseDelay = it; service?.setBassPhaseDelay(it) }, valueRange = 0f..20f)
            }
        }
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171719), contentColor = Color.White), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("INTENSIDAD DEL EPICENTER", color = Color(0xFF9999A0), fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Text("${intensity.toInt()}%", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                KnobControl(
                    label = "INTENSIDAD",
                    value = intensity,
                    minValue = 0f,
                    maxValue = 100f,
                    unit = "%",
                    size = 184.dp,
                    showValue = false,
                    onValueChange = { onIntensity(it); service?.setIntensity(it) }
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.height(4.dp))
        Text("MOTOR ACTIVO", color = Color(0xFF88888F), fontSize = 10.sp, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.Start))
        EngineSelection(service)
    }
}

@Composable
private fun BoxPulse(active: Boolean) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = .16f) else AccentRed.copy(alpha = .18f)),
        contentAlignment = Alignment.Center
    ) { Text("●", color = if (active) Color.White else AccentRed, fontSize = 20.sp) }
}

@Composable
private fun EngineSelection(service: EpicenterGlobalAudioService?) {
    var headphones by remember(service) { mutableStateOf(service?.isHeadphonesMode() ?: true) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171719), contentColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (headphones) "Audífonos / altavoces" else "Car Audio / subwoofer", fontWeight = FontWeight.SemiBold)
                    Text(if (headphones) "Motor de graves para drivers pequeños" else "Motor clásico de restauración de subgraves", color = Color(0xFF9999A0), fontSize = 11.sp)
                }
                Switch(
                    checked = headphones,
                    onCheckedChange = { headphones = it; service?.setEngineMode(it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = AccentRed, checkedThumbColor = Color.White)
                )
            }
            Text("Son motores alternativos: se activa uno a la vez, según el código DSP original.", color = Color(0xFF88888F), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun EffectsContent(service: EpicenterGlobalAudioService?, intensity: Float, onIntensity: (Float) -> Unit) {
    val headphones = remember(service) { service?.isHeadphonesMode() ?: true }
    var sweep by remember(service) { mutableStateOf(service?.currentSweepFrequency() ?: 45f) }
    var width by remember(service) { mutableStateOf(service?.currentWidth() ?: 50f) }
    var balance by remember(service) { mutableStateOf(service?.currentBalance() ?: 100f) }
    var volume by remember(service) { mutableStateOf(service?.currentVolume() ?: 100f) }
    var presetName by remember { mutableStateOf("") }
    Column {
        PageTitle("Controles DSP", if (headphones) "Motor de audífonos · intensidad en Inicio." else "Motor clásico · ajusta las perillas del núcleo.")
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171719), contentColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                if (!headphones) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        GlobalKnob("Sweep", sweep, 27f, 63f, "Hz", 120.dp) { sweep = it; service?.setSweepFrequency(it) }
                        GlobalKnob("Width", width, 0f, 100f, "%", 120.dp) { width = it; service?.setWidth(it) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        GlobalKnob("Balance", balance, 0f, 100f, "%", 120.dp) { balance = it; service?.setBalance(it) }
                        GlobalKnob("Volume", volume, 0f, 100f, "%", 120.dp) { volume = it; service?.setVolume(it) }
                    }
                } else {
                    Text("Intensidad actual: ${intensity.toInt()}% · ajustable con la perilla principal en Inicio.", color = Color(0xFF9999A0), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("PRESETS DSP", color = Color(0xFF9999A0), fontSize = 10.sp, letterSpacing = 1.4.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            listOf("Suave", "Fuerte").forEach { name ->
                TextButton(onClick = { onIntensity(service?.loadPreset(name) ?: if (name == "Suave") 35f else 90f) }) { Text(name, color = AccentRed) }
            }
            service?.customPresetName()?.let { name ->
                TextButton(onClick = { onIntensity(service.loadPreset(name)) }) { Text(name, color = AccentRed) }
            }
        }
        OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(32) }, label = { Text("Nombre del preset") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { service?.saveCustomPreset(presetName) }) { Text("Guardar preset personalizado", color = AccentRed) }
        Spacer(Modifier.height(8.dp))
        Text("EQ de 31 bandas y reverberación están en el proyecto anterior para el reproductor integrado; aún no están conectados a esta ruta global.", color = Color(0xFF9999A0), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun GlobalKnob(label: String, value: Float, minimum: Float, maximum: Float, unit: String, knobSize: androidx.compose.ui.unit.Dp, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${value.toInt()}$unit", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        KnobControl(
            label = label,
            value = value,
            minValue = minimum,
            maxValue = maximum,
            unit = unit,
            size = knobSize,
            showValue = false,
            onValueChange = onChange
        )
    }
}

@Composable
private fun CompatibilityContent(state: GlobalAudioState) {
    Column {
        PageTitle("Compatibilidad", "Diagnóstico del dispositivo y de la ruta global.")
        DiagnosticRow("Versión Android", "API ${android.os.Build.VERSION.SDK_INT}", if (android.os.Build.VERSION.SDK_INT >= 29) "Disponible" else "No compatible")
        DiagnosticRow("AudioPlaybackCapture", "Android 10+ y permiso de MediaProjection", if (android.os.Build.VERSION.SDK_INT >= 29) "Disponible; requiere consentimiento" else "No disponible")
        DiagnosticRow("Motores Epicenter", "Núcleo clásico + modo audífonos vía JNI", "Integrados")
        DiagnosticRow("Fuente externa", state.message, when (state.status) {
            GlobalAudioStatus.CAPTURING -> "Recibiendo señal"
            GlobalAudioStatus.ERROR, GlobalAudioStatus.LIMITED -> "Limitado"
            else -> "No verificado"
        })
        DiagnosticRow("Foco de audio", "Solicitud opcional para atenuar fuentes compatibles", "Experimental")
        DiagnosticRow("Ruta de salida", "La fuente puede seguir sonando junto al audio procesado", "Limitación Android")
        Spacer(Modifier.height(14.dp))
        Text("La solicitud de foco no controla todas las apps. Comprueba el nivel de señal capturada en Inicio para ver si la fuente sigue llegando al DSP.", color = Color(0xFFBEBEC4), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SettingsContent() {
    Column {
        PageTitle("Ajustes", "EpicenterDSP Global · prototipo técnico")
        DiagnosticRow("Suscripción", "Google Play Billing", "Pendiente")
        Text("La compra y restauración requieren configuración de Play Console y verificación segura de derechos en un servicio. No se habilitan cobros ni acceso premium en esta compilación.", color = Color(0xFFBEBEC4), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(18.dp))
        DiagnosticRow("Marca", "Logo, icono y paleta heredados de Epicenter", "Reutilizados")
        DiagnosticRow("Procesamiento", "PCM 16-bit · estéreo · 48 kHz", "Prototipo")
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    Text(subtitle, color = Color(0xFF9999A0), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))
}

@Composable
private fun DiagnosticRow(title: String, detail: String, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171719), contentColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(status, color = if (status.contains("No ") || status == "Limitado") Color(0xFFFF7780) else AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(detail, color = Color(0xFF9999A0), fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
