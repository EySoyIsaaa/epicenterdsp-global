# EpicenterDSP Global — BASS-ONLY PARALLEL prototype

## What changed

The AudioPlaybackCapture source remains a copy and the source app keeps playing at its normal volume. The parallel AudioTrack now emits only a wet-generated bass stem; it no longer writes the processed full-range capture back to the speakers. Audio focus is not requested. The bass-only master switch is off by default.

| Engine | Extracted signal | Deliberately excluded |
|---|---|---|
| EpicenterDSPCore | Existing detector-driven `generatedSub` plus its deep-extension component, through the engine's sub low-pass and voice-protection shaping | Clean voice path, original `bassProgram`, body, dip, and master bass shelf |
| HeadphonesBassCore | Existing synthesized oscillator `genSub`, DC filter, and generated-stem dynamics | `rawSubLP`/`rawSub`, which was the original low-frequency signal, plus the separately processed full-range path |

Both engines remain intact and selected through the existing engine-mode control. After stem extraction a stateful adjustable biquad low-pass (50–100 Hz), gain (0–150%), polarity inversion, and causal delay (0–20 ms) are applied. `tanh` bounds digital peaks before conversion to PCM16. Turning the master switch off writes zeroes to the parallel track while leaving source playback alone.

## Build and automated checks

- `assembleDebug` and `testDebugUnitTest`: passed.
- Android instrumented suite on the Pixel 9 Pro XL virtual device (emulator Android 17 / API 37): 9/9 passed after updating an old package-name assertion to `com.epicenter.global`.
- New JNI signal test runs both engines on a 55 Hz + 1 kHz synthetic signal. It checks for generated bass energy, bounds, and strong suppression of the 1 kHz component after the configurable 80 Hz low-pass. It passed on the emulator.
- This verifies the native wet-stem logic and frequency rejection. It does **not** verify MediaProjection capture from a separate player app, speaker acoustics, Bluetooth, or physical loopback.

## Latency and alignment

The screen now reports an estimate from Android's monotonic `AudioRecord` and `AudioTrack` timestamps, plus a smoothed change between timestamp samples (queried every 10 seconds). This is capture-to-track timing telemetry, not a microphone/line loopback measurement of the original and added acoustic waveforms. Android documents that the record timestamp is from the earliest point available in the capture pipeline, and an AudioTrack timestamp can refer to data already presented or committed for presentation; it does not guarantee acoustic alignment. [AudioRecord timestamp API](https://developer.android.com/reference/android/media/AudioRecord#getTimestamp(android.media.AudioTimestamp,int)) [AudioTrack timestamp API](https://developer.android.com/reference/android/media/AudioTrack#getTimestamp(android.media.AudioTimestamp)) A routed-device name is shown when Android supplies one.

No physical loopback input, external audio interface, or captured source-player session was available during this build, so **there is no measured end-to-end original-versus-added latency result yet**. We must not infer synchronization from the native test or the requested low-latency AudioTrack mode. The added 0–20 ms delay is only a user-adjustable causal trim; it cannot advance the signal, measure phase, or correct all devices, codecs, rooms, or Bluetooth buffering. Changing output route can alter both latency and buffering; the app does not claim route-independent phase lock.

The requested `PERFORMANCE_MODE_LOW_LATENCY` hint is used on Android 8+; the framework/device may ignore it. The app reads/writes bounded 256-frame stereo blocks (5.33 ms at 48 kHz) with blocking I/O; this lowers app-side chunking delay and prevents an unbounded queue, but it is not the full path latency. The path still includes AudioPlaybackCapture, Java/JNI processing, the mixer, and selected output route. Bluetooth commonly has substantially more and variable latency than a wired output, so it needs separate measurements.

## A/B signal files

Generate locally authored, capture-safe PCM files from the Android project directory:

```powershell
python .\tools\generate_bass_only_test_audio.py --output-dir .\bass_only_test_audio
```

The set contains 55 Hz, 1 kHz, combined-tone, and short synthetic-music stimuli. Copy a WAV to the virtual device and open it in a separate media player that permits playback capture. Grant MediaProjection when prompted. Compare the source with the bass-only switch off and on; the 1 kHz tone should remain from the source only while the parallel output adds no intentional mid/high stem. Repeat at 50, 80, and 100 Hz, both polarities, and several delay values. Repeat separately on built-in speaker, wired headphones, and Bluetooth. Keep the master switch off if the captured source is silent, unstable, or visibly unsupported.

For real acoustic latency, record the original and Epicenter's added output together through an external stereo loopback/microphone interface, use a sharp synchronized transient or sweep, and report route, device, sample rate, offset, and drift over several minutes. The emulator's virtual speaker is not evidence for phone or Bluetooth timing.

## Known constraints

- AudioPlaybackCapture requires Android 10+, user MediaProjection consent, a capturable playback usage, and a source app that permits capture. DRM or app policy can yield silence.
- This is additive bass reinforcement: the original bass remains present in the source. The app cannot subtract it from another app's output.
- The timestamp estimate may be unavailable or inaccurate on particular HALs. The UI labels it as an estimate and displays no value until both timestamps are valid.
- Switching speaker/headphone/Bluetooth was not physically exercised in this environment. Android usually reroutes the AudioTrack, but format changes, Bluetooth codec delay, drift, and acoustic phase remain device-specific and unverified.
- Presets for phase/polarity should be selected by listening/loopback on the actual device. There is no universal best polarity or delay.
