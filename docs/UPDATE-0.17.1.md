# 0.17.1 — YouTube 背景播放與焦點隔離

修正隱藏的播放器 Chrome 仍能取得 Windows 前景焦點的問題。
背景播放現在放在模組建立的獨立桌面；不切換使用者桌面，也不使用
反覆搶回遊戲焦點的方法。專用登入視窗仍只在使用者點擊登入時開啟。
一般 Chrome 視窗與設定不受影響。

同時調整本機除錯連線的啟動流程。每次使用獨立臨時連接埠，連線前
驗證它只監聽迴環位址，且 Windows 記錄的監聽程序就是本次建立的瀏覽器。
不連接其他 Chrome，不修改一般瀏覽器設定，也不更改官方播放驗證。

沿用播放器自己的登入資料快照及代理設定。只改背景播放程序；
0.17.0 的 HUD 編輯主題、主色及動畫功能全部保留。
沒有修改遊戲掛鉤、相機、D3D11／D3D12、ModMenuBE 或其他插件。

完整播放、120 秒跳轉、實際 PCM、曲尾及焦點觀察的結果列在
隨包 VERIFICATION.json。外部服務的登入、廣告、網路、區域及內容權限
仍可能影響播放；另有一次起播後官方頁重新載入，導致音訊橋失效，
這個偶發起播問題仍待完善。另一次正常播放雖超過原中斷位置到曲尾，
但最後的 PCM 少於宣告長度，未通過既有曲尾覆蓋標準；沒有放寬該標準。
因此本版交付焦點修正，YouTube 完整播放問題仍未完全結案。
本機測試結果不代表所有歌曲或玩家環境都已驗收。
Minecraft 內的切歌、切出返回與輸入恢復仍需人工驗收。

退出遊戲後安裝對應更新包，保留設定。本版不會自動安裝或公開發布，
0.17.0 測試包保留供回退。

English: Isolate the owned YouTube playback browser on a private Windows desktop
so its invisible native window cannot take the user's foreground focus. Keep
the ordinary official browser playback path and validate the loopback debugger's
process ownership. Retain the HUD editor appearance update and existing settings.
Real-service PCM/EOF and focus observations are recorded separately from pending
Minecraft gameplay acceptance.
