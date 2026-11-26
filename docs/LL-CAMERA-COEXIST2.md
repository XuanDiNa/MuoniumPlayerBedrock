# 0.14.8 LeviLamina cinematic coexistence candidate 2

Applies to Minecraft 1.26.60.05, LeviLamina Client 26.60.7 and the previously
reviewed Lamium 0.7.0 DLL (8,140,800 bytes; SHA-256
`7E9B6859B43A826021409F4EB87D34157D527D730E7AF44D26297665FE9B24C8`).
This is a DLL-only local candidate for an existing complete 0.14.8 player.
It retains the version number; identify it with `VERIFICATION.json` and
`CINEMATIC_HOOK revision=ll-coexist2` in the new native log.

## What the new test established

- PID 36392 loaded the actual 0.14.8 DLL and failed cinematic admission at
  game RVA 0x053B3290. No cinematic scopes or commands ran in that session.
- After the user disabled Lamium and restarted, PID 32504 admitted the shared
  camera hooks and logged a running director and successful native application.
  The user confirmed the cinematic camera visibly worked in this configuration.
- The previous candidate admitted Lamium's reviewed render detour but still
  rejected the camera-setup dependency. Lamium's source and exact DLL show both
  its FreeCamera setup hook and waypoint CameraCopy hook use that camera path.
  No read-only live jump-chain snapshot was available for the ended PID 36392;
  the new owner addresses were established by static delay-import registration
  review of the exact Lamium binary. The provided candidate still requires an
  actual two-plugin gameplay test.

## Narrow change

The existing exact game/loader identities and every machine-code guard remain.
For the one 26.60 camera dependency, admission additionally accepts an E9 entry
with the reviewed six-byte Detours displacement shape: byte six must be INT3,
and the remaining 26 guarded bytes must match. Its bounded read-only E9/FF25
chain must reach one of the two reviewed targets inside the exact Lamium DLL:

- RVA 0x2D850: FreeCameraSetupHook, Normal priority, forwards to its resident
  original first.
- RVA 0x1ED710: waypoint CameraCopyHook, Highest priority, forwards to its
  resident original before copying the view/projection.

The resolver checks the loaded module's full file identity, executable ownership
and exact target prefix. Unknown owners, changed guard suffixes, bad padding,
loops, unreadable chains or unreviewed modules continue to refuse.
Muonium does not install a hook on this dependency, rewrite Lamium's detours,
use a foreign trampoline or change Lamium settings. Its five existing cinematic
hooks still join the official LeviLamina shared dispatcher. No game RVAs,
actor offsets, Java code, playback, graphics backend or other plugins changed.

## Install and test

1. Completely exit Minecraft.
2. Extract this ZIP into the existing 26.60 LeviLamina instance root, replacing
   only `mods/MuoniumPlayerBedrock/MuoniumPlayerBedrock.dll`.
3. Keep the current complete 0.14.8 Java runtime, JAR, settings and sidecars.
4. Enable the normal Lamium installation as well, then start the game. There is
   no need to activate Lamium FreeCamera at the same time as Muonium cinematic.
5. Play a song in a world, enable the normal cinematic settings, close UI and
   remain idle for the configured delay. Confirm cinematic activation, ordinary
   input exit and normal player/HUD behavior in both mods.

The native log should show `revision=ll-coexist2`, with `setupOwner=lamium-0.7.0`
when the reviewed dependency hook is present. An unknown hook still refuses with
its exact failing RVA. The DLL contains no menu/runtime installer.

For rollback, restore the original frozen 0.14.8 DLL from its existing package.
This candidate does not replace, remove or republish the original release.

## Offline checks

Owned fixtures reproduce the original refusal before the change, and verify
both setup owners together with an occupied render entry after the change.
They assert the complete dependency entry is unchanged and still calls the
foreign function after enable, disable and close; unknown owner, suffix and
padding alterations are refused. Both original shared-hook load-order cases,
partial rollback and four game-version fixtures are retained. Debug/Release
results and production DLL audit are recorded in `VERIFICATION.json`.
These results do not establish Minecraft gameplay acceptance with both mods.
