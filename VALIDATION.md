# Verification boundaries

Users reported that the four game versions and the separate 26.60 LeviLamina variant run with their pre-publication baselines. This is basic runtime feedback, not acceptance of every feature/scenario.

Public packages preserve those native DLLs and production Java classes, replace four text fonts with OFL Noto assets and add license/provenance records. Font parsing, multilingual glyph coverage, real Skija rendering, UI input, alpha coverage and native D3D12 fixtures are checked offline. These checks do not launch Minecraft.

The vanilla public package uses one current 0.12.0-test2 DLL for all four versions. Its exact-profile implementations already existed; no entry guards or addresses were changed for grouping. This DLL has basic runtime feedback on 26.45/26.51; its 26.52/26.60 pairing awaits manual acceptance after offline exact-profile verification. The separate LeviLamina package preserves its accepted DLL. Historical accepted artifacts remain local.

Known limits include incomplete entity lyric occlusion and third-party tunnel/service stability. No FPS gain or perfect cross-machine support is claimed. Public package identities and test counts are recorded in docs/PUBLIC-VERIFICATION.json and per-package PUBLIC-EDITION.json.
