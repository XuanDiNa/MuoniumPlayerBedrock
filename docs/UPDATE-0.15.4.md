# 0.15.4：YouTube 中途／後半段緩衝修補候選版

這次 0.15.3 日誌已確認 JAR 與交付檔案的 SHA-256 相同。首段成功後，多次出現 CDN 403，縮小分段後可繼續取得部分資料，約 1 MiB 處又進入等待。收集在仍有緩衝／恢復記錄時結束，沒有最終失敗或曲尾記錄。玩家另外確認正常播放和拖動後半段都會遇到問題。這些證據不能單獨判定 403 是配額、連結過期、代理或平台驗證造成。

本輪修補已在本機 HTTP 重現的程式缺口：

- 小段取得成功後，繼續沿用該大小；不再每段改回較大請求而重新觸發拒絕及 metadata 更新。
- 403 首先嘗試 YouTube 的網址 `range` 分段方式，再考慮更新同格式連結。若節點拒絕網址分段會退回標頭方式；只替換 range 參數，保留其他簽名參數的原始編碼及順序；不更換音源或帳號。此方式參考 [yt-dlp 的分段實作](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py)。它是備援，不代表已證明玩家的 403 必由請求形式造成。
- CDN 沒有等待提示時採 1、2、4 秒的有界退避；64 KiB 僅有數秒音訊，不再每次固定等 20 秒而耗盡約 12 秒的解碼緩衝。
- 有 Retry-After 時先等待指定時間，支援秒數及 HTTP 日期；429 不當成連結過期，也不先發 metadata／縮段請求。超出既有總等待上限就回報失敗，不提早違反伺服器指定時間重試。
- 等待可取消。保留每段及整首的等待預算、HTTP 逾時、32 MiB 接收上限、Range／長度／強 ETag 驗證；錯段及後段整首 200 不能接入解碼器。

沒有加入繞過登入、平台驗證或付費權限的處理。原有代理設定繼續使用；預下載下一首及保留快取預設仍關閉。

本機 23 項 YouTube 測試涵蓋完整跨段、真實 AAC 解碼、跳到 55 秒後的非靜音 PCM、最後一段、403／429、網址 range、等待提示、取消、斷線及物件改變。凍結的 0.15.3 JAR 在其中 9 項新增案例失敗，本候選版通過。這些測試使用自有音訊與本機服務，不用玩家登入資料；不代替 YouTube 外部服務或 Minecraft 實機驗收。

整體播放器、介面、聲學、運鏡及 D3D12 自有視窗回歸已通過。最後補上網址分段相容性回退後，重跑串流、音源診斷、播放恢復及 2.6.0 適配測試；最終 JAR 與整體回歸使用的類別逐一比較，僅允許 YouTube 類別變更。詳細執行範圍及雜湊見套件 VERIFICATION.json。原生 34 組 Release 驗證亦通過。

首次跨到未下載的遠處仍可能需要順序緩衝，尤其分片 MP4 不保證立即跳轉。本輪確認的是這段緩衝後能讀到真正的後半段音訊，不宣稱任意遠距秒切。若外部 CDN 持續拒絕，新版也不能保證所有歌曲可播。

原生 DLL 僅更新版本與精確設定描述雜湊；遊戲映像防護、相機、D3D11／D3D12、ModMenuBE 和其他插件沒有本輪功能變更。原版四版本共用套件，LeviLamina 26.60.7 獨立；保留 0.15.3 供回退，不自動安裝或發布。

完全退出 Minecraft，再一起替換相符版本的 DLL、JAR、manifest 及設定描述檔，保留帳號、設定和歌單。重播原本失敗的 YouTube 歌曲到曲尾，並試後半段／回跳；若再失敗，保留同次原生及 jvm-private 日誌。

English: Retain successful YouTube window sizes, try URL byte ranges on 403, use short bounded backoff, and respect numeric/date Retry-After before retrying. Same-object validation, response bounds, cancellation, proxy settings and progressive playback remain enforced. Owned loopback/PCM tests pass; live YouTube and Minecraft acceptance are pending. This is a local candidate, not a published release.
