# Client-only room voice and music broadcast

The user approved the host-relayed, client-only approach in chat and explicitly
requested implementation. No additional game-server plugin or rented server.
Work inline without subagents, installation, game launch or publication.

## Transport and compatibility

Extend the existing explicit Direct room connection. Its TCP frames already pass
through the binary WebSocket quick tunnel. Advertise an additive voice capability;
only upgraded direct connections receive voice frames. Legacy hosts/members keep
music-room functionality. Never send voice into Minecraft networking.

The host authenticates with the existing room password, assigns a fresh voice
epoch to each connection/room membership, and derives sender identity from the
authenticated connection. Forward only to live, upgraded members of that exact
room, excluding the sender. Do not trust client-supplied sender/room fields.
Limit voice participation to eight connections per room, each <=55 frames/s and
400 Opus bytes/frame. Membership, reconnect, leave, kick and close invalidate the
epoch and pending audio. This is host relay, not a peer mesh or WebRTC.

Use small bounded voice queues (eight frames, <=200ms freshness) independently of
control frames. Each connection owns one asynchronous writer that prioritizes
control, drops expired voice, and closes a connection on control overflow or a
stalled write. Audio cannot build unbounded tasks or block the room engine.

## Audio and privacy

Independent Apache host code plus BSD Opus 1.5.2; no BSChat code is used in this
route. Build a small standalone JNA codec DLL with no game or loader imports.
48kHz mono, 20ms frames, 32kbps Opus. Receive each speaker through a bounded jitter
queue and per-speaker decoder, then mix/clamp for local Java Sound playback.

Room voice, microphone and music broadcast default off. Explicitly enabling room
voice opens receive output only; microphone opens only after the separate explicit
toggle and a negotiated active room. Leaving or disabling closes endpoints and
discards queued PCM. Do not open physical endpoints from tests by default.
Microphone capture runs on a dedicated thread; network/codec/mixing work runs off
the render/input thread. Music uses existing final EngineOutputTap with only a
bounded SPSC queue in its callback, never file/network/IPC operations.
Pause/mute stops music broadcast; microphone permission is independent. Focus and
UI changes do not restart devices or terminate an otherwise valid room session.
No system-loopback capture, recording, speech recognition, AEC or proximity model.

## UI and delivery

HUD editor/settings center exposes room-voice enable, mic enable, music broadcast,
send/playback gains and connection/error status. Opening the page is passive and
does not join a room, connect a tunnel or activate capture. Existing Together Listen
UI gains an explicit button opening the voice category.

Support the existing four vanilla targets and the separate LeviLamina target,
without changing game hooks/layouts or ModMenuBE. Preserve 0.17.1 and 0.18.0; ship
new 0.19.0 full/update/source candidates. Do not bundle the BSChat client/server.
Document that the host must stay online and tunnel quality affects latency.

## Verification

Red/green real codec tests, router authentication/epoch/room isolation/rate bounds,
bounded-writer freshness/control priority, microphone-default and lifecycle tests,
loopback Direct room + binary WebSocket relay tests, passive/offscreen HUD tests,
existing music/room/input regressions, actual host/native/codec builds and archive
manifest/hash/source/license audits. Manual microphone and two-device Internet
tests remain pending unless actually performed; never infer game acceptance.

## User follow-up accepted in the same task

Add two configurable microphone/deafen chords (unbound defaults), and selectable
microphone/headphones output. User selected deafen to stop both incoming and all
outgoing audio, restoring prior microphone/music choices on undeafen. Extend the
existing native binding/config and append two private events; no hook/layout or
ModMenuBE changes. Enumerate devices off-thread without opening hardware; missing
selected devices fail visibly rather than switching microphones silently.
