# Beatster

Android drum-pad / sampler app. Tap pads to trigger drum samples, load your own sounds,
record and play back loops. Built with Kotlin + Jetpack Compose, with a low-latency native
audio engine on top of [Oboe](https://github.com/google/oboe) (C++ via CMake / JNI).

## Project layout

| Path | What it is |
|------|------------|
| `app/` | Compose UI, pads view model, Kotlin audio controllers |
| `audioengine/` | Native Oboe-based engine (`src/main/cpp/native_engine.cpp`) exposed through JNI |
| `app/src/main/assets/samples/` | Bundled drum kit (kicks, 808s, snare, clap, crash, hats) |
| `tools/samples/generate_samples.py` | Script that synthesises the bundled drum kit from scratch |
| `tools/android-skills/` | Git submodule with Android/Kotlin coding guidelines used during development |

## Building

Requirements: Android Studio (or the Android SDK + NDK + CMake), JDK 17+.

```bash
git clone --recurse-submodules https://github.com/jh0per/Beatster.git
cd Beatster
./gradlew :app:assembleDebug
```

If you cloned without `--recurse-submodules`, run `git submodule update --init`.
The submodule only contains documentation; the app builds without it.

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
