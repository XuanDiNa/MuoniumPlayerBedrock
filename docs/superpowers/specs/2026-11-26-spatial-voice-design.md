# Spatial room voice and music broadcast

User approved adding distance attenuation, directional sound and block occlusion
to the existing client-only room audio. Work inline without subagents. Preserve
0.19.0 and all accepted earlier candidates; no installation or publication.

Extend existing room audio with an optional distance mode. Room mode remains the
default. Both participants must explicitly enable distance mode while in the same
Minecraft world; this is an out-of-band room, not game-server discovery. Existing
owned snapshots do not contain a shared server/world identifier. Do not imply
otherwise. Changing or losing the local world revokes spatial consent until the
user explicitly re-enables it. Dimension mismatch and absent/stale positions mute
spatial audio. Old clients continue room audio, never receive unsupported spatial
frames as full-volume audio.

Add negotiated spatial protocol v1 with bounded, finite source position,
dimension, per-world random stream identifier and range. Host still derives
identity and membership from authenticated connections. Spatial frames need a
spatial-capable host and recipient; malformed metadata is rejected, never
downgraded to room audio. Source coordinates are ephemeral, sent only after
explicit distance-mode consent, and are not logged or persisted.

Use existing WorldFrame/EnvironmentFrame values; no native hooks/layout changes.
Publish immutable snapshots on the host tick, trace bounded voxel geometry on the
voice worker. Mic and music share the transmitting player's source. While the
existing cinematic camera is active, use its already captured independent player
eye for the sender, and current camera pose for listening. No additional camera
demand or commands are created by voice.

Receive output becomes 48kHz stereo, capture and Opus remain mono. Equal-power
left/right panning, smooth distance rolloff to the chosen 8/16/32/64 block range
(default32), wall attenuation and low-pass filtering run per sender. Existing
native voxel collision shapes distinguish walls from air. Unknown geometry is
conservatively muffled, never described as confirmed clear. Do not run room
reverberation rays, game reads or file/network operations on the render thread.
Smooth gain/filter changes to avoid clicks. Preserve bounded queues and freshness.

HUD voice category adds distance mode, explicit re-enable and range choices.
Opening the page remains passive; microphone/deafen choices and shortcuts remain.
Deafen gates all paths. Package a new0.20.0 vanilla four-version/LL candidate,
full/update/source. Preserve codec and Opus dependency bytes. Verify synthetic
stereo/geometry, real relay/tunnel and compatibility, lifecycle/epoch/deafen,
HUD/input regressions, native fixtures and package hashes. Physical listening,
two-PC and game acceptance remain pending.
