# EpicenterDSP: viabilidad de inserción global en Android

## Conclusión

Una app Android convencional no tiene una API pública para colocar un motor C++ propio en la salida de AudioFlinger de otras apps, leer sus sesiones y sustituir cada señal antes del altavoz. `AudioPlaybackCapture + AudioTrack` es una segunda ruta de salida: la captura copia PCM, mientras la app fuente conserva su reproducción. Sin modificar/controlar la fuente, no hay garantía de una sola señal audible.

El prototipo viable para una app convencional es insertar los dos motores originales dentro del pipeline PCM del reproductor Epicenter: decoder → `EpicenterAudioProcessor` JNI → `DefaultAudioSink` → un `AudioTrack`/sesión de Epicenter. Para apps de terceros la ruta técnicamente viable es integrar el efecto como componente del sistema/vendor (o una ROM que lo incluya), no instalarlo dinámicamente desde la app. La prueba de sesión propia de este proyecto no acredita procesamiento global.

## Matriz de viabilidad

| Ruta | App convencional sin root | Con root / ROM propia | OEM / permisos del fabricante | Alcance y resultado |
| --- | --- | --- | --- | --- |
| Media3 `AudioProcessor` con `EpicenterDSPCore` y `HeadphonesBassCore` | Sí | Sí | Sí | Solo audio que reproduce Epicenter; la salida es un único PCM ya procesado. |
| `AudioEffect` público adjunto al ID de una sesión | Sí, si la plataforma ofrece el tipo de efecto | Sí | Sí | Solo ese `AudioTrack`/`MediaPlayer` y su sesión. Los UUID/tipos corresponden a efectos registrados por Android/OEM; no registra motores nuevos. |
| `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` | Sí, como notificación opcional al controlador de efectos del dispositivo | Sí | Sí | Pide a una app controladora que recupere/preferencias y adjunte sus efectos a la sesión que abrió el reproductor emisor. No crea una ruta global ni instala el DSP emisor. |
| `AudioEffect` sobre sesión global 0 | No es una base fiable ni recomendada para producto | Posible solo si el build/dispositivo lo permite | Depende del OEM | El uso de efectos insert sobre la mezcla global está obsoleto. `MODIFY_AUDIO_SETTINGS` no convierte un `.so` de la app en efecto de AudioFlinger. |
| Efecto C++ compatible con el Effect HAL/ABI, declarado en `audio_effects.xml` | No: no existe API pública de app para registrar/cargarlo en AudioFlinger | Posible en imagen/dispositivo modificable; root no garantiza que SELinux, verificación de arranque y HAL lo admitan | Sí, con integración y pruebas de plataforma | Puede ejecutarse en una ruta de framework/HAL compartida si el OEM la configura y la ruta usa ese efecto. |
| Efecto de dispositivo Android 11+ | No: constructor asociado es `@SystemApi` y requiere efecto ya provisto/configurado | Solo mediante integración con sistema/vendor | Sí | Se asocia a dispositivo/ruta configurada; no significa automáticamente todas las apps ni todas las rutas. |
| Capturar reproducción de terceros y volver a escribirla | Consentimiento MediaProjection y `RECORD_AUDIO`; sujeto a política de captura | Sí, con los mismos límites de captura si no se altera la ROM | Sí | Es copia, no sustitución. La fuente sigue en la salida, por lo que puede duplicarse. No usar como solución de “original = 0”. |

## API de sesión y `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`

La API pública `AudioEffect` permite crear/controlar clases derivadas conocidas (por ejemplo `Equalizer`, `BassBoost`, `DynamicsProcessing`) y especificar el ID de sesión del `AudioTrack` o `MediaPlayer` propietario. El propio framework crea/usa un motor de ese tipo para esa sesión. El derecho de control del objeto depende también de prioridad/propiedad del efecto. La app no obtiene acceso a la sesión de audio arbitraria de otra app ni puede remplazar su flujo de buffers.

`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` significa “mi reproductor abrió esta sesión; controlador de efectos, aplica aquí los ajustes guardados”. `EXTRA_PACKAGE_NAME` y `EXTRA_AUDIO_SESSION` son obligatorios. El receptor puede crear y habilitar sus efectos registrados en esa sesión; no hay callback de PCM ni mecanismo de instalación de plugin. Por tanto, enviarlo no inserta EpicenterDSP dentro de otra app.

Para la sesión propia tampoco se debe asumir que todos los dispositivos tienen todos los efectos. `AudioEffect.queryEffects()` permite consultar los efectos que informa la plataforma. El prototipo de abajo no depende de un efecto genérico OEM: usa ambos motores C++ originales en el processor propio, con una sola salida Media3.

## Efecto nativo, AudioFlinger y acceso de terceros

Los efectos de AudioFlinger/Effects HAL se descubren a partir de implementaciones del sistema y sus descriptores. La integración de plataforma declara bibliotecas UUID/nombre en `audio_effects.xml`; los efectos de dispositivo requieren configuración del fabricante y, en su API de control, usan un constructor `@SystemApi`. Una app no puede poner su biblioteca en `/vendor`, alterar esa configuración ni conceder al proceso AudioFlinger la carga de su biblioteca.

En una ROM controlada, el adaptador del efecto C++ Epicenter tendría que implementar el contrato de efecto Android (crear/destruir instancia, descriptor/UUID, comandos/parametrización y procesamiento de buffers), delegar el bloque PCM a `EpicenterDSPCore` o `HeadphonesBassCore` según modo y exponerse en `audio_effects.xml`/Effect HAL. Para cubrir reproducción arbitraria, el OEM debe insertarlo en la ruta de mezcla pertinente y verificar mezclas, offload, direct output, Bluetooth, USB, spatializer, llamadas y cambios de dispositivo. AudioFlinger no puede procesar un stream que el efecto HAL/ruta del dispositivo evita.

Root es una condición práctica para experimentar en un equipo desbloqueado, no una API estable ni garantía: puede hacer falta una imagen compatible, cambios de partición vendor/system, políticas SELinux, ABI HAL exacta y desactivar/reconfigurar verificación de arranque. La ruta sólida de producto es que el fabricante incorpore el motor y la configuración a su build, o distribuir una ROM propia para dispositivos concretos.

## Android 11–16

| Versiones | Lo que sí ofrece la plataforma | Implicación para EpicenterDSP |
| --- | --- | --- |
| Android 11–13 (API 30–33) | Efectos por sesión y efectos ligados a dispositivo en la arquitectura del sistema desde Android 11. El efecto de dispositivo lo integra el vendor; el control de dispositivo usa API de sistema. Audio HAL previo a 14 usa HIDL. | En app normal, solo sesión propia con API pública o processor dentro del reproductor. Global exige integración system/vendor. |
| Android 14–16 (API 34–36) | Mismo límite de API pública por sesión. Android 14+ define Audio HAL por AIDL; las APIs HAL nuevas se limitan a esa interfaz. | La migración HIDL→AIDL cambia la implementación del plugin de plataforma, no concede al APK permiso de inserción global. Hay que compilar/probar por imagen y vendor. |

Esta clasificación refleja el límite arquitectónico documentado para Android 11–16; la presencia, configuración, codecs/offload y comportamiento audible de efectos concretos siguen dependiendo del dispositivo/fabricante. Android 16 no añade una API pública para que un APK reemplace la ruta de salida de terceros.

## Permisos y Google Play

| Operación | Acceso/autorización real |
| --- | --- |
| Efecto por sesión en el reproductor propio | El `audioSessionId` procede del `AudioTrack`/`MediaPlayer` propio y se adjunta un tipo de efecto soportado por plataforma. No confiere control sobre la sesión de otra app. `MODIFY_AUDIO_SETTINGS` es normal y permite modificar algunos ajustes globales; no concede carga de efecto personalizado ni silencio selectivo de terceros. |
| `AudioPlaybackCapture` | `RECORD_AUDIO` (permiso peligroso/runtime), consentimiento de MediaProjection, mismo perfil y fuente compatible/permitiendo captura. Esto entrega copia del audio. |
| Plugin en AudioFlinger, HAL o efecto de dispositivo | Integración de sistema/vendor, firma/privilegios de fabricante, configuración de plataforma y/o ROM/root. No se obtiene pidiendo más permisos en el manifest. |

Para publicar la funcionalidad de captura, Play requiere uso esperado, transparente y consentido de audio/datos sensibles y cumplimiento de las políticas de permisos y foreground service. La documentación de Play prohíbe saltarse el sandbox o usar privilegios elevados abusivamente. No hay una excepción de Play que habilite integrar un efecto vendor desde un APK; aunque una ROM con efecto global pudiera distribuirse por canales propios, la aprobación de Play de un APK convencional no resuelve el requisito técnico del sistema.

## Prototipo verificable en este repositorio

`NativePlaybackController` registra `EpicenterAudioProcessor`, `EqAudioProcessor` y `ReverbAudioProcessor` en el único `DefaultAudioSink` del reproductor Epicenter. `DspSinglePathPrototypeInstrumentedTest` manda un bloque PCM de 10 ms por el processor real y comprueba que sale exactamente un bloque, el contador confirma una pasada de DSP y la salida difiere de la entrada seca. Ese processor está conectado antes del único `AudioTrack` de la sesión de Epicenter: el reproductor no escribe paralelamente el bloque seco.

Comando de verificación desde `android/`:

```powershell
./gradlew.bat connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.epicenter.hifi.nativeaudio.DspSinglePathPrototypeInstrumentedTest'
```

La compilación de `app` y `androidTest` terminó correctamente. La ejecución en el AVD `Pixel_Tablet (API 35)` se detuvo antes de instalar y ejecutar el test: `INSTALL_FAILED_INSUFFICIENT_STORAGE` (337 MB libres en `/data`). Por tanto, el test aún no tiene resultado instrumental aprobado. Cuando haya dispositivo/AVD con espacio suficiente, el resultado validará procesamiento de PCM dentro del pipeline propio. No prueba el audio físico en todos los dispositivos, ni engancha Spotify/YouTube u otras sesiones, ni declara que el procesamiento global esté resuelto. Para ese objetivo hace falta una build de sistema/vendor y una prueba con el efecto integrado en el dispositivo final.

## Fuentes oficiales

- [Android `AudioEffect`](https://developer.android.com/reference/android/media/audiofx/AudioEffect) — efectos por sesión, control y acciones de sesión.
- [Android `Equalizer`](https://developer.android.com/reference/android/media/audiofx/Equalizer) — adjunción a una sesión concreta y sesión global 0 obsoleta.
- [Android Open Source Project: Audio effects](https://source.android.com/docs/core/audio/audio-effects) — `audio_effects.xml`, implementación vendor y efectos de dispositivo.
- [Android Open Source Project: Audio HAL](https://source.android.com/docs/core/audio/implement) — relación AudioFlinger/Core HAL/Effects HAL, HIDL y AIDL.
- [Android `Manifest.permission`](https://developer.android.com/reference/android/Manifest.permission) — nivel normal de `MODIFY_AUDIO_SETTINGS`.
- [Android: capturar reproducción de audio](https://developer.android.com/media/platform/av-capture) — consentimiento, permisos y límites de captura.
- [Google Play: políticas de desarrollador](https://support.google.com/googleplay/android-developer/answer/18258653) — permisos restringidos y abuso de privilegios elevados.
