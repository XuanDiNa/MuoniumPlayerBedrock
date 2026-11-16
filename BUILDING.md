Local4 update: see modmenube-migration.md for separate Workspace/Configure and local3 fallback.

# Building on Windows x64

Requirements: VS2022 C++/Windows SDK, xmake, Python3 (`py`), JDK21 and Gradle8.10.2 on PATH. Use an x64 VS developer shell. Dependency downloads need network access.

```powershell
xmake f -y -m release --menu_fixture=n
xmake build -y MuoniumPlayerBedrock
gradle -p jvm --no-daemon installDist
```

DLL: `build/windows/x64/release/`. Companion: `jvm/build/install/MuoniumBedrockHost/`; install its `lib/` under plugin `jvm/lib/`. Complete deployment needs loader metadata and a compatible Java21 runtime, supplied in full releases.

## LeviLamina

```powershell
New-Item -ItemType Directory -Force artifacts/compat-26.60/ll-import | Out-Null
lib /nologo /def:vendor/levilamina-memory/LeviLamina.def /machine:x64 /out:artifacts/compat-26.60/ll-import/LeviLamina.lib
xmake build -y MuoniumPlayerBedrockLL
```

The LL package renames its DLL to `MuoniumPlayerBedrock.dll` and uses `type: native`. The preload variant uses `type: preload-native`. They have different entrypoints/allocator contracts. Adapted official headers/operators and import declarations are in `vendor/levilamina-memory/`. LeviLamina itself is not redistributed.

## Source and fonts

The curated original snapshot is pinned in `vendor/MuoniumPlayer-2.5.2/SOURCE-MANIFEST.json`. `tools/prepare_jvm_sources.py` generates Bedrock overlays; unchanged applicable sources compile directly. Public font substitutions are recorded explicitly in the manifest. Four legacy aliases contain unmodified Noto fonts. Ordinary Gradle builds include the fonts/OFL notices without a private init script.

This repository holds the unified four-version native-family source. The separate LL baseline has a corresponding source ZIP in the Release. Use matching source to rebuild it. A rebuild has a new binary hash and does not inherit manual acceptance.

## Verification

```powershell
gradle -p jvm --no-daemon test worldSpeakerTest playbackRecoveryTest stageRenderTest alphaBoundsRenderTest uiPresentationRenderTest
py tools/verify_public_assets.py .
```

Before the render tests, build `D3D12DisplayFixture`; `check` also needs `D3D12NativeTests`. These test hardware/WARP composition in an owned Windows graphics session. Singleton audio fixtures use a separate JVM to avoid other playback-worker state. Optional API fixture tests use the reviewed sibling ModMenuBE registry (`--menu_fixture=y`). Passive settings tests use the supplied frozen local3 consumer in `vendor/modmenube-local3-passive/`, with source checksums and MIT license; the sibling menu is not a production build dependency. The production adapter uses the versioned API and its own descriptor routing. MenuBEInterop, Local3RouteTests and MenuSettingsInterop replace historical Workspace pairing fixtures; no legacy exports are required.

Exact-image checks need legally obtained game images. No game executables, IDA databases or private extracted datasets are supplied. Historical investigation scripts/results are not required to build. Tests do not start Minecraft or install plugins.

Optional room helper: `py tools/prepare_tunnel.py` fetches a pinned cloudflared binary. Keep licenses/source/build access described in `docs/DEPENDENCIES.md` when redistributing.

繁體中文：使用 VS2022 x64、xmake、Python、JDK21、Gradle8.10.2。原生及 LL 分開建置。公開字型已在來源內；舊 DLL 用對應來源 ZIP。離線驗證不等同遊戲人工驗收。


Local4 workspace verification builds `MenuBEWorkspaceTests` and
`WorkspaceTransportTests` using the frozen local4 SDK/service/identity sources.
The fixture provider is a distinct sibling DLL with a real manifest and opt-in
`modmenu.json`. Test both module load orders, invalid table ownership/version,
not-ready state, request creation/failure/explicit return and deadline refusal.
`HostWorkspaceTest` checks the actual Win32 mapping and real owned mini/tools UI
without a GPU. Hardware rendering tests remain a separate requirement; do not
count WGL-unavailable failures as passing or reuse earlier render XMLs.
