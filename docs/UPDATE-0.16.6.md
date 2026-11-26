# 0.16.6 — YouTube 官方頁面音訊播放測試版

玩家回報所有 YouTube 曲目約在 1:04 停止，瀏覽器則可完整播放。
本機以重新登入後的專用會話重測，舊路徑仍在位移 1,048,576 收到 HTTP 403；
帳號登入成功並不足以修復這條 CDN 路徑。

本版讓已安裝的 Chrome／Edge 開啟獨立的官方播放頁，由官方播放器處理解碼，
將非加密 PCM 送入既有 Java 播放、音效、混音與歌詞引擎。
不提取或重播簽名媒體網址，不偽造平台驗證資料。
廣告仍由官方頁面播放，只有官方實際提供可點擊的「跳過」按鈕時才使用該控制。
不使用 Windows 全域滑鼠／鍵盤，也不操作玩家的瀏覽器分頁。

只複製模組自有、已關閉的 YouTube 登入瀏覽器資料至可清理的播放工作區。
不讀取日常 Chrome／Edge 設定，不開啟原登入資料目錄，不改動來源會話。
停止播放或結束程序時關閉專用子程序並清理其工作副本。
原有保留音樂快取／預取下一首開關保持原設定，預設仍關閉。

另修正官方明確表示未登入時的提示：保留憑據，提示使用「瀏覽器登入」重新登入。
暫時網路失敗或帳號接口沒有回覆不會直接判定 Cookie 過期；
舊請求也不能將剛重新匯入的會話標成失效。
片尾廣告與音訊回呼時間差的收尾修正有獨立回歸，不能把中途廣告當成歌曲結束。

## 驗證與限制

本機真實服務探針使用 Bad Apple（FtutLA63Cp8，約 3:39），
驗證正常播放越過 64／76 秒、完整結尾，以及跳轉至 120 秒後播放到結尾。
檢查的是實際非靜音 PCM，不只是進度條或解析成功。
獨立探針與離線測試不能代替 Minecraft 內播放、一起聽及效能驗收。

需要已安裝 Chrome 或 Edge，並先完成播放器的 YouTube「瀏覽器登入」，
登入完成後關閉專用瀏覽器。沿用現有 YouTube 代理設定。
沒有 Chromium 時保留舊取流路徑，該路徑的 1 MiB 拒絕仍可能發生。
官方頁面、廣告、驗證或網路情況會影響起播等待；不保證所有帳號與地區可播放。
專用瀏覽器有額外 CPU／RAM 開銷，遊戲內負載待測。
遠距跳轉需由官方頁面重新緩衝，不保證立即出聲。

保留 0.16.5 的失焦輸入修正、原作 2.6.1、D3D11／D3D12 與四版本原版合包，
LeviLamina 另包。原生修改僅版本與精確描述檔允許雜湊；未修改遊戲 EXE、
ModMenuBE、相機掛鉤或字幕遮擋。未自動安裝或公開發布。

English: Candidate official-page PCM transport for the reported YouTube 1:04
failure. Uses an isolated copy of the mod-owned login profile with installed
Chrome/Edge and existing YouTube proxy settings. Official ads and verification
remain; only an exposed official skip control is clicked inside the owned tab.
Real-service PCM/EOF/late-seek evidence is separate from pending Minecraft and
performance acceptance. No game, other plugin, user account or settings bundled.
