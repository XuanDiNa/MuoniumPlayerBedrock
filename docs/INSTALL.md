# 安裝與更新

公開發布 **0.20.2**（同步上游 2.6.1），提供完整包及更新包，不包含 ModMenuBE。

1. 完全退出 Minecraft。原版四版本使用同一個 vanilla 更新包；LeviLamina
   26.60.7 使用獨立 LL 包。首次安裝 full.zip，已含 Java21／播放器依賴；
   已有完整環境才用 update.zip。ModMenuBE 與載入器需另外安裝。
2. 解壓所選 ZIP 至實例根目錄，替換 mods/MuoniumPlayerBedrock/ 的 DLL、
   manifest、modmenu.json、根目錄及 interface 的設定描述檔與完整 jvm/lib 內的 JAR，並放入本版 jvm/voice/MuoniumVoice.dll。
3. 提供 Workspace V1 API 的 ModMenuBE：Configure 保留普通設定，工作頁提供五個直接開頁按鈕。
   按 Esc 逐層返回，最外層退場後恢復工作頁；失焦不會停止背景工作。
   目前完整候選與配對狀態見下方說明。
4. 仍用 local3：Configure 直接開工具按鈕頁，「普通設定」暫時返回選單設定。
   只處理已知描述檔，不必手動移除。無須更新選單。
5. 保留現有 MuoniumPlayerBedrock.json、帳號、歌單、快取及 runtime。更新包
   及完整包都不包含會覆寫的初始設定；首次啟動由模組建立缺少的設定。
   十項快捷鍵預設仍未綁定。舊套件保留供回退。

不修改選單 DLL、遊戲 EXE 或 Arke。自訂、唯讀、重新解析或多重連結描述檔
會保留。請勿混用不同版本 DLL 與 JAR；保留舊完整套件後再更新。

YouTube 官方頁面播放另需已安裝 Chrome／Edge，完成模組自有瀏覽器登入後關閉登入視窗。
播放程序從啟動起無可見視窗；需要重新登入時，專用登入頁仍正常顯示。

## ModMenuBE 配套候選（2026-11-27）

工作頁需要另外安裝提供 **Workspace V1 API** 的 ModMenuBE。完整整合候選為 [review7 分支](https://github.com/XuanDiNa/ModMenuBE/tree/codex/review7-settings-fixes)，來源 commit `020adfdd3a43fd1abb3feba838925431baddfd3e`，包含被動設定頁、選用原生入口與工作頁。此分支仍在上游審查，manifest 版本為 `0.2.1`，不代表上游已正式發布或合併。ModMenuBE 側已完成 Windows Debug／Release 與 Android arm64 離線驗證；**這份新 DLL 與 Muonium 0.20.2 的遊戲內配對仍待驗收**。更新前請完全退出遊戲並保留設定。

候選 DLL：**2,213,376 bytes**；SHA-256：

```text
F8B3EE647F8F604CBF7361F10B494BED11B095581B70C8D4FA5C37804E4AFF04
```

不能只用 `0.2.1` 判斷是否同一份 DLL；舊候選的人工驗收不適用於新二進位。另外回報的玩家崩潰問題仍待處理，此候選不宣稱修復該問題。

[遷移說明](modmenube-migration.md) · [測試步驟](../TESTING.md)

English: Choose the matching full edition for a first installation; it includes
Java21 and player dependencies. The update edition needs an existing full runtime.
Install the loader separately. Workspace requires a separately installed ModMenuBE with the **Workspace V1 API**. The complete integration candidate is the [review7 branch](https://github.com/XuanDiNa/ModMenuBE/tree/codex/review7-settings-fixes), commit `020adfdd3a43fd1abb3feba838925431baddfd3e`, including passive settings, optional native entry and Workspace. This branch is under upstream review; its manifest version remains `0.2.1`, and it is not an upstream release or merge. The ModMenuBE candidate has Windows Debug/Release and Android arm64 offline verification, but **in-game pairing of this new DLL with Muonium 0.20.2 is still pending**. Exit the game before updating and retain settings.
The historical local3 Configure fallback remains. Both ZIP
types preserve settings, accounts and playlists. No game is bundled or installed.
Ordinary/distance voice were user-confirmed. Extra physical microphone testing for the new gain control was explicitly skipped; offline checks do not cover all devices or networks.
