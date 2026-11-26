# Spatial room audio implementation

Follow the approved three-feature scope in the adjacent spec; execute inline.

- [x] Write failing spatial relay/geometry and lifecycle tests against0.19.
- [x] Add bounded source metadata/capability negotiation; reject unsupported or
  malformed spatial frames without downgrading to ordinary audio.
- [x] Implement stereo spatial mixer using immutable camera/voxel snapshots,
  smoothed distance/pan/occlusion filtering on the audio worker.
- [x] Connect host snapshots and existing independent cinematic-player sample;
  revoke spatial consent on world loss/change. Add passive HUD mode/range/rearm.
- [x] Verify codec/relay/tunnel/lifecycle/HUD/input and existing regressions;
  build native version-only changes and run34 native suites.
- [x] Package0.20.0 full/update/source, compare production bytes and frozen
  candidates, reopen/hash/audit. Document pending in-game/two-PC acceptance.

## Offline completion

306 JVM tests in13 tasks and34 native Release suites passed. Real ModMenuBE
passive-settings interoperability and the deployed-JAR codec probe passed.
Five archives were reopened and public-source/font/path/credential audits passed.
Only native version and exact descriptor history changed; all1874 vendor files,
codec DLL and frozen0.17.1/0.18.0/0.19.0 release files remained unchanged.

Candidate is in the project's releases/0.20.0-20261126 directory. Packaged source
is the snapshot before final archive audit; external VERIFICATION.json and
PUBLIC-ASSET-AUDIT.json record the final results. Physical microphone, two-PC
listening and Minecraft acceptance remain pending. No installation/publication.
