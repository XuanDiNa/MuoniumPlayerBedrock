# Optional BSChat voice and music transport

The user approved the optional BSChat direction after the source feasibility review.
Implement in this existing worktree without subagents, game installation, EXE changes,
or replacing the accepted 0.16.3 artifacts.

## Boundary

Target Windows x64, LeviLamina 26.60.7. Pin BSChat to
31e1dfc7cdf753949e06444ee483a40e54d31055 (26.6). Keep its AGPL source and
changes in an independent integration. Do not copy BSChat implementation into the
Apache host. Preserve its game packet transport, protocol and server permissions.
Both endpoints need BSChat; ordinary servers and vanilla Muonium remain usable.

## Data flow

Reuse Muonium's final EngineOutputTap. A default-off broadcast setting captures
48 kHz output, downmixes to mono, and pushes into a bounded in-memory SPSC queue.
No network, file or Win32 calls occur in this audio callback. A daemon worker
publishes 20 ms PCM frames through a versioned, process-specific Windows mapping.
BSChat reads it without waiting, mixes music with only permitted microphone audio,
and sends one Opus stream through the existing game connection. No loopback/system
audio capture, external relay, microphone auto-enable or persistent media files.

The BSChat audio worker also emits silent capture frames when no microphone exists,
so music does not require a microphone. Music never enables a microphone: PTT/VAD
and captureEnabled remain the microphone gate. Voice DSP applies to microphone;
music bypasses speech AGC. Total output is finite and clipped. Defaults have no
bound hotkeys; users configure BSChat keys themselves.

## Lifecycle and UI

Mapping ownership is the game process. Header includes version, size, sample
format, process ID, session generation, handshake-ready status and heartbeat.
Only a ready, fresh session accepts music. On disconnect/rejoin, increment the
generation, clear pending samples, and require fresh PCM; never replay old audio.
Stop/mute/pause and stale publishers release music transmission. Focus/UI changes
do not interrupt music. No BSChat installed means a quiet unavailable status and
no effect on local playback. HUD editor exposes broadcast, gain, status and an
explicit BSChat-settings button. Opening the HUD does not start broadcasting or
open the microphone.

## Verification

Test bounds, stereo fold-down, invalid PCM, default-off, session reset, stale
heartbeats, lock contention, disconnect, microphone privacy, music without a mic,
actual Opus encode/decode and the existing player regressions. Build actual
26.60.7 client/server plugins before calling the integration runnable. If upstream
SDK build fails, retain exact diagnostics and state that boundary explicitly.
Offline verification is not two-player game acceptance.
