# 0.16.8 — YouTube 歌詞時間軸修正

修正 YouTube 歌詞一開始就跳到最後一句：官方 `timedLyricsData` 有時只有文字，
缺少 `cueRange.startTimeMilliseconds`。先前沿用的解析會把缺少的時間變成零秒，
把整首歌誤認為全部從零秒開始。

現在只採用完整、有效的官方時間軸；保留真正的零秒起始與同時演唱行。
缺少時間、非法或倒退時間、整首多行時間完全相同時，不再製造零秒時間戳。
純文字仍保留為純文字，歌詞載入會繼續走原有同步歌詞匹配。
若沒有可匹配的時間軸，顯示暫無歌詞，不猜測各行時間。
第三方匹配仍可能受到歌名、版本及來源可用性影響。

沿用 0.16.7 的 YouTube 背景瀏覽器播放與音訊管線；本版未改取流、相機、
輸入、D3D11／D3D12 或遊戲掛鉤。原作 Java 來源保留，修正透過本專案的來源適配生成。
四個原版版本合包，LeviLamina 另包；未自動安裝或公開發布。

驗證涵蓋文字型官方回應、有效時間軸、零秒、缺漏與非法時間、同時演唱、
歌詞載入回退，以及既有 YouTube／音訊／輸入回歸；具體結果見隨包 `VERIFICATION.json`。
遊戲內仍待使用者驗收。保留 0.16.7 供回退。

English: Validate YouTube official lyric cues instead of defaulting missing times
to zero. Untimed official lyrics allow the existing timed matching chain to run;
no synthetic timings are generated. The 0.16.7 background browser/audio path is
preserved. Offline/service evidence is separate from pending gameplay acceptance.
