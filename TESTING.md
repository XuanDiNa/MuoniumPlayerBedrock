# ModMenuBE Esc-back candidate: 0.13.0

Update only the player. ModMenuBE is not included, modified or installed.
Use your existing complete player runtime and select the correct loader edition.
Vanilla supports 1.26.45.01 / 1.26.51.01 / 1.26.52.03 / 1.26.60.05;
LeviLamina supports 1.26.60.05 separately.

## ModMenuBE 0.2.0-local4

1. Exit Minecraft. Extract the matching update into the instance root, replacing
   the player DLL, manifest, modmenu.json, root/interface settings descriptors and
   MuoniumBedrockHost.jar together. Preserve config, accounts, playlists and runtime.
2. F10 → MuoniumPlayerBedrock → Configure: confirm ordinary settings and Apply/Cancel.
   Workspace should be a separate entry, with Player status and Help tabs plus
   direct buttons for Player / HUD editor / Presets / Mini player / Island search.
   Island search requires its existing option enabled in HUD editing.
3. Open each interface from Workspace. It must open without selecting an enum or
   pressing Apply. Merely browsing or switching tabs must not start playback,
   authentication, searches or downloads. Nothing should open automatically.
4. Press Esc to go back one layer. An open modal/search/editor selection closes
   first; a child page returns to its parent. At the outermost Player / HUD editor /
   Presets / Mini player / Island search page, Esc finishes the existing exit
   animation and restores the original workspace. No extra Workspace banner or
   button remains. Hold Esc briefly: it must not unwind several layers at once.
   F10 can abandon an old handoff. Window close and focus loss do not imply Back.
5. Start playback or another explicit background operation. Close the page, switch
   focus and return: work must continue. Old pressed/dragged state must clear.
6. Check ordinary player shortcuts (defaults remain NONE), normal playback, lyrics,
   cinematic camera and existing D3D11/D3D12 presentation.

## Existing ModMenuBE local3 fallback

local3 does not gain a workspace. Configure still opens the player-owned button
page. Ordinary settings temporarily restores the known sidecar for Apply/Cancel;
closing settings restores direct button entry. Only exact known player descriptor
hashes are routed, backed up and verified. User config is never moved or reset.
An absent or invalid workspace API cannot impersonate a legacy Workspace API.
No installed ModMenuBE is automatically upgraded.

## Evidence and limits

The new workspace adapter is tested against the frozen local4 API/service and real
owner/manifest/folder checks. Native and Java tests remain separate from gameplay.
Creation acknowledgement is sent only after Java creates the requested screen,
with exact uint64 session/request identity and a deadline. The shared-memory
mailbox uses a private random name and mutex; it contains no credentials/media.
No automatic retry occurs after expiration or failure. Page close/focus loss never
stops background work. Native world/image/Arke guards are unchanged.

Manual Minecraft acceptance is pending. Current hardware OpenGL rendering tests
were unavailable (WGL driver reports OpenGL unavailable); compilation and the
non-GL real page/input/shared-memory tests are recorded separately. The delivery
report must not count unavailable pixel/render tests as passing.

繁體中文：local4 的 Configure 保留普通設定，工作頁提供直接操作按鈕。
按 Esc 逐層返回：先關閉彈窗／搜尋／編輯選取，再退出子頁；在最外層退場
動畫完成後恢復原工作頁。不再新增「返回工作頁」浮層或按鈕。長按 Esc 不會
連退數層。失焦只暫停互動，不停止播放或背景任務。local3 沿用工具按鈕頁。
