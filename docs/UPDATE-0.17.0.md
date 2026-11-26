# 0.17.0 — HUD 編輯外觀與 YouTube 起播修正

沿用現有「HUD 編輯」入口。切換至「新版設定中心」後，在「設定頁主題」
選擇 PolyGlass Dark／Light 或 Minecraft Dark／Light，並調整主色與動畫。
四張卡片立即預覽；主色沿用取色器及十六進位輸入。
主色拖動只更新記憶體，放開滑鼠或關閉頁面時保存。
舊版浮動面板與既有 HUD 設定保留。外觀切換不重新建立播放器或播放佇列。

這是 Bedrock 現有 Skija 設定頁的適配，不包含 Fabric 的 OneConfig 執行環境。
設定頁主題不改變播放器／歌詞的獨立配色。四種主題只套用新版設定中心；
切換新舊佈局仍按既有流程重新開啟編輯頁，主題、主色與動畫可在當頁更新。

YouTube 起播有兩個修正：官方 watch 頁有時附加 `themeRefresh` 等顯示參數，
先前必須網址字串完全相同才確認頁面準備完成，導致正確歌曲也被拒絕。
現在驗證來源、路徑與所有原始查詢參數的值及數量，允許服務端增加其他參數，
仍拒絕歌曲身分被改變、重複或遺失。

另外，帳號 API 的 Cookie 驗證失敗，不再預先阻擋獨立專用瀏覽器的播放會話。
瀏覽器依官方頁面自行判斷登入與可播放狀態；沒有清除或偽造帳號驗證狀態。
停用瀏覽器路徑時，既有直接 API 路徑仍保留其原有帳號檢查。
實測也發現複製的登入資料夾可能沒有載入加密 Cookie，讓背景頁實際未登入。
若該背景會話缺少登入 Cookie，現在補交播放器已保存的 YouTube Cookie，
交由官方頁判斷有效性；會話已有登入 Cookie 時不覆蓋，不更改原始登入資料夾。
沿用 0.16.8 的歌詞時間軸修正。

Chrome／Edge 仍需自行安裝；沿用播放器專用登入和 YouTube 代理設定。
官方驗證、廣告、區域及內容權限仍可能影響播放。
本輪實際《擱淺》仍有播放中斷與後半段跳轉失敗：不能把上述起播及會話交接
修正視為 YouTube 完整播放已修好。這項問題繼續保留待修；本輪先驗收設定頁。
實際服務的成功／失敗結果與各項離線測試見隨包 `VERIFICATION.json`，
與 Minecraft 內人工驗收分開記錄。

本版沒有修改遊戲掛鉤、相機、D3D11／D3D12、ModMenuBE 或其他插件。
未自動安裝或公開發布。保留 0.16.8 供回退。

English: Extend the existing Bedrock HUD editor with four settings-page themes,
an accent picker and an animation toggle while retaining the legacy layout.
Accept official YouTube navigation that adds display parameters without changing
the requested video identity. An API-cookie rejection no longer preempts the
independent official-browser session; official authentication checks remain in force.
Verification evidence distinguishes offline tests, real-service PCM and pending
Minecraft gameplay acceptance.
