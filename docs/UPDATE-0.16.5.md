# 0.16.5 — 切換視窗後的播放器輸入恢復

玩家回報切換視窗／登入頁後，播放器整頁無法點擊，鍵盤也無法操作。
目前遊戲日誌與執行緒快照未捕捉到故障當刻，不能據此認定為網路等待或遊戲卡死。

獨立的 Windows 視窗測試已重現一條相同症狀的路徑：舊程式在恢復焦點時，
會等所有 GLFW 按鍵與滑鼠按鈕都放開才接受任何操作。
只要其中一個邏輯按下狀態未釋放，其他點擊、文字輸入與 Esc 也會全部被擋住。

新程式在恢復焦點時分別隔離原本按住的鍵與按鈕，其他控制立即恢復。
被隔離的控制須先釋放才能再次操作；事件分發、滑鼠拖動及鍵盤狀態輪詢均遵守此規則。
同一批事件中的釋放後重按也可恢復，不必再次切頁。
失焦時仍清除暫態與滾輪，不合成點擊、不關閉原頁，也不停止背景工作。

`OVERLAY_TRANSITION` 新增輸入啟用狀態及隔離數量，僅在狀態變化時記錄，沿用日誌上限。
不記錄按鍵、文字、登入資料或網頁地址。

## 驗證與限制

新增回歸先在舊實作失敗：一個保持按下的修飾鍵使另一個滑鼠點擊被丟棄。
修正後以自有隱藏 HWND 訊息驗證點擊、文字、Esc、按鍵重複、拖動輪詢、
釋放後重新操作及失焦滾輪隔離。沒有全域輸入注入，沒有操作 Minecraft。
其餘既有視窗／頁面生命週期測試與原生防護檢查隨套件提供，遊戲內恢復仍待驗收。

保留原版四版本合包與 LeviLamina 獨立包、使用者設定及既有 ModMenuBE。
原生部分僅更新版本與設定描述檔的精確允許雜湊。

**YouTube 未在本版修復。** 0.16.4 真實〈Bad Apple〉在 1:04 仍停止，
續讀於 offset 1,048,576 持續遇到 HTTP 403；確切外部原因仍未證明。
本輪未改其取流、代理或登入路徑。BSChat 亦仍為未整合草案。

English: Recover unrelated controls after an overlay focus handoff, quarantining
only carried held keys/buttons until release. Prevent inherited holds from
typing, starting a drag, or triggering shortcuts. Owned-window regression and
native checks are separate from pending Minecraft acceptance. YouTube's reported
1:04 interruption remains unresolved; this release does not change media fetching.
