# 0.15.2：Bilibili／QQ 登入取流修補候選版

修正「帳號已登入，播放請求卻沒有完整使用登入資訊」的兩處缺口。

- **Bilibili**：普通播放的影片資訊與播放地址請求，改為帶上已保存的帳號及裝置 Cookie。此前只有部分帳號功能與高解析下載會帶 Cookie，普通播放可能仍是匿名請求。沒有登入時仍可播放公開免費音訊。搜尋與清單請求沿用既有處理。
- **QQ 音樂**：取流請求補上與其他已登入 RPC 相同的 `comm.qq`／`comm.authst`，更新為 `music.vkey.GetVkey.UrlGetVkey` 的簽名 POST 請求，並使用 `ctx=0`。保留成功 purl、音質選擇及 HTTP／頂層／RPC／單項錯誤判定。
- **失敗說明**：Bilibili 記錄失敗發生在影片資訊還是播放地址；只記錄是否存在帳號／裝置 Cookie，不輸出 Cookie 值。QQ 104003 顯示為取流權限校驗失敗，避免直接認定歌曲沒有版權。
- **版本日誌**：JVM 啟動行改為讀取實際程式版本，避免繼續顯示固定的舊版號。

沿用 0.15.1 的 HTTP 串流拒絕處理與 Spotify／酷狗提示。原生 DLL 只更新版本及精確設定描述雜湊；相機、映像防護、D3D11／D3D12 及其他模組均未修改。原版四版本共用套件，LeviLamina 26.60.7 獨立。不含 ModMenuBE、帳號或使用者設定；預抓下一曲與持久快取仍預設關閉。

## 驗收與限制

完全退出遊戲後替換對應套件的 DLL、描述檔及 jvm/lib，保留原帳號、設定與歌單。重新開啟後先用已登入帳號播放先前失敗的 Bilibili 影片與 QQ 歌曲即可，無需重測所有功能。

本版修補的是請求構造，**不保證平台解除 HTTP 412 風控或提供帳號未獲授權的歌曲／音質**。不會在帳號被拒後切換匿名身分重試，不向音訊 CDN 新增帳號 Cookie，不繞過平台授權。離線請求測試可驗證憑證傳遞；玩家帳號的實際成功播放仍待此版測試。

若失敗，保留同次原生與 `jvm-private-*.log`。新日誌 `BILIBILI_PLAYBACK_REQUEST` 的帳號／裝置欄位只有 true／false；`MUSIC_SOURCE_FAILURE` 包含固定階段及數字錯誤碼。無需提供帳號 Cookie。

分析與公開參考見 [登入取流分析](SOURCE-AUTH-20261122.md)。離線測試、DLL／JAR 雜湊及封裝核驗結果見套件 `VERIFICATION.json`；不代表 Minecraft 遊戲內驗收。

English: Ordinary Bilibili playback now carries the saved account/device cookies through the view and playurl API calls. QQ playback now uses the authenticated comm envelope and the signed `music.vkey.GetVkey.UrlGetVkey` POST route. A remaining HTTP 412 or 104003 is reported accurately; login does not guarantee service acceptance or song entitlement. Preserve accounts/settings/playlists, update the matching edition, then retry the previously failing songs. Gameplay acceptance remains pending.
