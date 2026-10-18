# EpicenterDSP Global

Aplicación Android nativa para experimentar con **BASS-ONLY PARALLEL**: captura autorizada de audio elegible, extracción del bajo sintetizado por Epicenter y reproducción únicamente de ese stem en una salida paralela. La app de origen sigue reproduciéndose normalmente. No se vuelve a emitir la copia de voces, instrumentos ni el resto de la mezcla, y tampoco se elimina el bajo original de la fuente.

Se conservan los motores C++ originales `EpicenterDSPCore` y `HeadphonesBassCore`. La integración de captura usa MediaProjection + AudioPlaybackCapture, JNI y AudioTrack. No usa WebView, no pide audio focus ni cambia el volumen de otras apps. La función es experimental y depende de los permisos/política de captura de la app fuente; no promete inserción global en AudioFlinger ni alineación universal de fase/latencia.

## Compilar

Abre `android/` en Android Studio o compila con JDK 17 y Android SDK API 36:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd android
./gradlew.bat assembleDebug
```

El APK de depuración se genera en `android/app/build/outputs/apk/debug/app-debug.apk`.

## Funciones presentes

- Interfaz Jetpack Compose con el logotipo, icono y colores existentes de EpicenterDSP.
- Captura autorizada con MediaProjection + AudioPlaybackCapture, AudioRecord y PCM estéreo a 48 kHz.
- Stem wet-only de bajo generado por el motor C++ seleccionado: `EpicenterDSPCore` o `HeadphonesBassCore`; se omite el bajo original que el segundo motor mezclaba junto al sintetizador.
- Controles de salida adicional: master, pasa-bajas 50–100 Hz, ganancia, polaridad y retardo causal ajustable 0–20 ms.
- AudioTrack con hint de baja latencia, bloques PCM acotados y rampas para evitar golpes al activar/desactivar.
- Controles de intensidad y presets guardados localmente.
- Pantallas de inicio, efectos, compatibilidad y ajustes con estado explícito de captura.

## Límites y trabajo pendiente

Consulta [android/EPICENTER_GLOBAL_STATUS.md](android/EPICENTER_GLOBAL_STATUS.md) y [android/BASS_ONLY_PARALLEL_REPORT.md](android/BASS_ONLY_PARALLEL_REPORT.md) para las pruebas y límites. Android permite capturar solo ciertas reproducciones con consentimiento; algunas apps bloquean la captura o entregan silencio. La telemetría de AudioRecord/AudioTrack es una estimación, no una medición acústica de loopback. Altavoz, audífonos cableados y Bluetooth requieren mediciones independientes.

La rama `archive/aosp-effects` conserva por separado el prototipo experimental de integración del Effects HAL AIDL y su investigación. No forma parte de la aplicación convencional de la rama principal.
