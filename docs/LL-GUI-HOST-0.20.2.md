# 0.20.2 緊急熱修復 — 2026-11-28

## 修正的問題

部分玩家在同為 Minecraft 1.26.60.05 / LeviLamina 26.60.7 的安裝上，
模組因 `digest-unrecognized` 拒絕載入，播放器及 HUD 編輯不能開啟。
原驗證將啟動器可能改動的 PE 標記也當成程式身分差異，造成誤拒。
本次兩份玩家報告的整檔指紋，均可由已驗證的檔案只改 Subsystem 3 → 2 精確重現。

## 修正方法與範圍

- 原有完整 SHA-256 比對優先；新增已驗證整檔內容的標頭差異辨識。
- 僅在私有雜湊緩衝區排除 CheckSum 四個位元組及 Subsystem 兩個位元組；
  Subsystem 只接受 GUI 2 / 控制台 3。其餘整份檔案仍須符合已驗證內容的 SHA-256。
- 同類標頭差異無需逐位玩家補雜湊。入口、程式碼、資料、匯入表、載入器、區段表
  或其他標頭差異均不由這條規則放行；原生掛鉤與載入器分流檢查保留。
- 不修改遊戲 EXE、ModMenuBE、Java 播放器、設定、帳號或快取。
- 本次公開替換 LeviLamina 完整包／更新包；原版四版本套件保持原檔案。
  版本仍為 0.20.2，以沿用相同的 JAR 及設定；日誌可記錄 `matched-header-variant`。

完整包包含 Java 21、全部播放器依賴及來源；更新包包含播放器檔案，需已有完整 runtime。
完全退出遊戲後解壓至實例根目錄，合併 `mods` 資料夾並保留設定、帳號、歌單及快取。
ModMenuBE 與 LeviLamina 仍需另行安裝，本熱修復不更換它們。

## 驗證

維護者回報本次完整修正版測試可用；沒有提供逐項場景或額外設備清單，
不代表所有設備與載入器形式均已驗收。發布 DLL 與測試包一致，未為發布重新建置：

`E851CBB9CE0CA2D0B1EA2ED4CDCF33B9A56EBA98D8E266E0E8CFA8A616BD7658`

九種已驗證內容家族、54 組未收錄標頭組合、1,024 項回歸檢查通過；
Debug 的標頭變化／玩家重現測試通過，34 組既有原生 Release 測試再次通過。
另驗證真實 DLL 拒絕無關程序、診斷資料隱私，以及修改程式碼或載入器後仍拒絕。
Java 及其他 runtime 二進位未修改；此次未重新宣稱或重跑 Java 功能驗收。

公開 Release 沿用 `v0.20.2`，此標籤保留原始 0.20.2 提交，不移動已發布標籤。
本次熱修復的對應提交及完整來源另列於 Release；GitHub 自動產生的標籤 source
仍是原始版本，請使用此次上傳的 `MuoniumPlayerBedrock-0.20.2-source.zip`。
原始發布檔案保留於維護者本機以便回退。

既有 YouTube 播放、實體歌詞遮擋及弱網穿透限制仍然存在。本次只處理載入誤拒。

## English

Emergency loading fix for Minecraft 1.26.60.05 / LeviLamina 26.60.7.
The previous exact-file check rejected supported installations whose launcher
changed only PE checksum or GUI/console metadata. Verified whole-file content
families now accept those six-byte metadata differences without per-player
allowlisting. Every other file byte, including game code, import metadata and
loader code, must match a reviewed fingerprint. Native guards remain enabled.
The game executable and loaded image are never rewritten.

The maintainer reported that the supplied full candidate works. The published
DLL is the exact tested binary; detailed scenarios and additional devices were
not specified. Nine content families, 54 unseen metadata combinations and 34
native Release suites passed. Java and all other runtime binaries are unchanged.
This does not resolve unrelated YouTube, entity-occlusion or network limitations.

Exit Minecraft, extract the matching LeviLamina full/update ZIP into the instance
root and preserve settings, accounts and playlists. Full includes Java 21;
update requires an existing full runtime. Install ModMenuBE and LeviLamina
separately. Vanilla packages remain unchanged. Version 0.20.2 is retained.

The published v0.20.2 tag is preserved at its original commit. Use the explicitly
uploaded source ZIP and hotfix commit linked in the Release for this DLL;
GitHub's automatically generated tag source remains the original baseline.
