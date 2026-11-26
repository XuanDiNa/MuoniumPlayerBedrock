# 0.16.3：運鏡短暫取樣延遲修正

保留 0.16.2 的 YouTube 分段續讀修正，修復主機把仍有效相機資料過早丟棄、
重設整段運鏡與閒置計時的路徑。原作 2.6.1、運鏡路線與平滑演算法不變。

本機實際載入版本已核對為 0.16.1 LL，DLL／JAR 與凍結套件雜湊一致。
兩次原生 ApplicationLost 對應 Java 的 sample-lost／sample-start，
同一 epoch 與 input 在 47／63ms 後恢復，但導演重新等待十秒才開始。
對應原生視窗的最大影格間隔為 140／172ms；基礎 FOV 與畫面比例沒有跳變，
掛鉤衝突與故障計數均為零。這些證據不支持直接歸因於 FOV 或 Lamium。

原有協定允許同步讀出的相機資料有效 250ms，但主機在 100ms 就清空，
導致導演停止及黑邊失去顯示條件。本機 Win32 共用記憶體與原作導演測試
已重現：運鏡啟動後，讀到 140ms、仍有效的已套用相機樣本，舊程式立即停止。
修正將相機快照的同步驗證與讀取忙碌分開：

- 同步讀取仍按既有 250ms 協定檢查，不再另設 100ms 提早失效。
- Mutex 忙碌只可沿用最後成功驗證的快照最多 100ms，重複忙碌不續期，
  並且仍受資料本身的 250ms 期限限制。
- 成功讀到無效／過期資料、讀取錯誤、停用或失焦會立即清除，不復用舊值。
- 原生 150ms 命令期限、輸入／血量／場景檢查與精確映像／掛鉤防護不變。
- 有限數量的 CINEMATIC_HOST 日誌增加讀取狀態、忙碌與拒絕次數及最大
  同步樣本年齡，協助區分後續問題。不加入歌曲網址、Cookie 或帳號資料。

## 尚未確認的部分

新增 96 個連續鏡頭位置的遮擋測試，結果符合獨立射線／方塊體積參考，
尚未重現方塊投影算法錯誤。LYRIC_OCCLUSION 診斷每五秒彙總、最多 120 筆，
記錄缺少／其他場景方塊資料、遮擋輪廓與解除次數；不保留舊遮罩，
也沒有宣稱字幕整行或邊緣閃爍已全面修復。

同次日誌另有一次單階段的原生相機中斷後立即恢復，沒有對應 Java 導演重設。
目前不能把它和上述兩次中斷視為相同原因。舊日誌也無法直接區分每次相機
失效是讀取忙碌還是讀到較舊樣本；140／172ms 間隔與重現結果吻合，
仍須新版日誌與實機驗收。歌詞閃爍的全部成因尚未由這些日誌證實。

不修改遊戲、ModMenuBE、Lamium 或 Arke；不更換原生掛鉤、RVA、布局與
D3D11／D3D12 實作。不自動安裝或發佈。舊版候選套件完整保留。

English: Separate validated camera state lifetime from nonblocking mutex
contention. Protocol-valid synchronized samples no longer trigger a premature
100ms director reset. Busy reads retain a bounded, non-renewing 100ms lease;
invalid publications revoke immediately. Native leases and all admission guards
are unchanged. One brief native application loss remains unexplained; offline
regression coverage does not establish that all live visual flicker is resolved.
