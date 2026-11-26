# Upstream dev 2.6.0 synchronization plan

Goal: Update Bedrock from reviewed 2.5.2 to pinned upstream dev cda0c7e9db4069844699d14c9fcfb43729bd4b2f and deliver a local 0.14.0 candidate.

Approved direction: User requested latest upstream synchronization; existing four vanilla game profiles, separate LeviLamina 26.60 package, optional external ModMenuBE, old UI selection, no default hotkeys, no persistent media cache by default. No automatic game installation or GitHub publication.

- [x] Verify clean 0.13.0 baseline and upstream complete commit delta.
- [x] Refresh curated upstream inputs and content hashes, preserving licensed Noto substitutions and recording upstream font changes separately.
- [x] Adapt changed Java playback, UI, acoustics and camera interfaces through existing owned platform overlays. Retain streaming recovery, queue rules, cache controls and exact native guards.
- [x] Add meaningful regression coverage for changed cache/queue/mixing, UI lifecycle and snapshot invalidation; run upstream behavioral suites where applicable.
- [x] Validate complete JVM build/tests; render smoke separately and record unavailable graphics accurately.
- [x] Build numeric-version 0.14.0 native/LeviLamina packages and appropriate native checks; retain 0.13.0 DLLs unchanged.
- [x] Package vanilla four-version and LeviLamina full/update distributions separately, no ModMenuBE DLL or user-config overwrite in updates. Verify hashes/resources/licenses and write concise manual tests.

Evidence: Upstream main remains 9b4cc7e7 (2.5.2); latest development branch advertises 2.6.0, so label this an upstream development synchronization, not an official stable 2.6.0 release. GPU depth/entity occlusion remains pending as previously agreed. Worktree isolates all new source edits from the public main checkout.
