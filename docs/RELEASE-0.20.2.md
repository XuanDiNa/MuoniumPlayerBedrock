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

---

Windows x64 的 Minecraft Bedrock 客戶端音樂播放器，移植自 [MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion)。感謝原作者的支持與技術交流。本次整合上次 GitHub 發布之後的累計更新。

### 主要更新

- 同步上游 **2.6.1**：波點音源，以及QQ、Spotify、YouTube、Apple Music、網易雲的帳號、推薦與歌單功能；場景聲學、可選響度均衡及自然快混更新。
- 保留舊UI，加入新版設定中心、四種設定頁主題、主色及動畫選項，從HUD編輯調整；改善Esc返回與切換視窗後的輸入恢復。
- 運鏡加入整段路線預檢、方塊避讓與平滑處理，修正部分啟動及相機共存問題；保留D3D11／D3D12合成。
- 一起聽新增連接代理與延遲顯示；YouTube官方頁面播放使用背景瀏覽器，登入頁仍明確開啟。
- **純客戶端房間語音**：麥克風、拒聽、裝置選擇、快捷鍵及當前音樂廣播。房主在線、最多八人，不需遊戲伺服器插件。
- **距離音訊**：8／16／32／64格、方向感及方塊悶音；雙方需確認同一世界並明確啟用。普通與距離語音已獲玩家確認可用。
- **0.20.2麥克風增益**：0～400%、每50%一檔、預設100%，即時生效並保存；只調整麥克風，不影響音樂廣播，過大訊號採整幀峰值限制。遠距麥克風可先試200%。

語音入口：**一起聽 → 直連房間 → 房間語音**；HUD編輯也有語音與增益選項。收音／廣播預設關閉；麥克風及拒聽快捷鍵預設未綁定。

### 下載與安裝

| 適用環境 | 首次安裝 | 已有完整環境 |
| --- | --- | --- |
| 原版1.26.45.01／1.26.51.01／1.26.52.03／1.26.60.05 | [原版完整包](https://github.com/XuanDiNa/MuoniumPlayerBedrock/releases/download/v0.20.2/MuoniumPlayerBedrock-0.20.2-vanilla-mc26.45-26.51-26.52-26.60-full.zip) | [原版更新包](https://github.com/XuanDiNa/MuoniumPlayerBedrock/releases/download/v0.20.2/MuoniumPlayerBedrock-0.20.2-vanilla-mc26.45-26.51-26.52-26.60-update.zip) |
| 1.26.60.05／LeviLamina26.60.7 | [LL完整包](https://github.com/XuanDiNa/MuoniumPlayerBedrock/releases/download/v0.20.2/MuoniumPlayerBedrock-0.20.2-levilamina-mc26.60-full.zip) | [LL更新包](https://github.com/XuanDiNa/MuoniumPlayerBedrock/releases/download/v0.20.2/MuoniumPlayerBedrock-0.20.2-levilamina-mc26.60-update.zip) |

**[ModMenuBE](https://github.com/AFAlimru/ModMenuBE)與載入器需另外安裝**，不要混用原版／LL套件。工作頁依據`0.2.0-local4` API驗證，`local3`保留Configure備援。完全退出遊戲後解壓到實例根目錄，一起更新DLL、JAR、manifest與設定描述檔，保留原有使用者設定。完整包包含Java21、播放器依賴、Opus與授權來源；更新包需既有完整環境。

### 驗證與已知限制

- 本輪 **319項Java測試、34組原生Release驗證通過**，並核對附件、套件清單及公開字型／隱私掃描。
- 普通及距離語音已由玩家確認。新增增益的額外實體麥克風測試依維護者決定跳過；離線音訊驗證不代表所有硬體、跨網路或遊戲情境已驗收。
- **YouTube尚未完全修復**：仍可能起播失敗、途中停播或尾段不完整。官方頁面路徑需已安裝Chrome／Edge及專用登入；Spotify OAuth需自己的Client ID。音源受帳號、地區、平台授權及網路限制。
- 實體精確歌詞遮擋仍待完善，方塊與聲場有採樣限制；公網穿透及TCP弱網／跨國穩定性仍有限。
- 沒有回聲消除、降噪、自動同服尋房或Java Simple Voice Chat協定；距離模式不會自動驗證雙方同服同世界。

來源、授權及SHA-256清單隨附件提供。公開包使用Noto字型。保留舊版供回退，未自動安裝或修改遊戲EXE、ModMenuBE。

---

### English

A Windows x64 client music player for Minecraft Bedrock, ported from MuoniumPlayer with the original author's support. This release includes cumulative changes since the previous GitHub release:

- Upstream **2.6.1** source/account/playlist improvements, Bodian Music, scene acoustics, optional loudness normalization and natural quick mixing.
- Old/new UI choices, four settings themes, accent and animation controls in the HUD editor; input/focus recovery fixes.
- Cinematic route preflight, block avoidance and smoother motion; D3D11/D3D12 composition retained.
- Room connection proxy/latency controls and background browser playback for YouTube.
- **Client-only room voice and music broadcast**, device selection, microphone/deafen shortcuts, optional distance attenuation, stereo direction and block muffling. The host stays online; up to eight voice participants; no game server addon.
- **Independent microphone gain, 0–400%, default 100%**, applied live and saved. Music broadcast volume is unchanged. Open Together Listening → Direct Room → Room Voice, or the HUD editor.

Vanilla **1.26.45.01 / 1.26.51.01 / 1.26.52.03 / 1.26.60.05** shares one edition. **LeviLamina 26.60.7 on 1.26.60.05** uses its own edition. Install compatible **ModMenuBE and the loader separately**. Choose full ZIP for first installation, update ZIP for an existing full runtime. Exit Minecraft before replacing files; user settings are preserved.

Validation: **319 Java tests and 34 native Release suites passed**. Ordinary/distance voice were user-confirmed; extra physical microphone testing for the new gain control was explicitly skipped. Offline evidence is not universal hardware/network acceptance.

Known limitations: **YouTube playback is not fully fixed** and may fail at startup, stop early or lose the tail. Official-page playback requires installed Chrome/Edge and the dedicated login; Spotify OAuth requires your own Client ID. Entity lyric occlusion, bounded block/acoustic sampling and weak-network tunneling remain limited. There is no echo cancellation, noise suppression, automatic same-server discovery or Java Simple Voice Chat protocol. Distance mode requires explicit same-world consent.

Apache-2.0; third-party components retain their respective licenses, including LGPL/MPL terms and corresponding source. Public packages use Noto fonts. Old releases remain available for rollback.
