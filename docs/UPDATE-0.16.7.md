# 0.16.7 — YouTube 瀏覽器背景播放

YouTube 專用播放瀏覽器從啟動起就在背景執行，載入與等待廣告期間也不建立可見視窗，
不在工作列或 Alt-Tab 顯示播放視窗。需要操作帳號的專用登入頁仍正常顯示。

舊版在歌曲準備好後才隱藏普通 Chrome 視窗，載入期間仍可能出現在工作列。
本版以 Windows 原生隱藏啟動參數建立正常 Chrome／Edge，從第一個視窗就保持隱藏，
由瀏覽器除錯接口連接這個專用播放分頁，不額外建立播放渲染程序；
為該分頁設定媒體所需的尺寸與活動狀態，不改變 Windows 焦點或操作全域鍵鼠。啟動參數直接交給程序接口，
不經命令殼層；原生程序與執行緒控制柄均會關閉。
播放仍使用一般 Chrome／Edge 模式。

保留 0.16.6 的官方頁面音訊解碼、原有 Java 音效與歌詞管線、登入與代理設定。
官方廣告、驗證及帳號限制仍適用；隱藏視窗不代表減少或消除瀏覽器 CPU／RAM 負載。
停止播放與結束程序仍關閉專用子程序並清理暫時播放資料；不修改原登入資料。
背景播放的片尾回呼可能稍晚，本版加入有上限的收尾容差，避免播完後誤等資料；
仍要求歌曲已到末尾，且對已解碼音訊有硬性界限，不能把中途廣告或缺失片段當作正常結束。

## 驗證與限制

隱藏視窗回歸檢查啟動後與載入期間的可見 Windows 視窗數量。
自有網頁測試驗證解碼、官方提供的跳過控制、跳轉、背壓、結尾與程序清理。
真實服務探針以同一首 Bad Apple（FtutLA63Cp8）驗證超過 64／76 秒仍有非靜音 PCM、
完整結尾，以及跳轉 120 秒後超過 195 秒至結尾；播放期間也檢查無可見視窗。
具體結果及交付 JAR 雜湊見隨包的 VERIFICATION.json。

獨立探針不代表 Minecraft 內或一起聽已驗收，遊戲流暢度與瀏覽器負載仍需實測。
需要已安裝 Chrome／Edge；隱藏啟動失敗時回報播放未就緒，不退回可見播放視窗。
專用登入仍須由使用者完成；本版不捆綁瀏覽器。

四個原版遊戲版本合包，LeviLamina 另包；完整包與更新包均保留設定。
原生僅更新版本及精確設定描述檔允許雜湊；未改遊戲 EXE、ModMenuBE、相機或渲染掛鉤。
保留 0.16.6 供回退，未自動安裝或公開發布。

English: Start the owned YouTube playback browser with no visible window and
use a hidden CDP target with an active media viewport. Interactive login stays
visible. Full-song PCM, late seek, EOF, owned-window visibility and cleanup
checks are distinct from pending Minecraft and performance acceptance.
Browser overhead and official ads/account verification still apply.

