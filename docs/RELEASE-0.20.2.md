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
