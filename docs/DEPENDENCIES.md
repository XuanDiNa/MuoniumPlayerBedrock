# Dependencies and corresponding source

The active player is based on MuoniumPlayer 2.5.2 at commit
`9b4cc7e7bd143315da716075a6d024a0d4c0c81b` (Apache-2.0).
Curated source, resource manifests, Bedrock adapters and build tools are supplied
in this repository and matching Release source ZIPs. Versions are declared in
`jvm/build.gradle`. Dependency JARs are unmodified, with embedded notices retained
and collected under `licenses/dependencies/` with provenance.

| Component | Terms and source |
| --- | --- |
| MuoniumPlayer | Apache-2.0; https://github.com/zihe114514/MuoniumPlayer-multiversion |
| Noto Sans / Noto Sans CJK | SIL OFL 1.1; pinned unmodified fonts, notices and hashes in vendor resources and host JAR `META-INF/licenses/public-fonts/` |
| Original icon fonts / artwork | Retained from MuoniumPlayer with source and attribution. Platform names/logos identify supported services; trademarks belong to their owners. |
| MinHook 1.3.4 | BSD-2-Clause; https://github.com/TsudaKageyu/minhook/tree/v1.3.4 |
| nlohmann/json 3.11.3 | MIT; https://github.com/nlohmann/json/tree/v3.11.3 |
| ModMenu Workspace / keyboard SDK | MIT; supplied headers/notices in `include/sdk/` and `licenses/` |
| Skija / HumbleUI Types | Apache-2.0; https://github.com/HumbleUI/Skija and https://github.com/HumbleUI/Types |
| Skia | BSD-3-Clause; https://skia.googlesource.com/skia/ |
| LWJGL 3.3.6 | BSD-3-Clause; https://github.com/LWJGL/lwjgl3/tree/3.3.6 |
| GLFW / stb | zlib / MIT or public-domain alternative; https://github.com/glfw/glfw and https://github.com/nothings/stb |
| OpenAL Soft 1.24.1 | LGPL-2.0-or-later; matching source below |
| Processing Sound repackaged source | LGPL-2.1; source under vendor `repackage/processing/`, Bedrock modifications in overlay tools; https://github.com/processing/processing-sound |
| JSyn repackaged source | Apache-2.0 headers retained; https://github.com/philburk/jsyn |
| JAAD 0.9.4 | Apache-2.0; https://github.com/Tianscar/jaadec |
| Rhino relocated 1.7.15 | MPL-2.0; https://github.com/mozilla/rhino/tree/Rhino1_7_15_Release |
| zstd-jni 1.5.6-9 | BSD-2-Clause plus embedded component terms; https://github.com/luben/zstd-jni/tree/v1.5.6-9 |
| JNA 5.17.0 | Apache-2.0 / LGPL-2.1-or-later dual; https://github.com/java-native-access/jna/tree/5.17.0 |
| JOML 1.10.8 | MIT; https://github.com/JOML-CI/JOML/tree/1.10.8 |
| Gson / Guava / Commons / HTTP Components / Log4j / ZXing | Apache-2.0; versions/notices retained |
| SLF4J / Checker Qualifiers | MIT; embedded notices retained |
| Java-WebSocket 1.6.0 | MIT; https://github.com/TooTallNate/Java-WebSocket/tree/v1.6.0 |
| cloudflared 2026.10.7 | Apache-2.0; https://github.com/cloudflare/cloudflared/releases/tag/2026.10.7; pinned optional helper |
| Oracle JDK21.0.5 minimal runtime | Oracle No-Fee Terms and Conditions and included third-party terms; preserve `runtime/legal/` and `runtime/release` |
| LeviLamina 26.60.7 allocator interfaces/operators | LGPL-3.0; adapted headers/operators/build imports supplied; https://github.com/LiteLDev/LeviLamina/tree/v26.60.7 |
| Microsoft GSL 4.2.0 | MIT; headers/notices supplied; https://github.com/microsoft/GSL/tree/v4.2.0 |

## OpenAL source and replacement

The Release includes `openal-soft-90191edd20bb877c5cbddfdac7ec0fe49ad93727-source.zip`,
the unmodified commit recorded in bundled LWJGL's `OpenAL.dll.git` (OpenAL Soft
1.24.1). Its CMake files and README provide build options. With a Windows C++
toolchain, for example:

```powershell
cmake -S openal-soft -B openal-build -A x64 -DLIBTYPE=SHARED -DALSOFT_UTILS=OFF -DALSOFT_EXAMPLES=OFF
cmake --build openal-build --config Release
```

Replace the compatible DLL in the native JAR entry
`windows/x64/org/lwjgl/openal/OpenAL.dll`, updating its digest sidecars, or
configure LWJGL to load a compatible replacement. Source:
https://github.com/LWJGL-CI/openal-soft/tree/90191edd20bb877c5cbddfdac7ec0fe49ad93727

## Relinking and notices

LGPL/MPL components retain their own terms, not Apache. Supplied Java sources
and build tools allow modification/relinking; dependency JARs are replaceable.
No restriction is intended on modifications or reverse engineering required
to debug those modifications. LeviLamina itself is a separate loader and is
not bundled. A compatible licensed Java21 runtime can replace the bundled one.
Preserve component notices and corresponding-source access when redistributing.

Optional downloads/services have their own terms. Music/platform trademarks
belong to their rights holders. Public Noto files use historical resource aliases;
they contain no SF Pro/PingFang font bytes.

繁體中文：四個文字字型換為 OFL Noto，圖示保留原作來源。LGPL／MPL 元件保留
原條款並提供對應來源、建置與替換方式；Java runtime 保留發行商 legal。
