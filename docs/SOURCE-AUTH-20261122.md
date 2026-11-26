# 已登入音源取流失敗：2026-11-22

本輪使用玩家的 logs.rar，經成員路徑驗證後僅抽取日誌，原始附件不變。附件清單、雜湊及脫敏行號版本保存在本機 artifacts/source-auth-20261122，不隨公開原始碼發行。

## 有效會話

原生日誌 MuoniumPlayerBedrock-20261122-140624-45228 宣告版本 0.15.1，載入器 LeviLamina，精確映像設定為 1.26.60.05-LeviLamina-player409。與同 PID 的 jvm-private-45228-383666687 對照：

- 第 18 行已讀取 QQ 帳號歌單，符合使用者「已登入」的回報，並非可據此判定所有取流請求都已授權。
- 第 44–59、107–124、147–155 行包含 Bilibili HTTP 412。影片包含 BV18LHj6XEDR、BV1nh411j75q。
- 第 4634–4798 行範圍出現 QQ「账号没有该曲播放权限，请检查登录与会员权益」的提示。0.15.1 來源中的此提示對應 104003 分支；日誌本身未列出數字碼，因此這是依程式分支作的歸類。該分支可能涉及登入或歌曲／音質授權，不能直接視為沒有版權。

JVM 首行原本硬編碼為 0.15.0；同份日誌已出現 0.15.1 的新診斷，所以不能只憑該行斷定載入舊 JAR。0.15.2 改為使用程式實際版本。附件沒有二進位檔，無法重新計算玩家實際載入 DLL／JAR 的雜湊。

## 缺口與修補

Bilibili 的普通 resolve 使用匿名 view，再以 hires=false 呼叫 playurl；保存的登入 Cookie 沒有進入兩個請求。修補只讓播放所需的固定 API 使用已存 Cookie，不改搜尋、不新增帳號資料讀取、不把 Cookie 放入音訊 CDN 請求。

QQ 的 vkeyRequest 缺少其他已登入 RPC 使用的 comm 帳號欄位，並沿用舊的 unsigned GET 路徑。參考 [QQMusicApi](https://github.com/luren-dc/QQMusicApi) 的 [song 模組](https://github.com/luren-dc/QQMusicApi/blob/main/qqmusic_api/modules/song.py)、[請求執行器](https://github.com/luren-dc/QQMusicApi/blob/main/qqmusic_api/core/executor.py) 及 [下載說明](https://github.com/luren-dc/QQMusicApi/blob/main/docs/tutorial/download.md)，更新模組／方法與 ctx 參數。傳輸沿用本項目其他已登入 RPC 的既有 MuSign／buildComm 簽名 POST 方式。參考庫的取流端點預設不要求簽名；這裡選擇簽名路徑以共用既有帳號請求形式，不把簽名本身視為恢復權限的證據。

一次有界的公開匿名對照中，舊 GET（有／無 comm）回 RPC 104009，新簽名路徑回 RPC 1000，均沒有 purl。這僅證實請求路徑與回應不同，**不是已登入成功播放的證據**。沒有使用玩家憑證對外測試。

## 可重現驗證

受控 HTTP 邊界測試執行實際播放器取流程式，以測試自有 Cookie 與 JSON 回應重現普通播放漏登入、QQ 漏 comm，以及被拒時的結果。修補前 18 項中有 3 項按預期失敗；修補後檢查登入／匿名 Bilibili、view／playurl 拒絕、無匿名重試、QQ 簽名與帳號欄位、成功 purl 和真實權限拒絕持續失敗。

平台 HTTP 412 仍可能因風控而發生；QQ 已登入仍可能缺少曲目或音質權限。保留拒絕並提供具體階段，不能把受控測試或匿名公開對照當作所有玩家可用的證明。原始上游供應來源保持不變，透過生成覆蓋層適配。此輪不安裝遊戲、不修改其他模組、不發布 GitHub。
