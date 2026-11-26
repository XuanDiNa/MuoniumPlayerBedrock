# 0.14.7 LeviLamina hostcheck2：不用傳送 EXE 的本機檢查

適用已安裝 0.14.7 的 Minecraft 1.26.60.05／LeviLamina 26.60.7。
此文件說明 DLL 的本機報告功能；本輪 409C 相容修正另見 `LL-HOSTFIX409.md`。
不替換 JAR、manifest、設定、ModMenuBE、Arke 或遊戲 EXE。

## 玩家操作

1. 完全退出 Minecraft。
2. 將替換包解壓到實例根目錄，只替換
   `mods/MuoniumPlayerBedrock/MuoniumPlayerBedrock.dll`。
   保留原有 0.14.7 的其他檔案與設定。
3. 啟動遊戲一次。插件仍可能顯示拒絕載入，這次的目的是取得檢查報告。
4. 到 `mods/MuoniumPlayerBedrock/logs/`，傳回此次產生的
   **`Muonium-host-evidence-*.json`**，不需要傳送 EXE。

若沒有 JSON，傳回同一輪 `MuoniumPlayerBedrock-*.log` 中
`HOST_DIAGNOSTIC`、`HOST_EVIDENCE` 兩行即可。
`HOST_EVIDENCE status=saved` 表示已保存，`file=` 是 JSON 檔名。
不需要另外收集 JVM 日誌，因為拒絕載入發生在 Java 啟動前。

## 報告內容

- 完整檔案 SHA-256、大小及 PE 的架構、時間戳、入口、映像大小等數值。
- 程式區段、標頭、區段間隙及尾部資料的 SHA-256；不輸出原始內容。
- 最多 96 個區段、16 組資料目錄及 256 個經限制的匯入 DLL 名稱。
- 對應 26.60 時，核對已驗證的 8 個原始區段、61 組入口指紋、
  48 個完整原生函式及 3 個表格位置；另列載入器區段與目錄差異。

報告不含程式機器碼、EXE 內容、完整本機路徑、帳號、Cookie 或音源網址。
檢查只讀取本機檔案並保存 JSON，不上傳資料、不啟動播放器／音訊／網路。
讀取上限 512 MiB，報告上限 128 KiB。報告功能本身不調整允許載入清單或原生掛鉤。

`nativeEquivalenceConfirmed=true` 只代表上述已驗證原生內容與檔案佈局相符，
不是載入許可，也不是對未知載入器的完整安全判定。報告的
`nativeAuthorized` 固定為 `false`，產生報告不會設定或撤銷既有原生授權。
未知映像在人工審閱並另行驗證前仍然拒絕啟動。
若原生內容不同，僅靠指紋可能不足以補相容，不能承諾所有情況都能免 EXE 修復。

## 範圍與驗證

已在自有程序測試真正 LL DLL 拒絕未知映像時輸出 JSON，沒有啟動遊戲。
另用已驗證映像及自有副本檢查：相同內容、只改標頭、修改原生內容、
雜湊不符、截斷／超大檔案與不合法區段。實際玩家映像仍待回傳報告。

本候選保留版本號 0.14.7，沿用未改動的對應 JAR。原發行包及 hostcheck1 均保留。
Source overlay 解壓覆蓋到已驗證的 0.14.7 source ZIP，可重建此候選。
參考標頭可由 `tools/generate_host_evidence_reference.py --help` 指定已驗證的
本機映像重生；原始碼包只包含指紋，不包含遊戲執行檔。
