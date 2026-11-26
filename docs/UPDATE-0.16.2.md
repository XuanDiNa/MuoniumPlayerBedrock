# 0.16.2：YouTube 分段備援修正

修正 YouTube 播放在約 1 MiB 後卡在緩衝的另一條路徑，保留 0.16.1
的連續運鏡及 0.16.0 同步的原作 2.6.1 功能。

玩家回報〈Bad Apple〉在 1:04 停住。同次 0.16.0 播放器日誌確認已開始
fMP4 串流，之後在 offsetBytes=1048576 反覆出現標頭分段 HTTP 403、
網址分段 HTTP 416，並進入緩衝。外部節點最初拒絕標頭請求的原因尚未確定。

備援請求原本把絕對位移同時放進網址 `range` 與 HTTP `Range` 標頭。
這會對已由網址選取的片段再套一次範圍；已在本機 HTTP 節點重現持續
403／416 和 64 秒後無法解碼的情況。現在每次只使用其中一種範圍指定，
成功後沿用該方式。不是加長等待或先下載整首才播放。

仍嚴格核對 Content-Range 的起點、終點、總長、回應長度與物件 ETag。
網址片段若以 200 回傳，必須帶正確 Content-Range；後半段沒有範圍資訊的
200 回應仍拒絕，避免把歌曲開頭拼接到中間。保留重試上限、取消、原有
代理設定與不保留媒體快取的預設。新日誌 `action=range-selected` 只記錄
成功使用的傳輸方式與位移，不記錄簽名網址或 Cookie。

## 驗證與實測範圍

舊程式在新增的三項重現測試全部失敗。修正後需通過完整 YouTube 回歸，
包含逐位元完整下載、真實 AAC 解碼、64／76 秒非靜音 PCM、讀至實際曲尾，
以及錯誤位移、變更 ETag／總長、忽略 Range 和取消等拒絕測試。
測試使用自有合成音訊與本機 HTTP 節點，不是外部 YouTube 成功的證據。

完全退出 Minecraft 後安裝對應原版／LeviLamina 更新包，保留設定，沿用
目前 ModMenuBE。先用同一首〈Bad Apple〉不拖進度正常播放超過 1:04，
確認能一直播放到曲尾；再拖到 1:20 或更後面，確認緩衝後有聲音。
若仍失敗，保留同次模組內的 `jvm-private-*.log` 和原生日誌。

沒有更改原生掛鉤、遊戲映像防護、RVA、布局、D3D11／D3D12 或運鏡邏輯。
原版四版本共用套件；LeviLamina 26.60.7 使用獨立套件。

English: YouTube URL-range fallback now uses a single byte-range selector,
avoiding an additional absolute HTTP Range on an already selected URL slice.
Explicit response range/object validation remains mandatory. Existing camera,
rendering, source and privacy behavior is preserved. Offline recovery and
audible PCM tests do not establish acceptance on the player's live CDN route.
