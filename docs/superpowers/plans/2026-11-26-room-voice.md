# Client-only room voice implementation plan

**Goal:** Voice and current-music broadcast over the existing explicit room host.
**Architecture:** Additive room frames, bounded host relay, independent Opus codec
and Java Sound devices, passive HUD controls. Execute inline, no subagents.
**Tech:** Java21, JNA, Opus1.5.2, existing TCP/WebSocket tunnel, Gradle/xmake.
**Spec:** ../specs/2026-11-26-room-voice-design.md (user approved approach in chat).

## Constraints

No game-server mod, Minecraft packets, game hooks/layout changes, installation or
publication. Preserve all existing unrelated edits and frozen0.17.1/0.18.0 files.
Never modify pinned vendor sources; use prepare_jvm_sources.py adapters.
Microphone, room voice and music broadcast default off; no capture before consent.

## Steps

- [x] Codec: add failing RoomVoiceCodecTest for20ms encode/decode and malformed
  input; run the red test. Implement native/room-voice/Codec.cpp and xmake.lua
  (create/encode/decode/destroy ABI), RoomVoiceCodec.java JNA adapter; build and
  verify real, finite non-silent Opus roundtrip without audio endpoints.
- [x] Transport: add RoomVoiceTransportTest for same-room forwarding, identity
  derivation, no self echo, epoch invalidation, rate/size limits and queue bounds.
  Implement RoomVoiceRouter.java and RoomVoiceWire.java. Frame op names:
  room_voice_session, room_voice_audio; connection advertises bedrockVoice=1.
  Frame epoch is a host-issued UUID, seq nonnegative, data bounded Base64 Opus.
  Run red then green tests with controlled monotonic clocks.
- [x] Lifecycle/audio: add RoomVoiceTest with owned fake endpoints proving default
  off, explicit mic consent, pause/mute, disconnect/rejoin and focus independence.
  Implement RoomVoice.java plus RoomVoiceDevices.java using a dedicated capture
  worker, bounded queue and output worker. Reuse BroadcastPcmQueue for music.
- [x] Wire generated DirectServer/DirectClient through tools/room_voice_overlay.py;
  bind/unbind by connection owner, publish membership on room engine tick, route
  only negotiated voice, use independent bounded asynchronous frame writers.
  Add real loopback/tunnel integration tests. Retain legacy room operations.
- [x] Replace active HUD BSChat controls with client-room controls via source
  adapter. Add explicit voice-settings button to Together Listen UI; offscreen
  render/click/passivity tests and EngineOutputTap wiring test.
- [x] Run targeted codec/router/lifecycle/tunnel/HUD tests plus existing audio,
  room and input regressions,34 native fixtures and builds. Reconcile source
  changes by comparing frozen host/native/vendor inputs and executable bytecode.
- [x] Version0.19.0, package vanilla/LL full+update and source with codec/license,
  no BSChat addon, reopen/hash/audit archives. Document manual two-client tests
  and host/tunnel/AEC limitations; preserve installed games and old candidates.

## Same-task user follow-up

- [x] Add microphone and deafen chords, unbound by default, by extending existing
  configuration and appending private events14/15. This is a narrow expansion of
  native controls requested by the user; no game hooks or layouts changed.
- [x] Deafen stops both receive and all send paths, preserving microphone/music
  choices. Add background input/output device enumeration and explicit selection.
- [x] Verify the actual Windows events and real ModMenuBE passive settings parser
  with ten chords, Apply/Cancel and existing settings preserved.
- [x] Reproduce and repair packet sequence reset after audio-worker recovery;
  verify the shipped JAR resolves its codec from an unrelated working directory.

Manual physical microphone, two-computer networking and Minecraft acceptance
remain pending; synthetic endpoints and loopback tests do not substitute for them.

## Completed offline verification

166 JVM tests across nine tasks and34 native Release suites passed; real
ModMenuBE passive-settings interoperability passed. The final audio-only rerun
also passed after removing repeated idle queue allocation. Five archives were
reopened, manifests/hashes checked and public-asset audits passed. Frozen0.17.1,
0.18.0 and all1874 vendor files remained unchanged.

Candidate: the project's releases/0.19.0-20261126 directory.
The packaged source is the snapshot before final archive audit; final results
are in that directory's VERIFICATION.json and PUBLIC-ASSET-AUDIT.json.
No game installation, launch, microphone capture or publication was performed.
