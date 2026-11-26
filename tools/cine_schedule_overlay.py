"""Read-only Bedrock diagnostics for the upstream immutable camera session.

The former monolithic scheduler repairs are superseded by CineSession. Keep its
 tick ownership and immutable frame sampling, without reapplying legacy patches.
"""
from cine_motion_overlay import apply as damped_motion
def apply(text):
    assert text.count('private static final CineSession SESSION = new CineSession();') == 1
    marker = '    private CineDirector() {'
    assert text.count(marker) == 1
    assert text.count('        MobSample nearestMovingMob(double rangeBlocks);') == 1
    text=text.replace('        MobSample nearestMovingMob(double rangeBlocks);', '''        default boolean cameraPlanningEnabled() { return false; }
        default boolean cameraPathClear(Vec3Holder from, Vec3Holder to) { return false; }
        default boolean cameraAnchorVisible(Vec3Holder anchor, Vec3Holder to) { return cameraPathClear(anchor, to); }

        MobSample nearestMovingMob(double rangeBlocks);''')
    return text.replace(marker, '''    public static int diagnosticShotSequence() { return SESSION.shotSequence(); }

''' + marker)

def session(text):
    """Retain 2.6.1 tracking smoothing; add only bounded Bedrock route preview."""
    assert 'trackingResponseScale(trackingSoftness)' in text
    text=text.replace('    private Frame frame = Frame.IDLE;', '''    private CineRoutePlanner.Route cameraRoute;
    private double routeStartMotion, routeEndMotion;
    private long routeRetryAt;
    private int routeDiagnostics;
    private Frame frame = Frame.IDLE;''')
    text=text.replace('        planCut(settings, grid, now, now);\n    }',
        '        planCut(settings, grid, now, now);\n        planCameraRoute(probe, scene, settings);\n    }')
    marker='    private void planCut(CineSettings.Snapshot settings'
    assert text.count(marker)==1
    text=text.replace(marker,'''    private void planCameraRoute(CineDirector.SceneProbe probe, Scene scene, CineSettings.Snapshot settings) {
        if (!probe.cameraPlanningEnabled()) { cameraRoute = null; return; }
        long began = System.nanoTime();
        routeStartMotion = motionSeconds;
        double remaining = Math.max(0.05d, (cutDeadline - previousTickNanos) / 1.0e9d);
        double duration = remaining * settings.speedMultiplier();
        routeEndMotion = routeStartMotion + Math.max(0.05d, duration);
        routeRetryAt = previousTickNanos + 500_000_000L;
        int count = Math.max(2, Math.min(257, (int) Math.ceil(duration / 0.1d) + 1));
        var points = new CineDirector.Vec3Holder[count];
        for (int i = 0; i < count; i++) {
            double age = Math.max(0d, motionSeconds - shotMotionStart) + duration * i / (count - 1);
            points[i] = targetAt(scene, settings, age).position();
        }
        CinePose current = frame.current();
        var start = current == null ? new CineDirector.Vec3Holder(scene.x(), scene.eyeY(), scene.z())
                : new CineDirector.Vec3Holder(current.x(), current.y(), current.z());
        cameraRoute = CineRoutePlanner.plan(probe, start,
                new CineDirector.Vec3Holder(scene.x(), scene.eyeY(), scene.z()), points);
        if (routeDiagnostics++ < 64) System.out.println("CINEMATIC_ROUTE mode=" + cameraRoute.mode()
                + " attempts=" + cameraRoute.attempts() + " samples=" + count + " shot=" + shotSequence
                + " elapsedUs=" + Math.max(0L, (System.nanoTime() - began) / 1000L));
    }

'''+marker)
    old='''        CineDirector.Vec3Holder safe = probe.clipCamera(scene.x(), scene.eyeY(), scene.z(),
                target.position().x(), target.position().y(), target.position().z());'''
    assert text.count(old)==1
    text=text.replace(old,'''        CineDirector.Vec3Holder safe;
        if (probe.cameraPlanningEnabled()) {
            if (cameraRoute == null || motionSeconds > routeEndMotion) planCameraRoute(probe, scene, settings);
            safe = cameraRoute.sample((motionSeconds - routeStartMotion) / Math.max(0.05d, routeEndMotion - routeStartMotion));
            CinePose current = frame.current();
            var start = current == null ? new CineDirector.Vec3Holder(scene.x(), scene.eyeY(), scene.z())
                    : new CineDirector.Vec3Holder(current.x(), current.y(), current.z());
            var anchor = new CineDirector.Vec3Holder(scene.x(), scene.eyeY(), scene.z());
            if (!probe.cameraPathClear(start, safe) || !probe.cameraAnchorVisible(anchor, safe)) {
                if (previousTickNanos >= routeRetryAt) {
                    planCameraRoute(probe, scene, settings);
                    safe = cameraRoute.sample(0d);
                }
                if (!probe.cameraPathClear(start, safe) || !probe.cameraAnchorVisible(anchor, safe)) safe = start;
            }
        } else safe = probe.clipCamera(scene.x(), scene.eyeY(), scene.z(),
                target.position().x(), target.position().y(), target.position().z());''')
    old='''    private Target target(Scene scene, CineSettings.Snapshot settings) {
        double age = Math.max(0d, motionSeconds - shotMotionStart);'''
    assert text.count(old)==1
    text=text.replace(old,'''    private Target target(Scene scene, CineSettings.Snapshot settings) {
        return targetAt(scene, settings, Math.max(0d, motionSeconds - shotMotionStart));
    }

    private Target targetAt(Scene scene, CineSettings.Snapshot settings, double age) {''')
    old='''        boolean clipped = Math.abs(path.x() - position.x()) + Math.abs(path.y() - position.y())'''
    assert text.count(old)==1
    text=text.replace(old,'''        if (probe.cameraPlanningEnabled() && (!probe.cameraPathClear(
                new CineDirector.Vec3Holder(old.x(), old.y(), old.z()), path)
                || !probe.cameraAnchorVisible(new CineDirector.Vec3Holder(scene.x(), scene.eyeY(), scene.z()), path))) {
            // Acceleration can carry a spring outside the planned corridor.
            // Stop that step rather than crossing a block or an unknown cell.
            path = new CineDirector.Vec3Holder(old.x(), old.y(), old.z());
        }
'''+old)
    return damped_motion(text)
