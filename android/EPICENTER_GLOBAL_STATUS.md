# Estado de EpicenterDSP Global

## Arquitectura

La app es Android nativa. Conserva `EpicenterDSPCore` (clásico) y `HeadphonesBassCore`, seleccionables por JNI. La ruta paralela no intenta reemplazar ni mutear la reproducción de terceros. AudioPlaybackCapture entrega una copia elegible, de la que el modo wet-only extrae exclusivamente el bajo sintetizado para añadirlo a la reproducción de origen.

## Prototipo BASS-ONLY PARALLEL

La ruta de captura global puede iniciarse con consentimiento de MediaProjection. La app fuente sigue reproduciendo normalmente; el `AudioTrack` paralelo emite solo el stem de bajo generado por el motor seleccionado. El modo wet-only omite la ruta seca, voces/medios, y en HeadphonesBassCore también omite `rawSub`, que representa graves originales. El control está apagado por defecto. La app no pide audio focus ni modifica el volumen de otras apps.

Incluye pasa-bajas de 50–100 Hz, ganancia limitada, polaridad y retardo causal 0–20 ms. La demora no adelanta la señal y no implica sincronización: la fase y latencia dependen de Android, HAL, salida y acústica. El estado muestra una estimación de captura a AudioTrack a partir de timestamps monotónicos cuando están disponibles, no una medición acústica de loopback. La matriz, límites, resultados y procedimiento A/B están en [BASS_ONLY_PARALLEL_REPORT.md](BASS_ONLY_PARALLEL_REPORT.md).

## Verificación

Build y pruebas (requiere Gradle/JDK 17 y Android SDK; instrumentación también requiere AVD o dispositivo):

```powershell
./gradlew.bat assembleDebug testDebugUnitTest connectedDebugAndroidTest
```

Build verificado con `assembleDebug testDebugUnitTest` y suite instrumental Android 9/9 en Pixel 9 Pro XL AVD (API 37). La prueba wet-only sintetiza señales de referencia y verifica ambos motores y rechazo de 1 kHz; aún no valida una app fuente separada, loopback físico, Bluetooth ni altavoces reales.
