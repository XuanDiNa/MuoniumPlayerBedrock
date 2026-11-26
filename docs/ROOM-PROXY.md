# 一起聽連接代理 / Listen-together connection proxy

0.14.4 的 Bedrock 自有功能，不是上游 MuoniumPlayer 的代理支援聲明。

0.14.5 修正邀請複製流程：房主的「複製完整邀請」優先取得已就緒的公網邀請，
無需手填直連地址。未啟用公網穿透時，完整直連邀請必須包含房主地址；
「僅端口密碼」不包含地址，不能單獨貼入加入欄使用。

## 入口與用途

播放器 → 一起聽 → 直連房間 → **連接代理**。

- **直連**：預設。不讀取 JVM 全局代理；全局／TUN 仍可按系統路由接管連線。
- **系統代理**：唯讀 Windows 當前使用者的手動代理、PAC／WPAD。
  系統規則明確指定直連時允許直連；自動規則解析失敗會報錯，不悄悄回退。
- **HTTP CONNECT**：填寫代理主機名／IP 與埠，不填 `http://`、密碼或路徑。
- **SOCKS5**：同上；房間域名交給代理解析，無需本地 DNS 能解析房間地址。

HTTP CONNECT、SOCKS5 此版僅支援無認證代理。選中某一代理後連線失敗會報錯，
不會在背景改走直連。TLS 仍驗證房間憑證與主機名，不提供關閉驗證選項。

按保存才寫入播放器自己的 `config/muonium/room-proxy.json`；不修改 Windows、
Minecraft 或加速器設定。設定在下次加入時生效，不會強制中斷目前房間。
Esc 或暫時失焦不會取消已明確啟動的保存、房間連線或音樂工作。

## 適用邊界

此版只代理**加入現有 `wss://…trycloudflare.com/room/…` 公網邀請的同步連線**。
原本的局域網／`地址:埠#密碼` 直連不經此設定；本機轉接埠也不經代理。
房主的 `cloudflared.exe` 穿透程序、帳號登入、音樂／圖片下載保留原本路徑。
房主如需加速穿透，須由其全局／TUN 路由或加速器對該程序提供支援。

中國加入者可經自訂代理連入，海外加入者可以直連同一房主。代理不提供房間服務，
房主通道仍需可用；此更新沒有部署新的集中式服務，也沒有自動配對同服玩家。
只加速 Minecraft 的加速器不一定覆蓋背景 `javaw.exe` 或我們的房間目的地址。
此功能需要代理軟體確實提供可用的 HTTP／SOCKS5 入口，不能憑空產生代理。

## 驗證與診斷

離線測試使用自有回環代理和真正的 TLS／WebSocket 往返資料：HTTP CONNECT、
SOCKS5 遠端域名、憑證主機名驗證、拒絕不回退、超時與取消、設定重啟保存，
以及實際 Skija 設定頁的按鈕、Esc／失焦和背景保存。
測試憑證只存在 test resources，不加入播放器 runtime JAR，也不安裝到系統憑證庫。

手動測試使用房主的公網邀請。先保存正確代理再加入，確認房間與切歌同步。
再用不可用的代理埠重試，確認失敗且不回退，最後恢復正常設定。
跨境穩定性、實際加速器覆蓋與各人的 PAC 規則仍需在對應網路驗收。

日誌 `ROOM_PROXY connected mode=… route=… tls=true` 記錄選擇與解析後路徑。
不記錄邀請、房間密碼或代理地址。系統模式的 `route=DIRECT` 可以是系統規則結果，
不表示發生失敗回退。連線完成不等於房間握手與加入成功。

## English

Player → Listen together → Direct room → Connection proxy. Default: Direct.
Windows user proxy rules (manual/PAC/WPAD), unauthenticated HTTP CONNECT and
SOCKS5 are supported for joining existing encrypted internet room invitations.
Save applies to the next join; opening the page does not connect or save.
Proxy failure never silently falls back to direct. TLS certificate/hostname
validation remains enabled. SOCKS5 resolves the room name at the proxy.

LAN/address-direct connections, the host's cloudflared helper, login, artwork
and music downloads retain their existing routes. No system proxy settings or
accelerator rules are changed, and no central room service is deployed.
A Minecraft-only accelerator may not cover the separate Java process or room
endpoint. Real mainland/overseas connectivity still requires manual testing.
