# Beatster

Android drum-pad / sampler app. Kotlin + Jetpack Compose UI in `app/`, native Oboe audio engine
(C++ via CMake, JNI) in `audioengine/`.

## Skills (required)

Android/Kotlin skills from https://github.com/jh0per/AndroidSkills are vendored as the git submodule
`tools/android-skills` and linked into `.claude/skills/`. Before any Android, Compose, Gradle, JNI,
media, testing or release work, load the matching skill and follow it. Most relevant here:
`native-interop`, `media-video-audio`, `compose-mvi-viewmodel`, `compose-ui-practices`,
`gradle-build-setup`, `android-testing`, `verification-discipline`.

Fresh clone: `git submodule update --init`. Update skills: `git submodule update --remote tools/android-skills`.
