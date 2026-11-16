# 安裝與更新

測試更新 **0.13.0-test6-esc**，提供完整包及更新包，不包含 ModMenuBE。

1. 完全退出 Minecraft。原版四版本使用同一個 vanilla 更新包；LeviLamina
   26.60.7 使用獨立 LL 包。首次安裝 full.zip，已含 Java21／播放器依賴；
   已有完整環境才用 update.zip。ModMenuBE 與載入器需另外安裝。
2. 解壓所選 ZIP 至實例根目錄，替換 mods/MuoniumPlayerBedrock/ 的 DLL、
   manifest、modmenu.json、根目錄及 interface 的設定描述檔與 host JAR。
3. 已用 ModMenuBE local4：Configure 保留普通設定；工作頁提供五個直接開頁
   按鈕。按 Esc 逐層返回，最外層退場後恢復工作頁；失焦不會停止背景工作。
4. 仍用 local3：Configure 直接開工具按鈕頁，「普通設定」暫時返回選單設定。
   只處理已知描述檔，不必手動移除。無須更新選單。
5. 保留現有 MuoniumPlayerBedrock.json、帳號、歌單、快取及 runtime。更新包
   及完整包都不包含會覆寫的初始設定；首次啟動由模組建立缺少的設定。
   八項快捷鍵預設仍未綁定。舊套件保留供回退。

不修改選單 DLL、遊戲 EXE 或 Arke。自訂、唯讀、重新解析或多重連結描述檔
會保留。請勿混用不同版本 DLL 與 JAR；保留舊完整套件後再更新。

[遷移說明](modmenube-migration.md) · [測試步驟](../TESTING.md)

English: Choose the matching full edition for a first installation; it includes
Java21 and player dependencies. The update edition needs an existing full runtime.
Install compatible ModMenuBE/the loader separately. local4 has separate ordinary
Configure and Workspace buttons; local3 retains owned Configure buttons. Both ZIP
types preserve settings, accounts and playlists. No game is bundled or installed.
Manual gameplay acceptance remains pending.
