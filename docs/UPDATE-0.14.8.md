# MuoniumPlayerBedrock 0.14.8 local test update

This is a new candidate, pending Minecraft gameplay acceptance. It does not
include ModMenuBE, Lamium, Arke, a game executable, account data or player config.

## Changes

- Settings: the HUD editor can save any integer 3D lyric scale from 40 to 300.
  The ModMenu description previously required steps of five, so a saved value
  such as 117 made the entire settings page fail with
  `Unsupported setting value: 3D lyric scale`. The descriptor now accepts every
  supported integer. Existing values are preserved; resetting config is not
  required. Both root and `interface/` descriptions are updated together.
- Cinematic camera on 1.26.60.05: correct the AABB and rotation pointer slots.
  The independently checked LeviLamina 26.60.7 SDK places StateVector at 0x240,
  AABB at 0x248 and ActorRotation at 0x250. Previously the AABB X coordinate could
  be read as pitch, causing Java to refuse the camera as unavailable. The
  corrected reader checks finite bounds/angles before publishing a whole sample.
  The other three game versions retain their existing offsets and entry guards.
- LeviLamina/Lamium camera hooks: use the official LeviLamina shared-hook API for
  the reviewed 26.60.7 loader and PreLoader build. An already occupied render
  entry is admitted only when its bounded, read-only jump chain reaches the
  exact reviewed Lamium 0.7.0 target and its remaining guarded bytes match.
  Unknown owners, changed suffixes, loops or unavailable APIs still refuse.
  No other plugin's trampoline is borrowed or rewritten. Ordinary vanilla
  installations retain the existing MinHook route. This is not a guarantee of
  compatibility with arbitrary Lamium/loader builds.
- Bilibili: retain up to two returned backup URLs for the same selected audio
  representation. When the initial HTTP request fails with a retryable status
  or transport error, try these addresses in order. Once an object is opened,
  range reads and continuation stay on that object and its validators. No
  cross-track/codec splice, account change, URL persistence or music prefetch
  is introduced. Logs identify the failing stage/status without printing URLs.
- Retain the prior exact 409C player-host admission and bounded hash-only host
  evidence diagnostics from 0.14.7 hostfix2.

## Install the correct update

1. Completely exit Minecraft.
2. Select the vanilla ZIP for 1.26.45.01 / 1.26.51.01 / 1.26.52.03 / 1.26.60.05,
   or the separate LeviLamina ZIP for 1.26.60.05 with LeviLamina Client 26.60.7.
3. Extract into the instance root and replace the supplied player files.
   Keep existing settings, accounts, playlists and complete Java 21 runtime.
4. Restart Minecraft. DLL, host JAR and both settings descriptions must be from
   this update. Installing only the DLL omits the Bilibili and page fixes.

These are update packages for an existing complete 0.14.7 installation. They
do not install a Java runtime or change another mod. The former package remains
available for rollback; rollback also requires the matching DLL/JAR/sidecars.

## Focused gameplay checks

1. Open ModMenuBE -> MuoniumPlayerBedrock -> Settings. Confirm all settings load
   with the existing 117% scale, then open the player/HUD editor and return with
   Esc. Do not reset settings to perform this check.
2. In a 26.60 world, play music, enable the existing cinematic settings, close
   UI and remain idle for the configured delay. Confirm the camera starts and
   input exits it normally. Test with the installed Lamium configuration you
   ordinarily use; no need to change or reinstall that mod for this update.
3. Retry a previously failing Bilibili song and a previously working song, alone
   and in an existing together-listening room if applicable. Check startup,
   continued playback and switching tracks. Some sources may still fail due to
   unavailable access/CDN/account responses; this update is not a promise that
   every Bilibili video is playable.

The supplied BV1d4411N7zD did not reproduce the failure in the standalone
anonymous network probe: both AAC-LC representations decoded approximately 317 seconds to
completion at the time of testing. That does not establish playback on another
network or prove the cause of all previous user failures.

## Verification boundaries

`VERIFICATION.json` records the fresh native/JVM checks and package identities.
Owned fixtures cover shared-hook ordering/rollback/forwarding, unknown owner
refusal, component layout, the real ModMenu parser with scale 117, and a local
HTTP 403 primary -> successful same-track backup with pinned range continuation.
These are offline results, not Minecraft gameplay acceptance. No game process
was launched, stopped, injected into or modified by this update campaign.

Reference: [LeviLamina v26.60.7 SDK](https://github.com/LiteLDev/LeviLamina/tree/v26.60.7).
