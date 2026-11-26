# 0.16.0：同步 MuoniumPlayer 2.6.1

來源固定為上游 main 的 release 提交
[`4011d03b535cc40fa54b2cb8324cd5405eb9d6e0`](https://github.com/zihe114514/MuoniumPlayer-multiversion/commit/4011d03b535cc40fa54b2cb8324cd5405eb9d6e0)。
本輪查詢時尚未取得可用的 2.6.1 GitHub tag／Release，並非按標籤下載。
相較 2.6.0，收錄來源中有 129 個檔案更新，共核驗 1830 個來源／資源檔案。

提供的 Fabric JAR 僅用於功能／類別核對：17,871,907 bytes，SHA-256
`176DF3239C7B3EAF8C1C9495264F3785B103C2DD1C8E14D118FBC9563A380C53`。
Bedrock 仍從來源編譯為 Java 21，不附送或載入 Fabric JAR。

## 本輪功能

- 新增波點音樂：搜尋、推薦、登入與帳號歌單入口，以及對應音源與歌曲資料。
- QQ 音樂：推薦、猜你喜歡、每日歌曲、私人 FM、電台分組、帳號歌單操作。
- 網易雲：已購專輯、音樂雲盤；Apple Music：推薦內容。
- YouTube Music：專用瀏覽器登入與登入狀態恢復。由使用者點擊登入才啟動專用
  Chromium 資料目錄；不讀取平常瀏覽器的個人資料，關閉播放器不取消登入工作。
- Spotify：OAuth、推薦、最近播放、喜歡列表、帳號歌單及寫回。需自行設定 Spotify
  Client ID，回呼為 `http://127.0.0.1:47478/callback`，仍受平台帳號與 API 規則限制。
  Spotify 音訊供應方式沿用原作，未加入付費或 DRM 繞過功能。
- 場景聲學：直接使用新 `AcousticRayField`，接入 Bedrock 已有的不可變世界快照。
  區分首反射與晚期圍合度、逃逸率、逐頻帶反射能量與早期反射高頻衰減。
  計算在背景執行緒進行，每輪 96 條射線；世界資料仍受 33×33×33 快照範圍限制。
  未知直達路段不視為空氣，未知格也不能通過反射回程可見性檢查。
- 可選響度均衡：保留上游完整檔案分析與峰值增益保護。串流使用相同響度計，
  在既有背景解碼時對已下載前綴做估算，每五秒更新一次；不為此預先下載整首歌。
  前綴估算會隨後續內容改變，並非播放前已知的整曲 LUFS。關閉時不進行這段分析。
- 自然快混、安全短重疊、切歌預填、流式快取與音頻執行緒收尾更新。
- 運鏡：採用上游新的追焦平滑與擴充軌跡，新設定預設等待十秒，玩家輸入仍即時退出。
  保留 Bedrock 整段路線預檢、碰撞與未知格限制；既有使用者設定不覆寫。
- 同步音樂膠囊恢復顯示，以及 Token、Cookie、音頻快取與歌單操作修正。

入口：播放器來源選單切換音源；各來源登入／發現頁使用對應帳號功能。
響度均衡在播放器音效工作台的「曲目响度」頁；運鏡在 HUD 編輯的運鏡設定。

## 保留與驗收邊界

原版四個精確版本共用一包：1.26.45.01、1.26.51.01、1.26.52.03、1.26.60.05。
LeviLamina 26.60.7（1.26.60.05）使用獨立套件。ModMenuBE 另行安裝，這次不修改它。
保留設定／帳號／歌單、新舊 UI、Noto 字型、D3D11／D3D12 合成、LL 相機共存、
Bilibili／QQ 取流適配與 0.15.4 YouTube 續讀修補。快捷鍵預設無綁定；
下一首預抓與持久音樂快取預設關閉。響度測量等可再生工作檔在可清理的工作階段內。
原作公告與贊助提示繼續停用。

原生程式只有版本和設定描述檔雜湊變更；不增加遊戲掛鉤／RVA，不變更任何 EXE。
Java 的 1.21.1 世界歌詞緩衝修正不是 Bedrock 原生渲染修正。Bedrock 尚未提供真實
生物追焦資料；玩家／生物精確歌詞遮擋、語音廣播及穿透穩定性仍按既有待完善狀態處理。

離線、合成世界、回環 HTTP、音訊 PCM 及自有視窗測試不代表各平台真實帳號和歌曲
已驗收，也不代表已證明遊戲 FPS 或主觀音效提升。新版 DLL／JAR 的遊戲內驗收待測。
不安裝、不啟動遊戲、不修改其他插件，不上傳 GitHub。

## English

Bedrock 0.16.0 synchronizes the upstream 2.6.1 release commit `4011d03b`:
Bodian, expanded source/account/playlist features, YouTube browser login, Spotify
OAuth, the new acoustic ray field, optional loudness normalization, natural quick
mix and cinematic smoothing/trajectories. The host remains Java 21. Streaming
loudness uses the upstream meter on decoded prefixes without another download.
The 0.15.4 YouTube continuation repair and existing Bedrock features are retained.
Spotify requires a user-configured client ID. Account entitlement, external service
availability and Minecraft gameplay acceptance remain outside offline verification.
