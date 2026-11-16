# ModMenuBE integration: 0.13.0

The local4 adapter uses the frozen 0.2.0-local4 public workspace API version 1.
See vendor/modmenube-local4-workspace/PROVENANCE.json for source/hash provenance.
Those service/runtime sources are test-only; shipped player DLLs contain only the
adapter and public header. No ModMenu DLL is redistributed or changed.

The worker discovers already-loaded ModMenuBE.dll, validates the versioned public
API plus workspace version/table size and all function ownership before calling.
If the workspace export is present but invalid/unready, it waits without applying
local3's descriptor routing. Valid local4 keeps ordinary Configure and registers
status/help pages plus five explicit external logical targets from modmenu.json.
The owner callback address, manifest name/version/entry and plugin folder must pass
ModMenuBE's own admission. There is no old Workspace export or hash impersonation.

Only an explicit external command starts Java. Exact uint64 request/session IDs
are retained through an owned 64-byte mutex-protected shared-memory mailbox.
Native controls continue independently. Java creates the actual target page and
reports Created/Failed. Only a non-expired Created report acknowledges success to
ModMenuBE. Expiration, disabled state, child failure and rejected acknowledgements
cannot replay the command. No account/network/media data enters the workspace.

Esc follows the original page's back handling: modal/search/editor selections
dismiss first, child pages return to their parent, and only the outermost page's
completed Esc exit requests Return to Workspace. Animated exits wait for actual
screen closure; changing pages discards an abandoned exit intent. A held Esc does
not repeat through multiple layers. No extra Workspace banner or button is added.
Return hides only interaction and asks the worker to restore the original menu
handoff. The menu revalidates its saved scene/provider session. Ordinary window
close, minimize and focus loss do not request Return or stop background work.
Gesture clearing and input release gating retain their existing behavior.

## local3 compatibility

Without the workspace export, Configure keeps test4's setExternalScreen route,
registered by manifest name MuoniumPlayerBedrock. Its callback only atomically
queues opening the owned button page. Ordinary settings restores the root sidecar,
uses public rescan/open and preserves Apply/Cancel. Once that page closes,
Configure returns to direct buttons. Only exact released player descriptor hashes
are routed, with byte-verified backups. Custom/readonly/reparse/linked descriptors
are preserved. No user config/account/cache/playlists are moved or reset.

## Validation

Frozen local4 API, protocol, identity checks and service tests exercise five
targets, exact high-bit uint64 IDs, asynchronous creation/failure/return, deadlines,
provider shutdown, disabled state and both module load orders. Java tests exercise
the real private mapping, real mini/tools pages and root Esc return, modal/child
back, delayed/abandoned exits, early Esc before acknowledgement, cancellation,
no implicit return on focus/close, invalid/expired requests and background lifetime.
Actual rendering and Minecraft gameplay are separate acceptance categories.
