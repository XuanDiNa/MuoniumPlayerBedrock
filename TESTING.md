# 0.20.2 麥克風增益與房間語音驗收

0.20.1普通與距離語音已由玩家回饋可用。新增增益已通過離線驗證；依維護者決定跳過額外實體麥克風測試。若日後需檢查，可按 [麥克風增益步驟](docs/ROOM-VOICE.md) 比較100%／200%／400%及0%。此處不宣稱所有硬體或網路均已驗收；下方為歷史版本測試參考，0.18.0的BSChat部署不適用本版。

# 0.18.0 可選 BSChat 驗收

請先看 [語音與音樂廣播測試步驟](integrations/bschat/README.md)。本輪需雙人及已部署 BSChat 的測試伺服器；尚未進行遊戲內驗收。以下保留既有播放器測試參考。

# MuoniumPlayerBedrock 0.17.1 背景焦點測試

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定及現有 ModMenuBE。

1. 播放 YouTube 歌曲並切換歌曲，確認 Minecraft／播放器不會突然失焦，
   也不會出現背景 Chrome 的工作列或 Alt-Tab 視窗。
2. 切到其他應用程式再切回，確認點擊、鍵盤、拖動及 Esc 正常恢復；
   暫時失焦期間背景播放應繼續。只有明確點擊「瀏覽器登入」才開啟登入視窗。

本輪先驗收焦點修正，不必重測所有舊功能。完整播放／跳轉的本機實測與
已知啟動限制記錄在隨包 VERIFICATION.json，與遊戲內驗收分開。
[更新說明](docs/UPDATE-0.17.1.md)。以下保留歷史版本的測試記錄。

# MuoniumPlayerBedrock 0.17.0 設定頁與 YouTube 測試

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定及現有 ModMenuBE。

1. 在既有「HUD 編輯」的「介面與主題」選擇「新版設定中心」，關閉編輯再開啟。
   到「設定頁主題」依次選四張卡片，檢查深淺文字、主色取色器及動畫開關；
   可使用滾輪查看下方選項。重新開啟應保留外觀，舊版浮動面板仍能切回。
2. 播歌時修改上述外觀，音樂、佇列與 HUD 位置應保持正常；檢查 Esc、切出再返回。

YouTube 本輪實際完整播放／後半段跳轉仍未通過，已保留失敗證據待修。
不必為此重複先前的測試；本輪先確認上述設定頁功能。

本輪不必重測所有舊功能。若失敗，保留同次播放器 `jvm-private-*.log`
與模組原生日誌。[更新、驗證及限制](docs/UPDATE-0.17.0.md)。

# MuoniumPlayerBedrock 0.16.8 YouTube 歌詞測試

以這次異常的 YouTube 歌曲（擱淺官方 MV）檢查：

1. 開始播放後，歌詞不應直接跳到最後一句。
2. 若匹配到同步歌詞，確認正常推進；沒有可用時間軸時應顯示暫無歌詞。
3. 切換另一首 YouTube 歌曲，再切回，確認沒有沿用前一首的歌詞。

本輪未改播放／背景瀏覽器，原有驗證保留於 0.16.7。以下為先前版本測試記錄。

# MuoniumPlayerBedrock 0.16.7 YouTube 背景播放測試

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定及現有 ModMenuBE。

1. 確認已安裝 Chrome 或 Edge。在播放器 YouTube「瀏覽器登入」完成登入，
   關閉專用登入瀏覽器。沿用平時能播放 YouTube 網頁的網路與既有 YouTube 代理設定。
2. 開始播放 YouTube，載入、等待官方廣告及出聲後，都不應彈出 Chrome／Edge 播放視窗，
   工作列與 Alt-Tab 也不應出現專用播放視窗；登入視窗仍會正常顯示。
3. 同一首 Bad Apple 正常播放超過 1:04 並到曲尾，確認仍有聲音；再拖到 2:00，
   確認後半段能出聲並結束。首次起播、官方廣告及遠距緩衝可能需要等待。
4. 切歌、停止、Esc、切出再返回應正常；如平時使用一起聽，也用相同歌曲確認一次。
   留意新增的 CPU／RAM 負載與遊戲流暢度；本機獨立驗證不代表遊戲內已通過。

無需重跑所有舊功能。若失敗，保留同次 `jvm-private-*.log` 與模組原生日誌。
`PROGRESSIVE_ROUTE source=youtube mode=official-browser-pcm` 表示本版官方頁面路徑；
`YOUTUBE_BROWSER` 表示等待、準備或緩衝狀態。日誌不含 Cookie 或簽名媒體網址。
沒有 Chrome／Edge 時會沿用舊取流，該路徑的停播問題仍可能存在。
[實作、驗證與限制](docs/UPDATE-0.16.7.md)。

# 0.16.5 輸入恢復測試參考

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定及現有 ModMenuBE。
開啟播放器，切到瀏覽器或原有登入頁，再返回播放器；確認能點按鈕、搜尋輸入及按 Esc 返回。
切回時若仍按著 Shift、Alt 或滑鼠按鈕，其他操作應可繼續；原本按住的控制須放開後才可重新使用，
避免切回時誤啟動拖動、進度跳轉或快捷鍵。背景登入／下載／播放不應因失焦而停止。
若再出現整頁不能點，保留同次 `logs/jvm-private-*.log`；`OVERLAY_TRANSITION` 現在附加
輸入是否啟用及隔離按鍵／按鈕的數量，不記錄按鍵內容或帳號資料。
[修正與驗證範圍](docs/UPDATE-0.16.5.md)。獨立視窗驗證通過仍需實際遊戲驗收。

0.16.5 當時未修改 YouTube 取流；以下為先前版本的測試記錄。

## 0.16.4 YouTube 測試參考

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定，沿用目前 ModMenuBE。
開啟 YouTube 登入頁，使用現有會話重新驗證；若先前曾被標成 Cookie 過期，
可重新走原有登入或 Cookie 匯入流程，不需刪除全部設定。
用同一首〈Bad Apple〉確認正常播放超過 1:04 並到曲尾；再拖到 1:20 或更後面確認有聲音。
若仍失敗，保留同次模組內 `logs/jvm-private-*.log` 與 `logs/MuoniumPlayerBedrock-*.log`。
新增 `YOUTUBE_SESSION` 可分辨登入取流與匿名備援，`YOUTUBE_STREAM` 可分辨下載續讀失敗；
診斷不輸出 Cookie、認證 Token 或完整音訊網址。
[本次修正與限制](docs/UPDATE-0.16.4.md)。本輪沒有改運鏡、字幕遮擋與原生渲染，無需重測全部功能。

## 0.16.3 運鏡測試參考

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定，沿用目前 ModMenuBE。
在原本出現問題的位置，等待運鏡啟動，觀察有沒有突然切回玩家視角後又等十秒、
電影黑邊或歌詞閃一下。再確認鍵鼠輸入立即退出、開選單與切出返回正常。
[修正與未解情況](docs/UPDATE-0.16.3.md)。離線測試不等於所有閃爍都已修復。

包含下列 0.16.2 YouTube 修正，可用原本的 Bad Apple 測超過 1:04 及曲尾；
不用重測其餘全部功能。若仍出現問題，保留同次原生日誌與 jvm-private 日誌。

## 0.16.2 YouTube 續播測試參考

退出遊戲後安裝對應原版／LeviLamina 更新包，保留設定，沿用目前 ModMenuBE。
用原本在 1:04 停住的同一首〈Bad Apple〉正常播放超過該處，確認持續有聲音並能播到曲尾；
再拖到 1:20 或更後面，確認緩衝後恢復聲音。若仍失敗，保留同次模組內
`logs/jvm-private-*.log` 與 `logs/MuoniumPlayerBedrock-*.log`。
[修正與驗證範圍](docs/UPDATE-0.16.2.md)。本輪未改運鏡，無需重測全部功能。

## 0.16.1 運鏡測試參考

退出遊戲後安裝對應原版／LeviLamina 套件，保留設定，沿用目前的 ModMenuBE。
在原本覺得生硬的場景，以原有運鏡設定播放音樂，觀察約一分鐘的持續移動及一次換鏡：
鏡頭是否減少反覆快慢、接近機位是否自然減速。再到牆邊確認避讓，並確認鍵鼠輸入
立即退出、開選單、Esc 及切出返回正常。[本輪說明](docs/UPDATE-0.16.1.md)。
離線通過與實際觀感分開驗收，無需為此次運鏡修改重測所有音源。

## MuoniumPlayerBedrock 0.16.0 集中測試

退出遊戲後，依原版／LeviLamina 選擇套件，同時更新 DLL、JAR、manifest 和設定描述檔；保留使用者設定。ModMenuBE 另行安裝。首次安裝用 full，已有完整 Java 21 環境可用 update。

1. 開啟播放器，切換波點、QQ、網易雲及你使用的海外音源，查看發現頁、登入和帳號歌單；自行測試新增／刪除／收藏等明確操作。YouTube 瀏覽器登入需要在專用視窗完成登入後關閉該視窗。Spotify 先填自己的 Client ID，回呼登記為 `http://127.0.0.1:47478/callback`。
2. 播放音樂並切歌；依需要開啟響度均衡、自然快混。確認仍能邊下邊播、取消與跳轉。YouTube 先前會中斷的歌曲，測正常播放超過中斷點及拖到後半段，聽取恢復後是否真的有聲音。
3. 開啟場景聲學，在開放地面、石屋／洞穴、羊毛房及隔牆比較；播放世界定位音源時確認方向和遠近。這是背景的有限範圍採樣，主觀音效與遊戲效能需實測。
4. 開啟運鏡，等待設定的閒置時間（新設定預設十秒），觀察轉場、方塊附近路線、玩家輸入退出；再試 Esc、切出返回、HUD 編輯及電影黑邊。

若失敗，保留同次的原生日誌與 `jvm-private-*.log`，附上歌曲／音源、操作和時間。離線通過不等於真實平台或遊戲內已驗收。完整變更與尚有限制見 [0.16.0](docs/UPDATE-0.16.0.md)。

## 先前版本測試紀錄

### MuoniumPlayerBedrock 0.15.4 YouTube 續播修補測試

重播先前中斷的 YouTube 歌曲：先正常播放超過原中斷位置直到曲尾，再試拖到後半段及回跳。首次未緩衝的遠距跳轉可能仍需順序解碼，不能保證立即出聲；確認之後能恢復聲音。若原本在一起聽使用，也以相同方式確認；取消播放與切換歌曲應正常。若失敗，保留同次 `jvm-private-*.log` 與原生日誌，`YOUTUBE_STREAM` 記錄不含簽名網址或 Cookie。離線通過不代表外部平台或遊戲內已驗收。完整說明見 [本輪修補](docs/UPDATE-0.15.4.md)。

以下保留 0.15.0 的功能驗收參考，無需為這輪重測全部功能。

同步上游正式版 2.6.0（eb541684）。完整變更見 [更新說明](docs/UPDATE-0.15.0.md)。
本包只更新播放器，不包含 ModMenuBE 或載入器，不自動安裝。

## 安裝

1. 完全退出 Minecraft。原版四版本選 vanilla 包；LeviLamina 26.60.7 選
   levilamina 包，兩者不可混用。首次安裝 full，已有完整 Java 21 環境用 update。
2. 解壓至實例根目錄，一起替換 DLL、manifest、設定描述檔及完整 jvm/lib。
   保留設定、帳號、歌單、快取和 runtime；套件不含初始使用者設定檔。
3. ModMenuBE local4 由工作頁開啟；local3 由 Configure 工具按鈕開啟。
   維持已安裝的選單。快捷鍵預設無綁定，既有綁定不重設。

## 本輪合併驗收

不必重跑所有舊功能；在平時場景中一次完成即可。

1. **新頁面**：播放器側欄按下載管理，查看分類、狀態、捲動、返回。
   Skija／字型顯示已安裝；只開頁不應開始下載。
   開字型選擇頁，試篩選及選擇，檢查新舊 UI 均能正常使用。
2. **播放和混音**：用平時音源播放、切歌及自然接續。
   有多個分 P 的 Bilibili 曲目應顯示所選分 P 時長；仍能邊聽邊下載。
   如有使用智能混音，試啟用動態 EQ 並完成一次交接，再恢復個人設定。
   下一首預取和保留音樂快取預設仍關閉。
3. **運鏡／聲學／歌詞**：沿用既有設定，讓運鏡啟動並換鏡，確認路線與銜接正常。
   順帶比較空曠處及牆邊的場景聲學；如有使用桌面歌詞，試對齊設定。
4. **生命週期**：開關播放器、Esc、切出再返回，操作恢復，背景播放及已啟動工作持續。
   LeviLamina 如同時啟用受支援的 Lamium，也確認運鏡能正常進入與退出。

若有異常，保留同次原生、jvm-private 及 jvm-health 日誌。
PROGRESSIVE_ROUTE 可區分漸進播放與完整下載回退；CINEMATIC_HOOK revision=ll-coexist2
識別保留的 LL 相機修補。日誌不等於實際畫面或流暢度驗收。

## 驗證範圍

套件附 VERIFICATION.json，記錄 Java、原生 Release、自有視窗 Skija／D3D12 測試
及本次 DLL／JAR 雜湊。這些不是 Minecraft 人工驗收或 FPS 提升證明。
本輪未改原生遊戲掛鉤、RVA、相機布局、精確映像及 Arke 防護。
實體深度遮擋仍待完善，未知格式／加密／網路限制仍保留原回退。

English: Exit the game, extract the matching edition into the instance root,
and preserve settings/accounts/playlists. Check download management and font
selection, normal playback and Bilibili page duration, optional automix dynamic
EQ, cinematic transitions, acoustics and Esc/focus recovery in one session.
The full package includes Java 21; update packages require an existing complete
runtime. Install ModMenuBE separately. Offline/owned-window tests do not establish
Minecraft acceptance. Remote announcements and sponsor prompts remain disabled.
