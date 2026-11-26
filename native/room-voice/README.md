# Independent room voice codec

Apache-2.0 wrapper, statically linked Opus 1.5.2 via the xmake package build recipe (BSD-3-Clause).
No BSChat code, Minecraft imports, loader ABI, network, capture or rendering.
The host uses JNA; Windows x64, Java 21, 48000 Hz mono, 960 samples per packet.

From this directory, with Visual Studio 2022 C++ tools and xmake:

```powershell
xmake f -y -p windows -a x64 -m release --vs=2022
xmake build -j 4
```

Output: `build/windows/x64/release/MuoniumVoice.dll`. Ship at
`mods/MuoniumPlayerBedrock/jvm/voice/MuoniumVoice.dll` with the host.
The libopus archive pin is `v1.5.2`, upstream
https://github.com/xiph/opus/archive/refs/tags/v1.5.2.tar.gz .
Archive SHA-256: `9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404`.
The source package includes the exact unmodified archive under
`sources/opus-1.5.2.tar.gz`, plus the wrapper and build rules.
Full upstream copyright, BSD terms and patent notices: `../../licenses/Opus-COPYING.txt`.
The xmake dependency recipe enables runtime CPU detection; AVX is not required.

After building, from repository root run the existing Gradle executable:

```powershell
gradle -p jvm roomVoiceTest voiceBroadcastTest voiceBroadcastRenderTest
```

The codec fixture loads this real DLL and checks finite, audible Opus roundtrips.
Audio fixtures inject synthetic endpoints and never open the physical microphone.
See `../../docs/ROOM-VOICE.md` for the separate manual acceptance campaign.

The exact xmake-repo recipe at commit
`635956946b104aa1108d43267dd8878e13d3891a` and its Apache-2.0 notice are
retained in dependency/. The recipe adjusts CMake runtime options and carries
a clang-cl SSE4.1 build-flag patch; no codec behavior was edited by this port.
