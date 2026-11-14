# 安裝與更新

**配套需求：對應版本的 ModMenuBedrock；LeviLamina 使用 ModMenuBedrockLL。** 播放器入口及 HUD 編輯位於 ModMenu 的工作頁，快捷鍵預設無綁定。兩者需分別安裝。

1. 完全退出 Minecraft，下載對應遊戲版本及載入器的 `full.zip`。
2. 解壓到實例根目錄，得到 `mods/MuoniumPlayerBedrock/`；若啟動器使用其他插件位置，放入對應目錄。
3. 原生版需要支援 preload-native 的載入器。LeviLamina 版需要 26.60.7；入口不同，不要混用或同時啟用。
4. 安裝對應版本的 [ModMenuBedrock](https://github.com/XuanDiNa/ModMenuBedrock)；LeviLamina 使用對應 ModMenuBedrockLL。播放器不附帶或替換 ModMenu／Arke。
5. 在工作頁點「開啟播放器」／「HUD 編輯」，或自行綁定快捷鍵；預設無綁定。

完整包包含 Java 執行環境及 JAR 依賴。可選音源／分析元件按需要安裝。

## 保留資料更新

`update.zip` 適用已裝好 0.7.0-test1 或更新完整執行環境的使用者。它更新 DLL、manifest、設定描述檔、JAR 及授權文件，不覆寫 `MuoniumPlayerBedrock.json`、帳號、歌單或自訂快取。若安裝不完整，先保留設定及私人資料，再使用 full 包。

`PUBLIC-EDITION.json` 記錄基準版本、DLL/JAR 身分及驗收邊界。用 Release 的 `SHA256SUMS.txt` 核對下載。不要混用不同包的 DLL／JAR。

## 檢查與回報

確認工作頁、播放、切歌、佇列、歌詞，再確認新舊 UI、Esc、切出返回及使用中的 D3D 模式。3D 歌詞與運鏡另外檢查。保留穩定版供回退。

回報提供遊戲版本、載入器、套件名稱、出問題時間及相關日誌。分享前檢查私人資訊，不附 cookie、憑證或完整私人設定。

English: Exit Minecraft and extract the matching full ZIP into the instance root. Native preload and LeviLamina DLLs are not interchangeable. Updates require a complete existing runtime, preserve user data, and include public notices. Hotkeys start unbound.
