# Current-track noise cancellation

The expanded player exposes a non-persistent switch owned by `PlaybackService`.
It is available for seekable files/streams rendered on the device with LibVLC 3;
remote renderers and video playback do not use this output. Pause, resume, seeking, and UI recreation
keep the switch enabled. Track replacement, end (including repeat-one), stop,
and service teardown reset it.

`noise_output.cpp` resolves the public audio callback API from the packaged
LibVLC. `MediaPlayer.getInstance()` supplies the public native player pointer;
the bridge does not read private JNI structures. Each bridge retains its player.
The owner cancels output, stops the player to join callbacks, detaches callbacks,
then releases the bridge and player. Switching outputs creates a fresh player so
LibVLC's `amem` options cannot leak into ordinary playback. Pending restore state
tracks the latest user seek/play/pause request during an internal restart.

`NoiseOutput` runs synchronous inference on VLC's audio callback thread, using
48 kHz native-endian signed 16-bit mono PCM. It retains one model frame and a
bounded `AudioTrack` buffer, with no application PCM queue. Writes are
nonblocking, timestamp paced, and cancelable. Flush/route changes invalidate
in-flight results and reset model history on the audio thread. Pause retains
queued audio. Drain pads partial frames and flushes the bundled model's one-hop
STFT delay. The Audio Mixer has its own player and gain and never enters this
bridge.

The offline model and four ABI libraries come from
[`AndroidDeepFilterNet` 0.0.8](https://github.com/KaleyraVideo/AndroidDeepFilterNet).
The dependency is pinned; its `frameLength` is a byte count. Initialization uses
the public dispatcher/model-loader hooks synchronously on an IO caller, avoiding
the library's default unowned asynchronous initialization. Library/model
attributions are included in the existing libraries screen.

## Verification

- `:application:vlc-android:compileDebugKotlin`
- `:application:vlc-android:packageDebugResources`
- `:application:vlc-android:externalNativeBuildDebug` (arm64-v8a, armeabi-v7a,
  x86, x86_64)
- `:application:vlc-android:testDebugUnitTest --tests org.videolan.vlc.audio.PcmFramesTest`
- `:application:vlc-android:assembleDebugAndroidTest`
- `:application:app:assembleDebug`

The standalone library test APK uses a standard AndroidJUnitRunner and disables
Sentry auto-initialization in its test manifest. After installing it, run:

```sh
adb shell am instrument -w -r \
  -e class org.videolan.vlc.audio.NoiseOutputTest \
  org.videolan.vlc.test/androidx.test.runner.AndroidJUnitRunner
```

The tests cover frame boundaries/padding, native inference, pause/resume,
immediate seeking during restart, volume, sustained playback/underruns, drain,
track replacement/repeat boundaries, stale callbacks, and failure notification
followed by normal output. Synthetic noise energy is measured separately from
playback; it does not establish speech quality on discourse recordings.

On 2026-10-21, all three frame tests and all three instrumentation tests passed
on a Samsung SM-G991W (arm64). The sustained output check processed over 1.1
million samples with zero underruns. Synthetic noise output/input energy was
approximately 0.0000094. Kotlin, resource packaging, all four native ABI builds,
the test APK, and the complete debug app build also passed.

UI checks also passed with a real seekable discourse stream in portrait and
landscape: default-off state, enabling during playback, state across rotation,
and disabling while paused without moving the saved position. Artwork shrinks
to fit so the added switch does not clip the track title or subtitle.

This checkout's existing `UtilTests.kt` has syntax errors, and generated
`build_host` contains an unescaped resource reference on the verification host.
The new unit tests were run with a temporary Gradle init script restricting test
sources to `test/org/videolan/vlc/audio` and overriding only the generated
`build_host` value. Maven DNS also required a temporary local mirror of the
official 0.0.8 artifact. None of those workarounds changes project sources.

Before release, listen to real local recordings and discourse streams; verify
Bluetooth route switching, background playback, and simultaneous Audio Mixer
playback. Check speech quality and longer sessions
on lower-powered devices as well as the automated synthetic tests.
