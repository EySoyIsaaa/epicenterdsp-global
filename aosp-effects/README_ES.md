# EpicenterDSP como efecto AOSP

Este prototipo queda aislado del APK comercial. Lleva los dos motores C++ sin reescribirlos y agrega un adaptador AIDL `IEffect` para que el Effects HAL procese los bloques FMQ que entrega el framework. No captura reproducción, no crea un `AudioTrack` adicional y no utiliza ducking, audio focus ni controles externos de volumen.

## Componentes

- `engines/`: copias de `EpicenterDSPCore.cpp`, `EpicenterDSPCore.hpp` y `EpicenterHeadphonesCore.hpp`.
- `integration/hardware/interfaces/audio/aidl/default/epicenter/`: biblioteca AOSP `libepicenter_effect`, adaptador, UUID y fragmento de configuración.
- `stage_into_aosp.ps1`: copia el módulo bajo el árbol `hardware/interfaces` de Android 14+; se detiene si ya existe el destino.
- `product_integration.example.mk`: ejemplo para añadir la biblioteca al producto y elegir el motor.

El adaptador declara el tipo estándar `BassBoost` para reutilizar `strengthPm` (0–1000), y registra un UUID de implementación propio. `ro.vendor.epicenter.engine=classic` elige `EpicenterDSPCore`; `headphones` elige `HeadphonesBassCore`. El valor se lee al crear la instancia del efecto. Los valores de intensidad se mapean a 0–100; cero hace bypass dentro de la misma cadena. Los motores y sus fuentes no se modifican.

El DSP de salida usa deintercalado/intercalado en buffers preasignados de hasta 8192 frames por bloque. Cuando el FMQ entrega un bloque mayor, se divide en fragmentos sin asignar memoria durante el procesamiento. El efecto devuelve al framework el buffer procesado con el mismo número de muestras consumidas/producidas: existe una sola ruta audible.

## Registro y enrutamiento

La biblioteca exporta `createEffect`, `queryEffect` y `destroyEffect` (el último lo aporta `:effectCommonFile` de AOSP). `IFactory` carga la biblioteca indicada por UUID en la configuración AIDL y consulta su descriptor. La entrada se llama `bassboost` porque AOSP mapea ese nombre al UUID de tipo estándar; el efecto conserva su UUID de implementación propio. En la imagen de prueba hay que sustituir la definición existente de `bassboost` (sin dejar duplicados con el efecto stock) o integrar el UUID bajo un nuevo nombre en `kUuidNameTypeMap`. El producto debe compilar e instalar `libepicenter_effect.so` en `vendor/lib/soundfx` o la ruta de arquitectura correspondiente, fusionar la biblioteca/efecto del XML y arrancar el servicio Effects HAL AIDL del producto.

El fragmento aplica el efecto al `stream type="music"`, por lo que la política de AudioFlinger lo agrega al procesamiento de salida de música de la imagen de sistema. Hay que fusionarlo con el XML de efectos ya existente y poner el fragmento en la ruta que consume ese producto. No se debe reemplazar el XML del fabricante. En equipos con salida directa/offload o DSP del fabricante, la política puede usar otra ruta; esa ruta debe quedar explícitamente en PCM/software o debe integrarse el efecto en el proveedor de HAL. El prototipo no afirma cubrir todas las rutas ni todas las aplicaciones en cualquier Android.

`Flags::Type::INSERT` es intencional: ejecuta esta biblioteca software en una cadena de efectos normal. `POST_PROC` AIDL describe un efecto aplicado debajo del HAL a la salida del dispositivo; requiere soporte e integración de efectos de dispositivo en el HAL y no lo activa por sí solo una app. Si se busca ese nivel físico de procesamiento, el fabricante debe adaptar/registrar este mismo contexto en su ruta de dispositivo y marcarlo como efecto de dispositivo.

## Viabilidad

| Ruta | ¿Procesa audio de otras apps? | Requisitos | Resultado esperado |
|---|---:|---|---|
| App Android convencional / Google Play | No de forma global | AudioEffect público solo puede controlar efectos/sesiones permitidos; no puede registrar una biblioteca en AudioFlinger/HAL | No resuelve el audio duplicado de captura+reproducción |
| Efecto AIDL de software con XML `postprocess` | Sí, para los streams/rutas configurados por la imagen | Compilar el módulo en AOSP/producto, instalarlo en vendor/system según diseño, actualizar XML, servicio AIDL operativo y reflashear imagen | Prototipo de una sola ruta FMQ/AudioFlinger; validar offload y salidas por separado |
| Efecto AIDL tipo `POST_PROC` / `deviceEffects` | Sí, si el HAL del dispositivo lo implementa | Cambios de HAL/vendor, configuración por dispositivo, permisos y política VINTF/SELinux del producto | Inserción más abajo, dependiente de cada fabricante |
| Root/Magisk sin soporte del fabricante | No garantizado | Acceso privilegiado no reemplaza la ABI, servicio, XML ni compatibilidad de HAL; posible modificación de particiones | Experimental y frágil; no es una solución mantenible |
| ROM / compilación AOSP de dispositivo | Sí en rutas integradas | Árbol de dispositivo, kernel/vendor blobs compatibles, build de sistema/vendor y flasheo | Ruta recomendada para prueba controlada |

`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` es una notificación para que una app reproductora pida a un servicio de control de efectos que recupere/cree efectos para **su ID de sesión de audio**. Requiere el nombre de paquete y el ID de sesión. No registra una biblioteca nativa, no intercepta sesiones de otras apps y no proporciona una inserción global. El permiso `MODIFY_AUDIO_SETTINGS` tampoco otorga acceso para cambiar el Effects HAL del sistema.

### Requisitos de registro en AOSP

1. El producto necesita el Effects HAL AIDL `android.hardware.audio.effect.IFactory/default` y su VINTF/service configuration. Android 14 y posteriores usan AIDL para las nuevas implementaciones; Android 11–13 suelen usar Effects HAL HIDL. Un módulo AIDL no es drop-in para una imagen Android 11–13.
2. `Android.bp` compila la biblioteca contra los `aidlaudioeffectservice_defaults` y `effectCommonFile` del mismo árbol AOSP. No basta compilarla con NDK/SDK de una aplicación.
3. Incluir `libepicenter_effect` en `PRODUCT_PACKAGES`, configurarla en el XML de efectos que consume ese producto, conservar los XML/UUID de OEM y sustituir de forma controlada la entrada `bassboost` stock (o añadir el nuevo nombre/UUID al mapa AOSP `kUuidNameTypeMap`).
4. La biblioteca y el servicio necesitan permisos de lectura/ejecución de vendor y etiquetas SELinux adecuadas. La configuración normal de un producto AOSP ya define sus ubicaciones; una ubicación fuera de las reglas de partición requiere políticas del fabricante. No hay un permiso runtime de APK que habilite este registro.
5. Para cargarla en un producto comercial se necesita acceso al árbol de producto/firmware y a la cadena de build/firma/flasheo del fabricante. El bootloader desbloqueable por sí solo no da acceso a fuentes vendor ni garantiza la ruta de AudioFlinger.

### Android 11–16 y publicación

- Android 11–13: interfaz Effects HAL típicamente HIDL; integrar el adaptador implicaría portar el envoltorio a HIDL o usar una ROM que haya actualizado el HAL. El algoritmo DSP puede conservarse.
- Android 14–16: Effects HAL AIDL es la ruta de integración apropiada para dispositivos nuevos, con diferencias de versión de AIDL y de configuración que obligan a compilar contra el branch exacto.
- Google Play: una aplicación distribuida normalmente no puede instalar bibliotecas en `vendor/lib/soundfx`, editar la configuración de AudioFlinger, registrar un servicio HAL ni cambiar SELinux/VINTF. Una app firmada con la clave de plataforma, preinstalada como privilegiada o con permisos especiales depende del fabricante/ROM; root no es un permiso de Play ni hace compatible el módulo. La versión comercial puede distribuir una UI/controlador, pero la integración global necesita firmware del sistema.

## Compilación y validación en AOSP

En Windows actual no hay checkout AOSP, Soong, `repo` ni `adb` disponibles; solo están instalados SDK platforms android-34/35/36. Por eso no se puede compilar ni ejecutar VTS aquí. El fuente y el montaje quedan listos para validación en un checkout AOSP compatible.

1. Usa una máquina Linux x86-64 con los requisitos oficiales de AOSP y un árbol Android 14–16 que contenga `hardware/interfaces/audio/aidl/default/Android.bp`.
2. Ejecuta desde PowerShell: `./aosp-effects/stage_into_aosp.ps1 -AospRoot C:\ruta\aosp` (o copia el directorio equivalente manualmente en Linux). Revisa diff y adapta solo la integración de producto si el branch renombró APIs/targets.
3. Añade `product_integration.example.mk` al producto de pruebas. Fusiona el fragmento `audio_effects_config.fragment.xml` con el archivo activo del producto, incluyendo la biblioteca, el efecto y el `<postprocess><stream type="music">...`. Verifica en el código/propiedades del producto cuál XML carga su `IFactory`; no reemplaces el XML existente.
4. Construye `m libepicenter_effect` y la imagen/producto de prueba. Los errores de API AIDL/headers/flags entre ramas se corrigen contra ese branch, no cambiando el core.
5. Corre `atest VtsHalAudioEffectFactoryTargetTest VtsHalAudioEffectTargetTest VtsHalBassBoostTargetTest` en el dispositivo de prueba y reinicia la imagen. Comprueba `dumpsys media.audio_flinger`, `dumpsys media.audio_policy` y logs `AHAL_Epicenter`/`EffectFactory` para confirmar descubrimiento, creación y cadena `music`.
6. Para comprobar una sola salida, reproduce un tono PCM de referencia desde otra app a volumen normal con el efecto desactivado/activado y graba la salida física mediante loopback de laboratorio o fixture de audio externo (no `AudioPlaybackCapture`). Verifica que activado cambia la señal y que no aparece una segunda copia con retardo/fase; prueba speaker, auriculares, Bluetooth, USB y offload individualmente. Mide latencia de ida y vuelta y carga CPU en el dispositivo.
7. Alterna `ro.vendor.epicenter.engine` entre `classic` y `headphones`, reiniciando/recreando la instancia, y repite. El VTS confirma contrato del HAL, no confirma que una ruta de reproducción esté conectada ni que el DSP suene bien.

## Dispositivos de prueba y límites

- **Primera opción:** Cuttlefish/AOSP userdebug con Effects AIDL de referencia y salida virtual. Permite compilar/flashear producto y verificar la cadena de framework. La validación audible necesita loopback compatible o una interfaz de audio conectada; “pasa VTS” no prueba la señal física.
- **Hardware de referencia:** dispositivo con árbol AOSP/device tree público y firmware vendor compatible con el branch. Necesita bootloader desbloqueable para flasheo y una combinación de vendor/system que arranque.
- **Android Emulator de SDK / imagen Play:** no permite registrar esta biblioteca en el HAL del sistema como un APK; no es una demostración de ruta global. Un AVD con imagen AOSP reconstruible puede servir solo si el producto permite cambiar vendor, servicio y configuración.
- **OEM cerrado:** sin árbol/configuración vendor no se puede asumir soporte aunque reporte Android 14, 15 o 16.

FMQ evita crear una copia audible, pero no implica latencia cero: el tamaño de bloque, hilo del efecto, AudioFlinger/HAL, remuestreo y buffers del dispositivo determinan el retardo. El adaptador preasigna los buffers de scratch y los cores no asignan memoria ni toman sus propios locks durante el procesamiento; `EffectImpl` sí serializa su estado según el contrato AOSP. El prototipo solo acepta mono/estéreo; para tasas/canales y formatos fuera del contrato AIDL de float32, AudioFlinger debe convertir o no podrá abrirlo. Los streams en offload/directo pueden saltarse la cadena software.

## Referencias primarias

- [AOSP: Audio effects](https://source.android.com/docs/core/audio/audio-effects)
- [AOSP: Implement the Audio HAL](https://source.android.com/docs/core/audio/implement)
- [AOSP: AIDL Audio HAL implementation](https://source.android.com/docs/core/audio/aidl-implement)
- [AOSP: AIDL/HIDL audio HAL comparison](https://source.android.com/docs/core/audio/aidl-hidl-comp)
- [AOSP `EffectImpl`](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/default/include/effect-impl/EffectImpl.h)
- [AOSP `BassBoostSw`](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/default/bassboost/BassBoostSw.cpp)
- [AOSP Effects AIDL XML config](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/default/audio_effects_config.xml)
- [AOSP Effects AIDL flags](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/audio/aidl/android/hardware/audio/effect/Flags.aidl)
- [Android API: `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`](https://developer.android.com/reference/android/media/audiofx/AudioEffect#ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
- [Google Play: permisos y APIs restringidos](https://support.google.com/googleplay/android-developer/answer/16558241)
