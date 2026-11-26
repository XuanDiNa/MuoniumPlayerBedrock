# Upstream 2.6.0 synchronization history

Current 0.15.0 uses official tag 2.6.0, commit
`eb541684e9d681d8145f5f9d681cf31980646dc7`. See [stable update](UPDATE-0.15.0.md).
The following sections retain the earlier development campaign as historical evidence.

## 0.14.0: upstream development synchronization

Pinned upstream: [dev cda0c7e9db4069844699d14c9fcfb43729bd4b2f](https://github.com/zihe114514/MuoniumPlayer-multiversion/commit/cda0c7e9db4069844699d14c9fcfb43729bd4b2f).
At the time of 0.14.0, upstream main remained 2.5.2. The selected dev sources advertise 2.6.0; this port
does not describe them as an upstream stable release. The historical vendor
directory name remains `MuoniumPlayer-2.5.2` to preserve build paths. Its manifest
records the current commit, SHA-256 and original Git blob identities.

## Included

- Player opening/closing and page motion, staged first opening and expressive
  prompts. Old/new UI and animation choices remain available.
- GPU page-content snapshots in the host-owned Skija context. Cached content is
  released before resize, target replacement or context shutdown. This does not
  add a CPU screenshot path to the transition cache.
- Redesigned music-card controls/effects and singing-clock lyric reveal.
  Capsule maximum width is independent, with upstream bounds of 160–720.
- Graphical EQ/workbench and current Folia automatic mixing. Automatic cueing
  waits until at least 5/7 of the outgoing duration; the intermediate SMART
  engine from development history is not exposed as a supported engine.
- Background WAV ring prefetch and isolated window warmup, shared vocal/downmix
  source unwrapping, and asynchronous SMTC write handling.
- Latest-wins acoustic sampling, world-context invalidation and direct-path DSP
  corrections using bounded immutable Bedrock observations.
- CineSession tick ownership and immutable render interpolation, new shot deck,
  smooth FOV/return behavior and scene-ratio options.
- Bedrock mini-player playback observation and updated actual control geometry.

## Camera activation investigation

The user's installed 0.13.0 runtime reported a ready native camera hook but no
valid commands. Host readiness repeatedly dropped while music continued.
The environment reader returned null on a nonblocking mutex miss, which resets
the idle countdown. A new regression covers a maximum 100ms reuse lease since
the last successful synchronized validation. Repeated contention cannot renew
that lease; publication expiry, explicit invalidation, world changes and clock
rollback revoke it. Native focus/input/health and command validation remain
unchanged. This repairs a demonstrated activation-reset path, but the user's
specific failure still needs gameplay confirmation.

Bounded CINEMATIC_HOST records now include gate, reset count/reason, epoch and
input serial. They contain no game addresses or account data.

## Scope retained

- Four vanilla versions share one package; LeviLamina 26.60 is separate.
- ModMenuBE remains optional and separately installed. Current local3/local4
  integration is retained; ordinary settings and user data are preserved.
- All shortcut defaults remain unbound. Next-track prefetch and durable media
  retention remain off by default. Streaming is retained.
- Public Noto font overrides and license/provenance records remain intact.
- D3D11/D3D12/native image and Arke guards are retained; no new hook/RVA is
  inferred from this Java update. This is not new D3D12 engine compatibility.

## Not implemented by this synchronization

- Java-only chat screen discovery/hover activation: the existing Bedrock IPC
  does not identify chat truthfully. Use the explicit mini-player/search entries.
- Java entity-following shots: the native cinematic transport supplies the
  local player and scenery; no entity target is fabricated.
- Entity-depth lyric occlusion remains pending. Existing voxel partial wall
  occlusion is retained with its existing limitations.
- Simple Voice Chat companion protocol and Java loader/mixin-specific features
  remain outside the Bedrock host.

Offline and owned-window checks do not establish gameplay acceptance or FPS gains.

## 0.14.1 correction candidate

The 0.14.0 gameplay report still had no camera commands. Its native input serial
changed continually during host-ready periods; the older counters did not say
whether physical events, held keys or actor motion caused each reset. This is
not evidence that every reset was spurious or that the gameplay failure is fixed.

This candidate replaces session-wide GetLastInputInfo with a read-only observer
on the existing game window's input queue. It consumes raw relative mouse events,
keyboard/button/wheel messages, and keeps the held-key and actor-motion guards.
It does not register devices, replace the game's raw-input target, capture input,
block messages or add game offsets. Observer failure refuses camera takeover.
CINEMATIC_INPUT separately counts event/held/motion resets for gameplay review.
The actual private mailbox/runtime/director path is now part of offline checks.

A deterministic workspace regression reproduced a cancelled request retaining
ownership of a singleton screen. The cancellation now consumes that ownership;
a later explicit shortcut open cannot be closed by the old request. This also
needs the reported main-menu sequence verified in Minecraft.

Java-chat-only interaction/expansion/card-scale controls are hidden in both
settings layouts and search; persisted settings/share keys are retained. Explicit
mini-player actions and supported island/capsule widths remain available.

## 0.14.2 cinematic motion and collision correction

The user confirmed 0.14.1 starts and does not exhibit another visible fault,
but its movement feels stiff and disorderly, especially around blocks. Loaded
DLL/JAR hashes matched the 0.14.1 candidate. Native logs contain applied camera
commands without hook faults; those counters do not measure subjective smoothness.

Three reproducible adapter boundaries are corrected:

- Keep the fractional 50ms simulation remainder rather than resetting the clock
  to each late render frame. Idle and motion clocks no longer depend on a host
  cadence such as 125Hz. At most 250ms can be caught up; input/context changes,
  focus loss, clock rollback and longer suspension do not replay old movement.
- Restore the saved tracking-softness response for desired position/angular
  velocity. Upstream 2.6.0-dev CineSession ignored that setting and chased targets
  at fixed velocity/acceleration limits. Original speed/acceleration limits,
  immutable frame sampling, shot scheduler and independent FOV envelope remain.
- Keep tangential travel at a block contact instead of stopping the complete
  diagonal step. This is an owned Bedrock collision adapter over the existing
  bounded snapshot, with at most three contacts. Every segment and its final
  interpolation chord must be known and clear. Unknown geometry refuses motion;
  corners and partial slab collision remain constrained. This is a point-camera
  solver retaining the existing margin, not a full camera-volume solver.

The wall fixture demonstrates the original failure: after 20 diagonal steps
its tangential progress stalled at 0.24 blocks rather than the requested 1.6.
Tests also exercise a corner, a partial slab, unknown snapshot bounds, unchanged
speed limits, functional tracking softness, and actual host mailbox activation
at 20/60/125/144/165/240/360Hz and an irregular 13ms cadence.

CineSession is adapted at build time with before/after checksums; vendor sources
are untouched by these corrections. The four-version native camera/Arke layouts
and guards remain unchanged; DLL metadata/descriptor pairing is updated for the
new version. This does not claim an FPS improvement or game-verified smoothness.
Only the user can accept the new camera feel in their scene.

## 0.14.3 bounded shot preflight and command-range correction

The installed DLL/JAR hashes matched the 0.14.2 candidate. In the reviewed local
log, commands stopped applying while input, eligibility and the director remained
active (uptime 19832796); no native patch fault was logged. This does not identify
every visual incident, but reproductions establish two command-range defects:
the widest upstream landscape exceeds the existing 16-block native sphere, and
wall sliding can move an otherwise valid destination beyond that sphere.

The Bedrock adapter now bounds both the requested endpoint and final collision
travel to 15.25 blocks from the player foot. Final travel is truncated along the
already-clear chord, never radially projected through a corner. The original
16-block Java/native command checks are retained unchanged.

Each shot previews its desired positions over the planned shot duration, with
at most 257 samples. This is a mathematical preview without advancing the live
director, playback, input, transition, or random shot deck. Up to 24 deterministic
rotation/distance alternatives are checked; a route is accepted only when every
segment and its approach from the current camera are known clear. A conservative
0.15-block cube expands the existing native block AABBs, including partial slabs.
The existing player-eye-to-camera line of sight is also checked throughout the
preview and during playback, so a clear travel path alone cannot send a player
shot behind a wall. This retains the old anchor check, not entity visibility or
an unlimited terrain query to a distant landscape focus point.
The route is a piecewise linear target consumed by the existing bounded spring;
it is not a new teleport, spline that can overshoot walls, or unrestricted search.

No valid route retains the current safe pose. Each live controller step still
checks clearance, bounds and ordinary input/focus/world guards. A newly blocked
route can be replanned at most once per half second; current-step
failure holds position and discards that blocked velocity. Snapshot absence,
unknown cells and unavailable paths never establish free space. Geometry may be
up to the existing snapshot lease old; entities are not part of this planner.

CINEMATIC_ROUTE records bounded candidate/sample counts and elapsed microseconds
for at most 64 events per session object. The preflight does not scan Minecraft,
open unknown chunks, change a block, change the EXE or loosen native guards.
FOV, shot timing, immutable frames and the original velocity/acceleration bounds
are retained. A hold may look static in a cramped room; game acceptance remains
pending and the preview is not a guarantee against geometry changing afterward.

## 0.14.4 internet room proxy

Bedrock-owned addition: explicit Direct, Windows user manual/PAC/WPAD, HTTP
CONNECT or SOCKS5 routing for joining existing encrypted tunnel invitations.
Remote DNS, normal TLS hostname validation, bounded proxy handshakes and
owned cancellation are retained. Selecting a proxy does not silently fall back
after failure. Opening the settings page does not connect or save; persistence
runs in a background worker and applies on the next join. Existing address/LAN
connections, host-side cloudflared and media requests keep their original paths.
No upstream source snapshot, native game hook/layout, ModMenuBE, Windows proxy
setting or accelerator rule is changed. See [ROOM-PROXY.md](ROOM-PROXY.md).

## 0.14.7 all-source progressive audio

Clear HTTP sources share byte-detected MP3, FLAC, AAC/ADTS, MP4/M4A (sample tables
and DASH fragments), Vorbis and WAV PCM/float decoding. Bilibili retains CDN
headers; YouTube retains its windowed proxy-aware transport. Qishui uses the
original authorized whole-sample AES-CTR operation incrementally. Audio callbacks
read PCM only. Owned held-tail fixtures verify non-silent playback readiness,
tail-moov, a single large mdat, cancellation and codec-specific seek dispatch.
The previously repackaged AAC decoder produced silent spans on the owned sine;
use the existing JAAD 0.9.4 radio dependency for the new audio frames instead.
No new runtime dependency or ffmpeg executable is added.

Apple native playback, local files and existing radio keep their original paths.
Unsupported encryption or inaccessible tail indexes retain the complete-file
fallback. See [STREAMING-AUDIO.md](STREAMING-AUDIO.md). Manual platform/gameplay
acceptance is pending; earlier draw stalls and the driver crash remain separate.

## 0.14.6 room member latency and short-stall capture

- The member row reserves a rightmost lane for live member-to-host transport RTT, updated about every two seconds. This includes tunnel/proxy transport and is separate from Minecraft server ping. Local hosting uses its loopback connection.
- A versioned additive capability in the authenticated hello enables room-scoped latency tables. Unknown, disconnected, old-host and expired data is shown as `— ms`. Samples are bounded, matched to an outstanding monotonic echo token and owned by the current connection; no cross-room roster is broadcast.
- Ping/Pong handling stays on the socket reader, independent of player UI work. Row callbacks only read immutable cached snapshots.
- Incident review: the installed 0.14.5 host JAR hash matched its health log. Bilibili startup coincided with a 6.073-second draw and a later 1.844-second draw; the host continued afterward. No stack was captured by the old eight-second watchdog. This does not establish a specific blocking call or a fix.
- The production watchdog now captures blocking stacks at two seconds, at most six reports, without interrupting playback. The Bilibili root cause remains pending new stack evidence; no speculative audio/graphics rewrite was made.

## 0.14.5 complete room invitations

The host's complete-invitation action now copies the ready encrypted public
invitation when one exists, without a manually entered direct address. Otherwise
it requires a host address instead of silently copying a port/password fragment.
The separate fragment action is labelled as such, and the joining UI reports a
missing host address before starting any connection. Hosting layout reserves
space between proxy information and the copy actions. Original upstream sources
remain unchanged; the narrow adapter and regression tests record this difference.
This does not create a new relay service or guarantee cross-border connectivity.
