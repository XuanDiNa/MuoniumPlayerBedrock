# 0.16.1：運鏡連續速度修正

修正運鏡在整段移動中反覆加減速的問題。保留 0.16.0 同步的原作 2.6.1 功能。

舊控制器會追趕「下一個 tick 就到達目標」的位置，到達時直接把速度歸零。
慢速移動的目標也可能觸發這條路徑，令原本用於平滑的幀間插值每 50ms
重新減速和加速；接近靜止機位時則可能突然停住。

現在的位置、朝向、傾斜與 FOV 偏移採用有速度／加速度上限的阻尼跟隨。
接近機位時提早減速；持續移動時保留連續速度。只改建置時的 Bedrock 適配，
原作來源快照保持不變。既有運鏡速度、追焦柔和度、閒置時間及其他設定不覆寫。

保留整段路線預檢、方塊與未知區域限制、相機半徑、輸入立即退出、換鏡時限、
電影黑邊及 D3D11／D3D12。沒有增加原生掛鉤或更改 RVA、映像與布局防護；
原生 DLL 只更新版本和設定描述檔的精確雜湊。

## 驗證與測試

新增測試直接驅動實際 CineSession，觀察最終相機幀，而非另一份運鏡算法。
0.16.0 在六項測試中全部失敗；修正後全部通過：

- 自由空間接近靜止機位時，位置、yaw、pitch、roll、FOV 均保留加速度上限。
- 以 240Hz 讀取 20Hz 相機幀，1m/s 勻速目標不再每個 tick 反覆加減速。
  舊輸出約 0.096–1.592m/s；新輸出約 1.000m/s。這是合成測試數據，並非遊戲 FPS。

完全退出遊戲後安裝對應更新包，保留設定並沿用目前的 ModMenuBE。
在原本覺得生硬的場景播放音樂，讓同一套運鏡設定跑過一段移動及一次換鏡；
確認整段移動的速度、朝向與接近機位的減速是否自然。再到牆邊觀察避讓，
並確認鍵鼠輸入、開選單、Esc 和切出返回正常。

原版 1.26.45.01／1.26.51.01／1.26.52.03／1.26.60.05 共用套件；
LeviLamina 26.60.7 使用獨立套件。離線測試不代表新版的實際觀感已驗收。
實體歌詞遮擋、生物追焦資料及其他既有待完善項目維持原狀。

English: 0.16.1 replaces one-tick camera pursuit with bounded damped tracking.
Moving targets retain continuous velocity; stationary destinations brake before
arrival. Existing shot scheduling, collision/radius limits, input revocation,
settings and all upstream 2.6.1 features remain. Offline output tests are separate
from subjective Minecraft gameplay acceptance.
