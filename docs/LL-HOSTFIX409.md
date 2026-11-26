# 0.14.7 LeviLamina hostfix2 載入修正版

適用已安裝 0.14.7 的 Minecraft 1.26.60.05／LeviLamina 26.60.7。
只更新 MuoniumPlayerBedrock DLL，保留目前的 JAR、manifest、設定和 runtime。

## 原因與修正

收到的遊戲 EXE 對應玩家 2026-11-22 00:03:22 日誌中的完整指紋：

`409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04C`

這份檔案先加入啟動器區段，再重建匯入表，與先前已支援檔案的排列順序不同。
已逐位元組核對原版八個區段，61 組入口指紋、48 個完整原生函式及其
`.pdata` 邊界、三個表格位置、88 組原有匯入／IAT 均保持一致；完整匯入符號圖
也與已驗證的啟動器映像相同。本修正只新增這份檔案的精確大小及 SHA-256，
只允許 LeviLamina 版使用它。其他未知映像仍拒絕，未修改 RVA、原生結構或掛鉤。

## 安裝與測試

1. 完全退出 Minecraft。
2. 將替換包解壓到實例根目錄，只替換
   `mods/MuoniumPlayerBedrock/MuoniumPlayerBedrock.dll`。
3. 啟動遊戲，確認可以開啟播放器及 HUD 編輯、播放一首歌曲。
4. 日誌應顯示 `HOST_DIAGNOSTIC ... reason=matched`，以及
   `LOAD ADMITTED profile=1.26.60.05-LeviLamina-player409`。

原有支援映像仍會顯示各自的 profile 名稱，不要求出現 player409。
不要替換遊戲 EXE，也不必更新 ModMenuBE 或 Arke。此包沒有自動安裝或上傳。
原 0.14.7 發行包及 hostcheck1 診斷包保留，版本號保持 0.14.7 以沿用對應 JAR。

## 下次不必傳 EXE 的檢查方式

本 DLL 也包含本機報告功能。若今後遇到未收錄的有效 PE 映像，啟動一次會在
`mods/MuoniumPlayerBedrock/logs/Muonium-host-evidence-*.json` 留下小型報告。
可先傳這份 JSON，讓開發者核對區段與原生介面的指紋。
它不含 EXE 內容、機器碼、完整本機路徑或帳號資料，不上傳資料。
報告本身不會放行未知檔案。詳細欄位及限制見 source overlay 的
`docs/LL-HOST-EVIDENCE.md`。

已完成離線與自有程序驗證；沒有啟動玩家 EXE。遊戲內實際載入及操作仍待玩家驗收。
Source overlay 解壓至原 0.14.7 source ZIP，可重建本候選。
