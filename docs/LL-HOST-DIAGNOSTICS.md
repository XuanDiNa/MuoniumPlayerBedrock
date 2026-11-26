# 0.14.7 LeviLamina 載入檢查診斷 DLL

適用已安裝 0.14.7 的 Minecraft 1.26.60.05／LeviLamina 26.60.7。
這是獨立保存的 hostcheck1 診斷候選，沒有覆蓋原 0.14.7 發行套件。

玩家提供的 2026-11-21 23:43:30、PID 42088 日誌顯示 DLL 已進入正式
ll_mod_load，但 VerifyHost 回傳 false；Java、音樂及網路工作尚未開始。
舊日誌沒有檔案身分或錯誤碼，不能判定是讀檔失敗或未收錄的啟動檔。
已確認的 LeviLamina 版本號本身不能代替遊戲執行檔的身分證據。

## 使用

1. 完全退出 Minecraft。
2. 解壓替換包至實例根目錄，只替換
   `mods/MuoniumPlayerBedrock/MuoniumPlayerBedrock.dll`。
   保留目前 0.14.7 的 JAR、manifest、設定、帳號及 runtime。
3. 啟動一次。若仍載入失敗，保留該次
   `mods/MuoniumPlayerBedrock/logs/MuoniumPlayerBedrock-*.log`。
   原生檢查發生在 Java 啟動前，因此不需要 JVM 日誌。

新日誌會出現 `HOST_DIAGNOSTIC revision=1`：

| reason | 意義 |
| --- | --- |
| matched | 完整檔案大小及 SHA-256 已對應現有核准映像 |
| open-failed | 無法開啟遊戲執行檔；win32 記錄實際 Windows 錯誤碼 |
| size-read-failed / size-invalid | 無法取得有效檔案大小 |
| size-unrecognized | 大小未收錄，仍拒絕；512 MiB 以內會另外記錄診斷用 SHA-256 |
| digest-unrecognized | 大小相符但完整 SHA-256 未收錄，仍拒絕 |
| read-failed / size-changed | 讀檔失敗或讀取期間長度不符 |
| hash-…-failed | SHA-256 提供者／計算失敗；crypto 記錄其狀態碼 |

同一行附上檔案大小、SHA-256、PE 架構、時間戳、映像大小與 subsystem；
不記錄執行檔路徑、帳號、Cookie 或音源網址。它只描述此次實際讀取的檔案，
不會上傳資料。未知或無法讀取的映像仍不啟動播放器。

本候選只補齊拒絕原因，沒有新增允許載入的遊戲映像，也沒有修改遊戲、
載入器、ModMenuBE、Arke、渲染或串流播放。不能宣稱已修復玩家的載入問題；
下一步須依這一行的實際錯誤碼／雜湊決定處理方式。

Source overlay: extract over the verified 0.14.7 source ZIP. The candidate retains
version 0.14.7 so it can use the unchanged matching player JAR. Exact image tables,
loader ownership and native hook contracts remain byte-for-byte unchanged.
