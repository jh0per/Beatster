# Beatster

Android groovebox in the spirit of Maschine and the MPC: finger-drum on 16 pads, play melodies on
a synth keyboard, record your voice, add effects, and loop it all in clips of different lengths.
Built with Kotlin + Jetpack Compose on top of a low-latency native engine using
[Oboe](https://github.com/google/oboe) (C++ via CMake / JNI).

## What it does

- **Pads**: 16 velocity-ready pads with the bundled kit. Tap to play, several fingers at once.
  Per pad: volume, pitch, pan, one-shot or gate, choke groups (open/closed hat), loop. Load any
  audio file onto a pad, or hold the mic button to sample straight onto it.
- **Tracks and clips**: up to 8 tracks, each with its own clip length (1, 2, 4, 8 or 16 bars), all
  looping against one transport. Drum tracks play the pads, synth tracks play MIDI notes, audio
  tracks play recorded takes.
- **Recording**: tempo, metronome, one-bar count-in, quantize (1/4 to 1/32, triplets). Press record
  and tap pads or keys to record MIDI (overdub, with undo); on an audio track, record one clip of
  your voice from the next loop start, latency-compensated, and it loops.
- **Synth**: two detuned oscillators (saw, square, sine, triangle), ADSR, resonant filter, or play
  any pad's sample chromatically.
- **Effects** per track: filter (LP/HP/BP), drive, bit crusher, tempo-synced delay, reverb, plus
  volume, pan, mute and solo. A limiter on the master keeps the mix from clipping.
- **Projects**: save, save as, open and new. A project is a folder with `project.json` and the WAV
  files it uses, saved automatically when the app goes to the background.

## Project layout

| Path | What it is |
|------|------------|
| `app/.../engine/` | `AudioEngine` interface and `NativeEngine` (the JNI bridge) |
| `app/.../studio/` | `StudioController`: all studio logic, free of Android and unit-tested |
| `app/.../sequencer/` | Live MIDI recording and quantize |
| `app/.../model/`, `data/` | Project model (kotlinx.serialization) and the on-disk project store |
| `app/.../audio/` | WAV codec and MediaCodec-based decoder for loaded files |
| `app/.../ui/` | Compose screens: transport, tracks, pads, keyboard, clip view, sheets |
| `audioengine/src/main/cpp/core/` | Platform-independent engine: sampler, synth, sequencer, recorder, effects, mixer |
| `audioengine/src/main/cpp/android/` | Oboe streams (full duplex) and the JNI surface |
| `audioengine/src/test/cpp/` | Host tests for the engine core |
| `app/src/main/assets/samples/` | Bundled drum kit (kicks, 808s, snare, clap, crash, hats) |
| `tools/samples/generate_samples.py` | Script that synthesises the bundled drum kit from scratch |
| `tools/android-skills/` | Git submodule with Android/Kotlin coding guidelines used during development |

The audio thread never locks, allocates or frees: the UI sends commands through a lock-free queue,
and buffers it hands over come back through an event queue to be freed on the control side.

## Building

Requirements: Android Studio (or the Android SDK + NDK + CMake), JDK 17+.

```bash
git clone --recurse-submodules https://github.com/jh0per/Beatster.git
cd Beatster
./gradlew :app:assembleDebug
```

If you cloned without `--recurse-submodules`, run `git submodule update --init`.
The submodule only contains documentation; the app builds without it.

### Tests

```bash
./gradlew :app:testDebugUnitTest          # Kotlin: studio logic, recording, WAV, project store

# C++ engine core on the host (no Android SDK needed); add -DBEATSTER_SANITIZE=address or thread
cmake -S audioengine/src/test/cpp -B build/enginetests && cmake --build build/enginetests
build/enginetests/engine_tests
```

### Regenerating the bundled samples

```bash
python3 -m pip install numpy
python3 tools/samples/generate_samples.py
```

The script is deterministic (fixed random seed) and writes 16 mono 16-bit / 44.1 kHz WAV files
into `app/src/main/assets/samples/`. Tweak the parameters in the `SAMPLES` table to change the kit.

## License

- **Source code** (everything in this repository except the audio samples) is licensed under the
  [MIT License](LICENSE).
- **Bundled audio samples** in `app/src/main/assets/samples/` are generated procedurally by
  `tools/samples/generate_samples.py` and contain no third-party recordings. They are dedicated to
  the public domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) — use them
  freely, no attribution required.
- **Third-party components** keep their own licenses:
  - [Oboe](https://github.com/google/oboe) — Apache License 2.0
  - AndroidX / Jetpack Compose / Kotlin — Apache License 2.0
  - [AndroidSkills](https://github.com/jh0per/AndroidSkills) (submodule, docs only) — see that repository
