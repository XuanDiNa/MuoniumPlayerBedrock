# 26.60 Workspace startup correction

The local ModMenu 0.6.0-test11 log reached Ready, while Muonium 0.12.0-test2 reported `HOST CHECK identity=unrecognized` and stopped before creating its Workspace provider. The actual game file is the reviewed console-capture/launcher image `68B38A0B3FD2A57C4538DBFAC5BEACE14AFD774513E2CBF1BE4443E9DE010768` (323,821,384 bytes). This is a game identity admission failure, not a Workspace ABI change or a ModMenu DLL pin failure.

Read-only review established equality of eight original section headers/contents, entrypoint, 61 native entry guards and 48 complete native function bodies. The six GUI/console forms reconstructed in private memory match the published ModMenu test11 provenance. Workspace.cpp, WorkspaceApi.cpp and Workspace.h match the frozen real API fixture byte for byte.

The replacement native DLL adds four complete SHA-256/size identities: the console original, the earlier launcher-console form, and GUI/console forms from the alternate launcher rebuild order. No runtime normalization, RVA changes, new hooks or reduced guard checks are introduced. Vanilla and LeviLamina loader admission remain separate. Tests reject unreviewed subsystem/checksum, section contents, final-byte, file-size and loaded-header changes.

The regression first failed with `missing exact 26.60 console/rebuild-order identity`. After the allowlist change, the real original/launcher inputs pass 221 checks. Debug/Release verification covers existing versions, loader policy, version-isolated native contracts, settings parsing and actual provider register/publish/command/ack/disable/recovery flows using the real Workspace API. These are offline fixtures, not gameplay acceptance.

Republished distribution `0.12.0-public2` includes native version `0.12.0-test3-mc2660-console`. Native DLL and manifest versions must match. The public-font Java JAR remains byte-identical to the previous public2 package, so its diagnostic version still says test2. The separate LeviLamina DLL/JAR remain their previous baseline. New native DLL gameplay acceptance is pending. Previous public2 assets and its original tag object remain preserved locally; its GitHub Release and tag are replaced after verified replacement assets are ready. The public release number remains public2 at the user's request.

繁體中文：ModMenu 已啟動，但播放器拒絕控制台／啟動器順序產生的精確遊戲映像，因此未建立工作頁。本次只補齊已核實的四個完整映像身分；保留四版本實作、位址、入口保護與載入器隔離。新 DLL 和 manifest 必須一起更新。Java 引擎與 LeviLamina DLL 沿用；離線通過仍需進遊戲驗收。
