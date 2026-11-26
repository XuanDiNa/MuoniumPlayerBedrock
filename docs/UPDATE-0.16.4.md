# 0.16.4：YouTube 登入會話與取流路徑修正

玩家附件確認實際載入 0.16.3；這次串流在 1,048,576 bytes 後持續 HTTP 403，
HTTP Range 與 URL Range 均失敗，之後出現緩衝。新的截圖另顯示「沒有可用音頻源」。
玩家確認同一首歌曲在瀏覽器能完整播放；這不能單獨證明是哪一項 CDN 驗證造成失敗。

本輪修正兩個已在來源與自有 HTTP 測試中確認的問題：

- WEB_REMIX 版本原本由當天日期拼成，並非 YouTube Music 發布的版本。
  現在透過既有代理取得官網公布的 JSON context、版本及 visitor 資訊；
  只解析資料，不執行網頁腳本。驗證頁或同意頁不能產生虛構版本。
- 原本匯入的 Cookie 只用於帳號／歌單，播放及網址刷新仍一律走匿名 Android。
  現在優先嘗試已登入 WEB_REMIX 的直接 AAC 音源，保留格式、碼率及長度資訊，
  同格式網址刷新也使用登入路徑；沒有可用的直接 AAC 音源才嘗試匿名備援。

官網 context 僅在記憶體保留最多十五分鐘；帳號 Cookie 或 HTTP／代理客戶端改變會失效。
帳號接口沒有回應可能來自接口、出口、網路或會話問題，不再一律標成 Cookie 過期。
本版不會擅自匯入一般瀏覽器的登入資料，也不修改系統代理。

新增有限的 `YOUTUBE_SESSION` 路徑診斷，續讀日誌增加來源客戶端及 proof／URL range
是否存在的旗標；不記錄其值、完整音訊網址、Cookie 或認證 Token。

## 驗證與限制

凍結 0.16.3 在三個新增帳號案例均失敗；新實作通過官網 context、登入取流、
同格式刷新、會話切換、驗證頁拒絕、匿名備援與不誤標過期的離線檢查。
自有 HTTP／AAC 測試實際解碼到結尾，並核對 64、76 秒有非靜音 PCM。
這些是自有測試資料，不能當作真實 YouTube 或 Minecraft 已修復的證明。

仍不保證網頁接口一定回傳可直接解碼的 AAC；只提供加密簽名、其他串流格式或
持續 CDN 403 的情況仍可能失敗。此次沒有實作無頭瀏覽器播放，也沒有聲稱
解決所有平台驗證。實際驗收使用原本〈Bad Apple〉播放超過 1:04 至結尾，並跳轉後半段。

原生 DLL 只更新版本與設定描述檔的精確允許雜湊，沒有更動遊戲掛鉤、布局、
運鏡、字幕遮擋、D3D11／D3D12 或 Arke 防護。保留設定與既有 ModMenuBE。
BSChat 廣播仍是未整合草案，不包含可使用的語音功能。

English: Use the officially published WEB_REMIX context instead of a fabricated
date-based client version. Prefer the imported authenticated session for direct
AAC resolution and same-format refresh, retaining anonymous fallback. Account API
failure alone no longer asserts cookie expiry. Owned HTTP/AAC regressions do not
establish live YouTube recovery; ongoing CDN 403 and unsupported web formats remain
possible. Native hooks, rendering, camera, installed games and other plugins are unchanged.
