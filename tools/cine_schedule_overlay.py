"""Checked scheduler repairs; keep the pinned upstream source untouched."""

def once(text, old, new):
    assert text.count(old) == 1, old[:100]
    return text.replace(old, new)

def apply(text):
    text = once(text, '    private CineDirector() {', '''    // Read-only diagnostics for the Bedrock camera handoff. They do not alter
    // shot timing, smoothing, beat detection or the intentional FOV envelope.
    public static int diagnosticShotSequence() { return currentShotSequence; }
    public static long diagnosticPulseNanos() { return lastPunchNanos; }

    private CineDirector() {''')
    text = once(text, '    private static long lastCutAtTick;', '''    private static long lastCutAtTick;
    // A beat estimate can change its anchor/rate; it cannot shorten a shot's
    // real lifetime. This clock belongs to the scheduler, never the renderer.
    private static long shotStartedNanos;''')
    text = once(text, '''        if (now >= transitionEndNanos) {
            transitionEffect = 0;
            return null;
        }''', '''        if (now >= transitionEndNanos) {
            // Read-only: tickActive owns completion and the next-shot anchor.
            return null;
        }''')
    text = once(text, '''    private static void beginTransitionTo(SceneProbe probe, long now) {
        CineBeatSource.Grid grid''', '''    private static void beginTransitionTo(SceneProbe probe, long now) {
        long minimumNanos = (long) (CineSettings.minShotSeconds() * 1.0e9);
        if (now - shotStartedNanos < minimumNanos) return;
        CineBeatSource.Grid grid''')
    text = once(text, '        int effect = pickTransitionEffect();', '''        int effect = pickTransitionEffect();
        if (effect == 0) {
            prepareShot(probe, now, false);
            lastCutAtTick = activeTicks;
            transitionShotPending = false;
            transitionCovering = false;
            transitionEffect = 0;
            return;
        }''')
    text = once(text, '''    private static void prepareShot(SceneProbe probe, long now, boolean firstShot) {
        boolean isFirst''', '''    private static void prepareShot(SceneProbe probe, long now, boolean firstShot) {
        shotStartedNanos = now;
        boolean isFirst''')
    text = once(text, '''        shotLogCounter = 0;
    }''', '''        shotLogCounter = 0;
        shotStartedNanos = 0L;
        lastCutAtTick = 0L;
    }''')
    text = once(text, '''        if (value) {
            currentShotSequence = 0;''', '''        if (value) {
            shotStartedNanos = System.nanoTime();
            currentShotSequence = 0;''')
    return text
