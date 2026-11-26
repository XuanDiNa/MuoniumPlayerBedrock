# 0.15.3：YouTube 跨段播放修補候選版

附件中 0.15.2／LeviLamina 的同次播放記錄，顯示 YouTube 分片 MP4 起播成功，反覆緩衝後在 64,297 ms／219,999 ms 停止，原因為串流 IOException。舊日誌沒有保留底層 HTTP 狀態，因此不能把這次中斷直接認定為授權、連結過期或某個 CDN 錯誤。

本輪修補已用本機 HTTP 和自製音訊重現的續接缺口：

- 有效 CDN 連結在後續音訊段繼續使用，不再每段強制重新解析 YouTube metadata。只有 CDN 拒絕時才嘗試更新同格式連結。
- 中途斷線或暫時 5xx 最多重試兩次，從尚未交給解碼器的原位元組續讀。已收到的前段不重播、不跳過；取消播放會終止讀取。
- 驗證 Content-Range 起點、終點、總長與強 ETag。後段收到整首 200、不同長度或錯誤區段時停止，避免把歌曲開頭或其他物件接進目前 AAC 解碼器。
- 被限流時沿用有上限的縮段與等待；漸進模式不再連續重試實際大小相同的窗口。
- 音訊回應在接收過程就受既有 32 MiB 上限約束，保留整次交換的逾時與取消。
- 新增 `YOUTUBE_STREAM` 固定欄位記錄，區分 HTTP 拒絕、續接、錯段與停止；不記錄帳號、Cookie、影片 ID 或簽名網址。

首段上限仍為 256 KiB，後續上限 1 MiB；保留邊下邊播。預下載下一首與持久快取仍預設關閉。QQ 保持 0.15.2 的已發布行為，先前帳號通道草稿不包含在本候選版。

原生 DLL 只更新版本與精確設定描述雜湊，相機、遊戲映像防護、D3D11／D3D12 及其他插件不變。原版四版本共用套件，LeviLamina 26.60.7 獨立；維持已安裝的 ModMenuBE。

## 本輪測試

完全退出 Minecraft 後替換對應套件，保留帳號、設定與歌單。優先重播先前中斷的 YouTube 歌曲，播放超過原中斷位置並到曲尾；如有一起聽，也在原使用方式確認續播。若失敗，保留同次原生與 `jvm-private-*.log`。

本機測試涵蓋完整跨段、真實 AAC 解碼、斷線、CDN 5xx、連結更新、錯段、物件改變、200 回應及取消。本機通過不代表玩家網路／外部 CDN 或 Minecraft 遊戲內已驗收，也不保證平台接受每首歌曲。

English: Reuse a valid YouTube CDN URL between byte windows, refresh only when rejected, retry transient interruptions at the undelivered byte offset, validate range/object identity and bound incoming response allocation. Progressive playback and existing proxy settings are retained. Test the previously interrupted track through its end. External service and Minecraft acceptance remain pending; this is a local candidate, not a published release.
