# Contexto de audio: Epicenter, EQ y efectos

## 1. Ruta de audio

```text
Archivo local / MediaStore / URI importado
  -> ExoPlayer (Media3: decodifica a PCM)
  -> DefaultAudioSink personalizado
  -> EpicenterAudioProcessor (PCM16 <-> float, análisis espectral, DSP JNI)
  -> EqAudioProcessor (31 filtros paramétricos tipo peaking)
  -> ReverbAudioProcessor (Reverb y Concert Hall)
  -> AudioTrack del sistema Android
```

El orden real se define en `nativeaudio/NativePlaybackController.java`. Los procesadores aceptan PCM signed 16-bit; el sink desactiva float output para que Media3 entregue un formato soportado. Epicenter trabaja primero, luego EQ y reverberación. La salida del DSP y de los efectos se limita al rango PCM antes de volver al dispositivo.

## 2. Epicenter DSP

### Archivos principales

- `cpp/EpicenterDSPCore.cpp/.hpp`: motor clásico para Car Audio/subwoofer, filtros, detección de graves, envolventes, síntesis y extensión profunda.
- `cpp/EpicenterHeadphonesCore.hpp`: variante para audífonos y altavoces pequeños.
- `cpp/EpicenterDSPJNI.cpp`: enruta bloques PCM intercalados al motor seleccionado.
- `dsp/EpicenterDSPNative.java`: ciclo de vida JNI y métodos nativos.
- `nativeaudio/EpicenterAudioProcessor.java`: puente Media3 PCM16 a float, controles y análisis de espectro.
- `engine/AudioEngine.kt`, `viewmodel/DspViewModel.kt`, `ui/screens/DspScreen.kt`: estado, valores y UI.

### Modos y controles

- `mode = car`: usa `EpicenterDSPCore`.
- `mode = headphones`: usa `HeadphonesBassCore`. Son motores alternativos: nunca se apilan. Al cambiar de modo ambos reinician el estado de filtros.
- Intensidad: 0–100%.
- Sweep: 27–63 Hz; modifica las frecuencias derivadas de detector/crossover/síntesis en el modo car.
- Width: 0–100%; cambia frecuencias derivadas, ancho de la región de graves.
- Balance y Volume: 0–100%; controles específicos del motor clásico.
- El modo audífonos usa la intensidad; los demás parámetros del modo clásico no se interpretan como controles equivalentes de ese núcleo.

La implementación C++ limita los canales procesados a L/R, conserva canales surround adicionales intactos y procesa en bloques. Los controles se publican con atomics y se aplican en el límite de bloque para evitar tocar el estado DSP desde el hilo UI. El reset de pista evita arrastrar envolventes/fase de la canción anterior.

## 3. EQ gráfico de 31 bandas

### Bandas

Frecuencias centrales (Hz):

`20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000`.

- Cada banda se implementa como filtro peaking biquad de 1/3 de octava en `nativeaudio/EqAudioProcessor.java`.
- Ganancia por banda limitada a ±8 dB; la UI/modelo expone el rango nominal ±12 dB, pero el procesador impone el límite operativo de ±8 dB.
- Preamp permitido: ±24 dB. Se calcula headroom de salida al editar bandas para reducir clipping.
- Estado por canal/filtro vive en el hilo de audio. Cambios de EQ se publican como snapshots inmutables y reinician el estado de una banda si cambia entre activa/inactiva.
- Presets y afinación automática se encuentran en `viewmodel/EqViewModel.kt`; auto-tune usa los 12 niveles del analizador para proponer una curva moderada y reduce preamp.

`ui/components/Equalizer31Band.kt`, `ui/screens/EqScreen.kt` y `viewmodel/EqViewModel.kt` conforman la interfaz y los controles. `AudioEngine.kt` persiste EQ y conecta la UI al procesador.

## 4. Reverb y Concert Hall

- Implementación: `nativeaudio/ReverbAudioProcessor.java`.
- Usa dos etapas estilo Freeverb en serie. Cada etapa tiene bancos comb y all-pass estéreo con buffers dimensionados a la frecuencia de muestreo.
- Reverb: room size 0.50, damping 0.50 y mezcla wet máxima 0.55.
- Concert Hall: room size 0.85, damping 0.25 y mezcla wet máxima 0.45.
- Los valores UI de amount van de 0 a 100%; se convierten a mezcla wet multiplicando por su límite máximo. Se puede habilitar cada efecto de manera independiente o ambos.
- La mezcla conserva una parte dry. Al aplicar ambos, el Hall recibe la señal que sale de la etapa Reverb.
- Tiene control de headroom interno hasta 12 dB, actualmente el controlador no lo enlaza automáticamente al preamp EQ.

UI: `ui/screens/FxScreen.kt`. Persistencia y ajustes: `engine/AudioEngine.kt`.

## 5. Analizador, reproducción y almacenamiento

- `nativeaudio/SpectrumAnalyzer.java` calcula 12 bandas para visualizar espectro y apoyar auto-EQ/auto-DSP.
- `NativePlaybackController.java` configura Media3, cola, audio focus, transiciones y los tres procesadores en el sink.
- `EpicenterPlaybackService.java` mantiene la sesión Media3/notificación y permite reproducción en segundo plano.
- `data/repository/MusicRepository.kt`, `FavoritesRepository.kt`, Room DAOs/entidades y `ui/screens/LibraryScreen.kt` implementan la biblioteca local.
- `AudioEngine.kt` guarda parámetros de DSP, EQ, efectos y crossfade en SharedPreferences.

## 6. Dependencias y límites para portar a otra app

La base Android usa Kotlin/Java 17, Jetpack Compose, AndroidX Media3 1.3.1, Room 2.6.1, Coroutines, CMake y C++17. Versiones de SDK y dependencias están en `android/variables.gradle` y `android/app/build.gradle`.

Al portar únicamente los motores de audio, conserva como unidad estos grupos:

1. C++ core(s) + JNI + CMake.
2. `EpicenterDSPNative` + `EpicenterAudioProcessor`.
3. `EqAudioProcessor` y `ReverbAudioProcessor` si también se requieren EQ y FX.
4. Construcción del AudioSink personalizado en `NativePlaybackController`.
5. Estado/control/persistencia que conecte los parámetros a la UI.

Si el reproductor destino usa otro pipeline de salida, no basta con copiar el DSP: hay que insertar los procesadores en su sink o callback PCM y mapear sample rate, canales, formato, ciclo de vida, cambios de pista y mute/bypass.
