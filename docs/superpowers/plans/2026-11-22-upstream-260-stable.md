# Upstream 2.6.0 stable synchronization

Goal: Deliver a new 0.15.0 Bedrock candidate based on official upstream tag 2.6.0,
commit eb541684e9d681d8145f5f9d681cf31980646dc7. The supplied Fabric JAR is
comparison evidence, not an executable replacement for the Bedrock Java host.

Architecture: keep the existing C++/Java boundary and build-time platform overlays.
Refresh verified raw upstream files, rebase only affected overlays, and preserve
native game profiles plus the ll-coexist2 camera admission implementation.

User authorization: requested upstream synchronization; existing authorization
covers implementation choices. No new approval gate or subagents. No installation,
game process control, upstream publication or changes to other plugins.

- [x] Record supplied JAR identity, source tag/tree, exact file delta and class comparison.
- [x] Add and run regressions for Bilibili page-specific duration before synchronization.
- [x] Refresh the curated snapshot from verified Git blob identities, retaining font overrides.
- [x] Rebase CDN fallback, client UI/download manager and camera/platform overlays; keep remote notices disabled.
- [x] Include upstream stable Hermite camera interpolation, dynamic EQ and acoustic updates.
- [x] Run JVM behavioural, audio, owned-window D3D11/D3D12 and cinematic regression suites.
- [x] Update 0.15.0 metadata/descriptor pairing, build both native DLLs and run exact-image/LL coexistence checks.
- [ ] Package unified four-version vanilla and separate 26.60 LeviLamina full/update ZIPs, with source and license records.
- [ ] Reopen archives, check paths/CRC/manifest hashes and verify retained 0.14.8 packages unchanged.

Keep shortcuts unbound by default, next-track prefetch and persistent media caching
off by default, and all original user settings outside distribution ZIPs. Retain
ModMenuBE installation. Entity-depth lyric occlusion remains deferred.

Validation boundaries: owned fixture results are distinct from actual Minecraft
acceptance. No claim of universal Bilibili playback or Lamium gameplay coexistence.
