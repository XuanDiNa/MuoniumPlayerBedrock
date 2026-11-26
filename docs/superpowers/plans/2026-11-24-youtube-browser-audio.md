# YouTube official browser audio recovery

Goal: replace the failing anonymous-CDN playback route with actual official-page
playback, feeding clear PCM into the existing Java audio/DSP/lyrics engine.
The user authorized continuing repairs and alternative routes; do not install,
publish, modify other plugins, or use subagents.

Evidence: 0.16.4 repeatedly receives HTTP 403 at byte 1048576. A throwaway Chrome
session using the user's imported YouTube session and the configured proxy endpoint
plays the same FtutLA63Cp8 video beyond 120 seconds with non-silent WebAudio output.
Its startup and exact full playback results are recorded separately under ignored
artifacts/yt-browser-20261124. This does not establish integrated-game acceptance.

Implementation:

- [x] Red owned-page Chromium test: local synthetic WAV, WebAudio PCM, sparse seek,
  pause/backpressure, EOF, cancellation and child-process cleanup. No YouTube
  fixture network and no global browser/profile access.
- [x] Own-process loopback CDP transport with bounded requests/messages, isolated
  session profile, imported app-owned cookies only, explicit YouTube proxy, close.
- [x] Official-page PCM source with bounded buffers and per-packet timeline.
  Preserve the official page's advertising/verification behavior; do not extract
  signed CDN URLs or fabricate proof/client identities. Report unsupported states.
- [x] Reuse ProgressiveAudio's PCM/DSP owner, including sparse requested seeks,
  completion/retention only for contiguous audio, and cancellation cleanup.
- [x] Route real YouTube playback to the browser when available; preserve direct
  code as an explicit fallback when no browser is installed. Do not silently turn
  browser verification/denial into a different authentication-bypass path.
- [ ] Fresh affected tests, actual song through EOF and late seek, source/class
  audit and native guard checks; create a new versioned candidate over 0.16.5.

New files: BrowserAudioSession.java (CDP/process), YouTubeBrowserAudio.java
(page/PCM policy), browser-audio.js (owned audio graph), browser audio tests.
Modify ProgressiveAudio.java (positioned source), StreamingAudioRoute.java,
tools/youtube_session_overlay.py (official watch marker before direct resolve),
jvm/build.gradle (test), then version/descriptor/docs/packager only.

Manual game acceptance remains separate. Official login, verification or account
restrictions remain respected. No persistent music cache unless the existing
user option is enabled; browser working profiles live inside the owned session.

Observed candidate regressions and fixes:
- Official quality selection temporarily resets duration; capture validated
  duration before requesting tiny video quality. Reproduced red then green.
- Site post-roll handler may precede our ended listener; completed song drains
  owned packets before considering post-roll. Covered by owned-page regression.
- A cached prefix with insufficient lookahead must request more positioned PCM,
  even if its first frame exists. Reproduced red then green.
- Concurrent browser closes wait for process retirement without acquiring the
  SessionMedia lock twice; cancellation and process cleanup tests pass.
- Ads wait for official playback and only click its offered skip control.

Final affected Java tasks: 169 tests, zero failures. Native Release: 34 suites.
Live service and candidate packaging remain tracked in the ignored evidence logs.

Live baseline finding: official headless page, with no WebAudio at all, also
clears the media source around 40-50 seconds and displays the official retry
error. Removing software-only rendering did not cure it. A regular Chromium
window passed the original failure position. Candidate uses a dedicated regular
Chromium window launched minimized/offscreen and hides only that PID's HWNDs;
never controls any user's browser window. The app remains the sole audible PCM
owner. No identity, signature, response or proof fabrication is introduced.

Final status: not released, not fixed. Real service remained unstable: media
source cleared with an official generic retry error, including a no-WebAudio
headless control. Candidate route is gated behind the explicit development
property muonium.youtube.experimentalBrowserAudio. Default runtime and version
remain 0.16.5. Restored accepted README/testing/installation/version metadata.
No 0.16.6 runtime archive was created or installed. Do not mark the final live
full-playback and late-seek/packaging item complete.

Full dedicated-session experiment (user authorized "try"):
- Clone only the app-owned idle YouTube browser profile. Never launch the
  original and never overwrite full-profile cookies with the flattened map.
  Snapshot, cancellation, destination identity and long-path tests were added.
- Official-page Python probe reached the content tail, and a separate 120s seek
  completed with non-silent 120s/195s output. Original profile metadata remained
  unchanged and throwaway copies were removed. First tail did not emit bridge
  EOF because delivered PCM lagged the content clock by 0.307s; a bounded four
  callback-block tolerance fixes this owned-page regression without accepting a
  missing audio second or a mid-song advertisement.
- Java navigation could read an old/partially initialized document. Reproduced
  with a delayed owned document; navigate now waits for top-document commit and
  a non-loading matching document before creating the audio bridge.
- The Java service run after these fixes still lost its media source at about
  50s and showed the official generic retry error. Full-session reuse is NOT a
  stable fix. Route remains default off; release version remains 0.16.5.
- Offscreen versus hidden-window control did not explain the early document
  replacement. Do not present a GPU, proxy, account or service quota cause as
  proven. Do not package private profile clones or relax the live release gate.


2026-11-24 fresh login and final candidate evidence supersedes the earlier
not-fixed status for the tested local session:
- User completed dedicated browser login. Read-only metadata confirmed a new
  session; account cookies and original profile were never packaged or changed.
- Direct route still rejected continuation at byte 1048576 after fresh login.
  Reauthentication alone did not repair that route.
- Added explicit boolean LOGGED_IN=false handling with an atomic session
  revision check; preserve cookies and avoid invalidating replacement login.
  Temporary account/network failure remains distinct from explicit rejection.
- Reproduced/fixed post-roll handling by comparing bounded callback lag with
  the last content clock. Mid-song ads and missing audio remain non-EOF.
- Reproduced/fixed official exposed skip control activation using owned-tab CDP
  input; no global pointer or game input. Never modify ad timers or verification.
- Final 0.16.6 host: actual non-silent Bad Apple PCM past 64/76 seconds to EOF;
  separate 120-second seek reached non-silent 120/195 seconds and EOF. Same JAR
  hash in both probes. Original profile unchanged and owned browsers retired.
- Official route now defaults on when Chromium is installed, remains off for
  offline tests, and retains an explicit diagnostic opt-out.
- Fresh Java: 180 tests, no failures/errors/skips. Native Release: 34 suites.
  Upstream 1830-file provenance and public Noto font/source audit passed.
- No game installation/launch/publication. Installed 0.16.5 and frozen packages
  preserved. Minecraft, together-listening and CPU/RAM acceptance remain pending.
Evidence: ignored artifacts/yt-session-recovery-20261124. Candidate packaging
must gate on the exact tested host hash and reopen/audit every archive.
