"""Build-only, auditable platform overlays. Never modifies the pinned reference.

Apache-2.0 upstream files retain their copyright and full source. The report lists
every changed source and its before/after SHA256; business/UI roots stay shared.
"""
from pathlib import Path
import hashlib
import json
import re
from upstream25 import verify, DELTA
from streaming_mix_overlay import apply as streaming_mix_overlay
from cine_schedule_overlay import apply as cine_schedule_overlay
from ui_presentation_overlay import apply as ui_presentation_overlay

ROOT = Path(__file__).resolve().parents[1]
REF = DELTA
OUT = ROOT / 'jvm/build/generated/platform'
CLIENT = 'com.muoniumplayer.bedrock.BedrockClient'

def method_body(text, signature, body):
    start = text.index(signature)
    brace = text.index('{', start)
    # Tokenize so braces in comments/string literals do not terminate the method.
    token = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]')
    depth = 0
    for m in token.finditer(text, brace):
        if m.group() == '{': depth += 1
        if m.group() == '}':
            depth -= 1
            if depth == 0:
                return text[:brace + 1] + '\n' + body + '\n    ' + text[m.start():]
    raise ValueError(signature)

SOURCES = {
    'muonium-common': [
        'core/MuoniumPlayerExtension.java', 'core/utils/KeyboardUtils.java', 'core/cine/CineDirector.java',
        'core/screens/onboarding/OnboardingOverlay.java', 'core/management/FontManager.java', 'core/screens/hud/GuiHudEditorPro.java',
        'core/ncm/music/CloudMusic.java', 'core/ncm/music/LiveStreamPlayer.java',
        'core/rendering/ui/AbstractWidget.java', 'core/screens/ncm/panels/PlayQueuePanel.java',
        'core/ncm/music/AudioPlayer.java', 'core/ncm/music/SmtcTimelineStabilizer.java', 'core/ncm/music/ExternalArtwork.java', 'core/screens/ncm/IslandSearchOverlay.java',
        'core/screens/ncm/panels/NavigateBar.java', 'core/screens/ncm/PlaylistCreateSheet.java'],
    '26.3': [
        'api/impl/GLStateManagerImpl.java', 'api/impl/OpenAPIImpl.java',
        'compat/gl/GL11.java', 'compat/gl/GL14.java',
        'core/screens/hud/HudSettingsModel.java', 'core/screens/GuiAnnouncements.java',
        'core/screens/GuiUpdateNotice.java', 'core/screens/GuiFfmpegNotice.java',
        'core/rendering/IslandVisibilityGate.java', 'core/rendering/IslandAmbientStatus.java']
}
SOURCES['muonium-common'] += ['core/ncm/music/acoustics/SceneAcoustics.java', 'core/widget/impl/MusicLyricsWidget.java', 'core/rendering/MusicSpectrumRenderer.java', 'core/lyrics3d/WorldLyricsRenderer.java', 'core/MuoniumPlayerEventHandler.java', 'core/settings/ConfigPaths.java', 'core/ncm/music/LyricCacheStore.java',
    'core/ncm/music/MusicAudioCache.java', 'core/screens/ncm/NCMScreen.java']

report = []
latest=verify()
selected=set(latest['java'])
for tree, names in SOURCES.items():
    for name in names:
        rel = Path('com/muoniumplayer') / name
        source_rel=Path(tree)/'src/main/java'/rel
        src = (DELTA if source_rel.as_posix() in selected else REF) / source_rel
        old = src.read_text(encoding='utf-8-sig')
        new = old.replace('net.minecraft.client.Minecraft', CLIENT)
        if name == 'core/cine/CineDirector.java':
            new = cine_schedule_overlay(new)
        elif name == 'core/screens/hud/GuiHudEditorPro.java':
            new = ui_presentation_overlay(new)
            marker='    @Override\n    protected void mouseReleased(int mouseX, int mouseY, int state) {'
            assert new.count(marker)==1
            new=new.replace(marker,'''    @Override
    public void suspendHostInput() {
        draggingPanel=false;
        resizingPanel=false;
        draggingSaturationBrightness=false;
        draggingHue=false;
        draggingSlider=null;
        draggingPopoverScale=false;
        stage.clearSelection();
        super.suspendHostInput();
    }
''' + marker)
        elif name.endswith('PlaylistCreateSheet.java'):
            new=method_body(new,'private void openLocalFolderImport()', '''        if(com.muoniumplayer.bedrock.HostFileDialogs.active())return;
        String initial=LocalMusicLibrary.directories().isEmpty()
                ? new java.io.File("").getAbsolutePath():LocalMusicLibrary.directories().get(0);
        com.muoniumplayer.bedrock.HostFileDialogs.chooseDirectory(initial,picked->{
            if(picked==null||isDismissing()||!picked.isDirectory())return;
            final String directory=picked.getAbsolutePath();
            dispose();
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(()->{
                try {
                    LocalMusicLibrary.addDirectory(directory);
                    var tracks=LocalMusicLibrary.scanDirectory(directory);
                    com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                        .runOnMainThread(()->finishLocalFolderImport(directory,tracks));
                }catch(Throwable failure) {
                    com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil
                        .runOnMainThread(()->DownloadDynamicIsland.showModelOperationResult("本地音乐","导入失败",false));
                }
            });
        });''')
        if name.endswith('NavigateBar.java'):
            before='quickActionsSurface.addChild(btnTheme, btnFont, btnPlayerSize, btnRefresh, btnAnnouncement);'
            assert new.count(before)==1
            new=new.replace('btnAnnouncement.setUnreadBadge(() -> AnnouncementChecker.hasUnread());','// Bedrock player tools have no remote announcement feed.')
            new=new.replace('return "查看公告";', 'return "播放器工具 / Player tools";')
            new=method_body(new,'private static int railSlotCount(', '        return 5;')
            new=method_body(new,'private static boolean railFitsAnnouncement(', '        return true;')
        if name.endswith('IslandSearchOverlay.java'):
            new = new.replace('private IslandSearchOverlay() {', 'public boolean transparentHost(){return true;}\n    private IslandSearchOverlay() {')
            new=new.replace('private IslandSearchOverlay() {',
                'private boolean hostClosing;\n    public boolean hostBackClosing(){return hostClosing;}\n    private IslandSearchOverlay() {')
            new=new.replace('private void closeAndRestore() {','private void closeAndRestore() {\n        hostClosing=true;')
            new=new.replace('api.displayScreen(overlay);','overlay.hostClosing=false;\n        api.displayScreen(overlay);')
            # Both original GLStateManager and Skija now share one canvas. The
            # original extra Skija correction would apply the layout scale twice.
            assert new.count('SkijaGfx.scale(skijaExtraX, skijaExtraY);')==1
            new = new.replace('SkijaGfx.scale(skijaExtraX, skijaExtraY);','// Bedrock: GLStateManager applies the sole Skija layout correction below.')
            before='this.resultsPanel = new ScrollPanel();'
            assert new.count(before)==1
            new=new.replace(before,before+'\n        this.resultsPanel.setBeforeRenderCallback(()->this.resultsPanel.setMargin(0));')
        if name.endswith('HudSettingsModel.java'):
            new = new.replace('游戏里按 Ctrl+F 直接搜歌，按键可以在键位设置里改。','从 ModMenuBE Configure 打开，或在设置中自定义快捷键；默认无绑定。')
        if name == 'core/ncm/music/acoustics/SceneAcoustics.java':
            # A real stereo-side fixture proves the upstream fallback leaves
            # S audible even when the fixed source's direct path is muted.
            # Preserve the listener/headset branch and all original DSP logic.
            before = 'final double width = 0.35 + 0.65 * Math.min(1.0, directGain * 1.4);'
            assert new.count(before) == 1, 'Pinned source-side attenuation boundary changed'
            new = new.replace(before, 'final double width = (0.35 + 0.65 * Math.min(1.0, directGain * 1.4)) * (plan.hasSpeakerAnchor ? directGain : 1.0);')
        if name == 'core/ncm/music/SmtcTimelineStabilizer.java':
            # The upstream controls advertise -1=automatic; preserve that choice
            # instead of clamping it permanently to a manual zero/500ms value.
            new=method_body(new,'public static void setUserDelayMs(int value)', '''        userDelayMs=value<0?null:(long)Math.min(2000,value);
        com.muoniumplayer.core.settings.HudConfig.smtcTimelineDelayMs=userDelayMs==null?-1:userDelayMs.intValue();
        com.muoniumplayer.core.settings.HudConfig.save();''')
            new=method_body(new,'public static void setUserWindowMs(int value)', '''        userWindowMs=value<0?null:(long)Math.max(500,Math.min(30000,value));
        com.muoniumplayer.core.settings.HudConfig.smtcTimelineWindowMs=userWindowMs==null?-1:userWindowMs.intValue();
        com.muoniumplayer.core.settings.HudConfig.save();''')
        if name == 'core/ncm/music/ExternalArtwork.java':
            new=new.replace('new File(ConfigPaths.SMTC_DIR, file)',
                'new File(com.muoniumplayer.bedrock.SessionMedia.directory(), file)')
        if name == 'core/screens/ncm/panels/PlayQueuePanel.java':
            # A hidden child does not run its before-render callback. Visibility
            # must be owned by the parent, which still renders an empty drawer.
            flow = '''flowSort.setHidden(this.roomMode
                    || (!com.muoniumplayer.core.ncm.music.QueueFlowSorter.isRunning()
                    && (this.rows.size() < 2
                    || !com.muoniumplayer.core.ncm.music.AutomixSettings.isAdvancedModeEnabled())));'''
            clear = 'clearAll.setHidden(this.rows.isEmpty() || (this.roomMode && !this.room.canClear()));'
            assert new.count(flow)==new.count(clear)==1
            new=new.replace(flow,'').replace(clear,'')
            new=new.replace('private boolean open;', '''private final RoundedRectWidget flowSort = new RoundedRectWidget();
    private final RoundedRectWidget clearAll = new RoundedRectWidget();
    private boolean open;''')
            new=new.replace('RoundedRectWidget flowSort = new RoundedRectWidget();\n        this.drawer', 'RoundedRectWidget flowSort = this.flowSort;\n        this.drawer')
            new=new.replace('RoundedRectWidget clearAll = new RoundedRectWidget();\n        this.drawer', 'RoundedRectWidget clearAll = this.clearAll;\n        this.drawer')
            assert new.count('        this.syncRows();')==1
            new=new.replace('        this.syncRows();', '        this.syncRows();\n        '+flow+'\n        '+clear)
            new=new.replace('        this.open = open;', '''        this.open = open;
        System.out.println("QUEUE_UI action=" + (open ? "open" : "close") + " pending=" + CloudMusic.getQueuedNextSongs().size());''')
        elif name == 'core/ncm/music/AudioPlayer.java':
            assert new.count('VoicechatBroadcast.ensureInstalled();')==1
            new=new.replace('VoicechatBroadcast.ensureInstalled();','// Bedrock voice broadcast is deferred.')
            new=new.replace('    public File getSourceFile() {', '''    public File bedrockSourceToken() { return this.sourceFile; }
    public File getSourceFile() {''')
            new=new.replace('    public File getSourceFile() {', '''    public boolean bedrockCompleteForMix(boolean enabled) {
        return com.muoniumplayer.bedrock.ProgressiveAudio.completeForAnalysis(this.sourceFile,enabled,AutomixSettings.maxDeckBytes());
    }
    public File getSourceFile() {''')
            new=new.replace('    public void play() {', '''    private volatile boolean bedrockBufferHeld;
    public void play() {
        if (bedrockBufferHeld && isUsable()) { this.playRequested = true; return; }''')
            new=new.replace('if (this.playRequested) {\n                this.player.jump',
                'if (this.playRequested && !bedrockBufferHeld) {\n                this.player.jump')
            new=new.replace('        this.sourceFile = file;', '        this.sourceFile = file;\n        this.bedrockBufferHeld = false;')
            new=new.replace('        return this.sourceFile;', '''        return com.muoniumplayer.bedrock.ProgressiveAudio.analysisFile(this.sourceFile);''')
            new=new.replace('    public float getTotalTimeMillis() {', '''    public boolean bedrockIsBuffering() { return bedrockBufferHeld; }
    public int bedrockProgressiveTick() {
        int state = com.muoniumplayer.bedrock.ProgressiveAudio.state(this.sourceFile, getCurrentTimeMillis());
        if (state == 1 && !bedrockBufferHeld && this.playRequested && isUsable()) {
            this.player.pause(); bedrockBufferHeld = true;
            System.out.println("PROGRESSIVE_AUDIO buffering");
        } else if (state == 0 && bedrockBufferHeld) {
            bedrockBufferHeld = false;
            if (this.playRequested && isUsable()) playFrom(getCurrentTimeMillis());
            System.out.println("PROGRESSIVE_AUDIO resumed");
        }
        return state;
    }
    public float getTotalTimeMillis() {
        float progressiveDuration = com.muoniumplayer.bedrock.ProgressiveAudio.durationMillis(this.sourceFile);
        if (progressiveDuration > 0f) return progressiveDuration;''')
        elif name == 'core/rendering/ui/AbstractWidget.java':
            # GLFW delivers input before drawing. Last rendered hover can refer
            # to a different cursor position; keep child-first/clip semantics.
            before='child.isHovering() && child.isClickable() && child.onMouseClicked'
            assert new.count(before)==1
            new=new.replace(before,'child.testHovered(mouseX, mouseY) && child.isClickable() && child.onMouseClicked')
            before='if (!this.isHidden() && this.isHovering()) {'
            assert new.count(before)==1
            new=new.replace(before,'if (!this.isHidden() && this.testHovered(mouseX, mouseY)) {')
        elif name == 'core/ncm/music/CloudMusic.java':
            before='''        if (currentlyPlaying == null || activePlayer == null || activePlayer.isFinished()) {'''
            assert new.count(before)==1
            new=new.replace(before,'''        // Explicit Play after failure/end is a new request for this track.
        // Keep room/mirror routing above, and never auto-skip a failed song.
        Thread stoppedOwner = playThread;
        boolean retryable = awaitingPlaybackAction || (stoppedOwner != null
                && stoppedOwner.getState() == Thread.State.TERMINATED
                && (activePlayer == null || activePlayer.isFinished() || !activePlayer.isUsable()));
        if (retryable && currentlyPlaying != null && playList != null && !playList.isEmpty()) {
            int retryIndex = curIdx;
            if (retryIndex < 0 || retryIndex >= playList.size()) retryIndex = playList.indexOf(currentlyPlaying);
            if (retryIndex >= 0 && retryIndex < playList.size()) {
                System.out.println("PLAYBACK_RETRY reason="+(awaitingPlaybackAction?"failure":"finished")+" action=same-track");
                discardResumeOffset();
                stopExistingPlayThread();
                curIdx = retryIndex;
                dontAdd = false;
                startNewPlayThread(playList, retryIndex);
                return PlayPauseResult.RESUMED;
            }
        }
'''+before)
            # The drawer is the actual upcoming playback list. Keep the
            # explicit-priority ledger for repeat/FM policy, not as a filter
            # hiding ordinary playlist tracks from the user's queue controls.
            new=method_body(new,'public static int getQueuedNextCount()', '''        synchronized (PLAY_NEXT_LOCK) { return pendingRunLocked(); }''')
            new=method_body(new,'public static List<Music> getQueuedNextSongs()', '''        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            return queue == null || current < 0 || current >= queue.size() ? Collections.emptyList()
                    : new ArrayList<>(queue.subList(current + 1, queue.size()));
        }''')
            new=method_body(new,'private static int pendingRunLocked()', '''        List<Music> queue = playList;
        int current = curIdx;
        return queue == null || current < 0 || current >= queue.size() ? 0 : queue.size() - current - 1;''')
            before='                int insertAt = playNextLedger.insertIndex(queue, curIdx);'
            assert new.count(before)==1
            new=new.replace(before,'''                // Requeue one source identity, including an existing playlist
                // entry. Never remove the current/previously played entries.
                for (int i = queue.size() - 1; i > curIdx; i--) {
                    Music existing = queue.get(i);
                    if (existing != null && (song.equals(existing)
                            || existing.isCrossSourcePeerOf(song) || song.isCrossSourcePeerOf(existing))) {
                        queue.remove(i);
                        playNextLedger.forget(existing);
                    }
                }
                int insertAt = playNextLedger.insertIndex(queue, curIdx);''')
            new=method_body(new,'public static boolean removeQueuedNext(int offset)', '''        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            if (queue == null || current < 0 || offset < 0 || offset >= queue.size() - current - 1) return false;
            Music removed = queue.remove(current + 1 + offset);
            // Legacy sessions may already contain duplicates. Removing one
            // occurrence must not erase the remaining occurrence's priority.
            boolean remains = false;
            for (int i = current + 1; i < queue.size(); i++) {
                if (java.util.Objects.equals(removed, queue.get(i))) { remains = true; break; }
            }
            if (!remains) playNextLedger.forget(removed);
            queueRevision++;
            System.out.println("QUEUE_UI action=remove pending=" + pendingRunLocked());
            return true;
        }''')
            new=method_body(new,'public static int clearQueuedNextSongs()', '''        synchronized (PLAY_NEXT_LOCK) {
            List<Music> queue = playList;
            int current = curIdx;
            int count = queue == null || current < 0 || current >= queue.size() ? 0 : queue.size() - current - 1;
            if (count > 0) queue.subList(current + 1, queue.size()).clear();
            playNextLedger.clear();
            if (count > 0) queueRevision++;
            System.out.println("QUEUE_UI action=clear removed=" + count);
            return count;
        }''')
            new=new.replace('        DownloadDynamicIsland.showQueuedNext(song.getName(), rank);', '''        System.out.println("QUEUE_UI action=add pending="+getQueuedNextSongs().size());
        DownloadDynamicIsland.showQueuedNext(song.getName(), rank);''')
            before = '            if (!isSessionUsable(targetSession)) return false;\n\n            AudioPlayer preparedPlayer;'
            assert new.count(before)==1
            new=new.replace(before, before.replace('if (!isSessionUsable(targetSession)) return false;',
                'if (!isSessionUsable(targetSession)) { com.muoniumplayer.bedrock.ProgressiveAudio.discard(musicFile); return false; }'))
            before = 'preparedPlayer = initializePlayer(musicFile, song);\n            } catch (Throwable e) {\n                if (!isPlaybackCancelled() && isSessionUsable(targetSession)) {'
            assert new.count(before)==1
            new=new.replace(before, before.replace('} catch (Throwable e) {','} catch (Throwable e) {\n                com.muoniumplayer.bedrock.ProgressiveAudio.discard(musicFile);'))
            before='''                AudioPlayer activePlayer = ownedPlayer;
                if (activePlayer == null) break;'''
            assert new.count(before)==1
            new=new.replace(before,before+'''
                bedrockDropStaleUpcoming();
                int progressiveState=activePlayer.bedrockProgressiveTick();
                if(progressiveState == 3) {
                    String failure=com.muoniumplayer.bedrock.ProgressiveAudio.failureKind(activePlayer.bedrockSourceToken());
                    System.out.println("PLAYBACK_END reason=stream-failure action=await-user positionMs="
                        +(long)activePlayer.getCurrentTimeMillis()+" durationMs="+(long)activePlayer.getTotalTimeMillis()+" failure="+failure);
                    synchronized(PLAYER_STATE_LOCK) {
                        if(ownedPlayer==activePlayer)activePlayer.close();
                    }
                    // A failed stream must reach run()'s failure handler, never
                    // handlePlaybackCompletion/updateCurrentIndex.
                    throw new IOException("Audio stream interrupted ("+failure+")");
                }
                if(armed != null && armed.deck != null && armed.deck.bedrockProgressiveTick()==3) cancelArmedTrack();
                if(fadingOutPlayer != null) fadingOutPlayer.bedrockProgressiveTick();''')
            before='        private void waitForPlaybackCompletion() {'
            assert new.count(before)==1
            new=new.replace(before,'        private void waitForPlaybackCompletion() throws IOException {')
            before='''        private void handlePlaybackCompletion() {
            AudioPlayer completedPlayer = ownedPlayer;
            if (completedPlayer == null || isPlaybackCancelled()) return;'''
            assert new.count(before)==1
            new=new.replace(before,before+'''
            System.out.println("PLAYBACK_END reason=completion action=advance positionMs="
                +(long)completedPlayer.getCurrentTimeMillis()+" durationMs="+(long)completedPlayer.getTotalTimeMillis());''')
            before='''            System.out.println("[Automix] handover -> "'''
            assert new.count(before)==1
            new=new.replace(before,'''            System.out.println("PLAYBACK_HANDOVER reason="+(overlapOverrideMillis>0L?"requested":"automatic-mix")
                +" positionMs="+(long)outgoing.getCurrentTimeMillis()+" durationMs="+(long)outgoing.getTotalTimeMillis()
                +" fireMs="+plan.fireMillis+" overlapMs="+effectiveOverlap);
'''+before)
            # EOF must be checked after the original handover attempts.
            new=new.replace('                if (!playing.get()) break;',
                '                if (progressiveState == 2 || !playing.get()) break;')
            # Central admission also covers retried streams. Upcoming ownership
            # is tied to the exact queue revision and playback generation.
            new=new.replace('        private File getMusicFile(Tuple<String, String> playUrl, Music song) {','''
        private volatile File bedrockUpcoming;
        private Music bedrockUpcomingSong;
        private long bedrockUpcomingGeneration, bedrockUpcomingRevision;
        private synchronized void bedrockClearUpcoming() {
            com.muoniumplayer.bedrock.ProgressiveAudio.discard(bedrockUpcoming);
            bedrockUpcoming=null;bedrockUpcomingSong=null;
        }
        private synchronized void bedrockDropStaleUpcoming() {
            if(bedrockUpcoming!=null && (!com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled() || bedrockUpcomingRevision!=queueRevision
                || bedrockUpcomingGeneration!=generation.get() || isPlaybackCancelled())) bedrockClearUpcoming();
        }
        private synchronized File bedrockTakeUpcoming(Music song) {
            if(bedrockUpcoming==null)return null;
            boolean same=song!=null && bedrockUpcomingSong!=null
                && song.getStableKey().equals(bedrockUpcomingSong.getStableKey());
            long current=generation.get();
            boolean valid=com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled() && bedrockUpcomingRevision==queueRevision && !isPlaybackCancelled()
                && (current==bedrockUpcomingGeneration || (current==bedrockUpcomingGeneration+1 && currentlyPlaying==song));
            if(!same||!valid){bedrockClearUpcoming();return null;}
            File result=bedrockUpcoming;bedrockUpcoming=null;bedrockUpcomingSong=null;return result;
        }
        private File bedrockPrepareAudio(Tuple<String,String> url,Music song,java.util.function.BooleanSupplier cancelled) {
            return bedrockPrepareAudio(url,song,cancelled,false);
        }
        private String bedrockCacheKey(Tuple<String,String> url,Music song) {
            return song==null?null:audioCacheKey(song,quality.getQuality())+"|"+AudioContainerSupport.normalizeReportedContainer(url.getB());
        }
        private File bedrockPrepareAudio(Tuple<String,String> url,Music song,java.util.function.BooleanSupplier cancelled,boolean ahead) {
            if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
            File ready=bedrockTakeUpcoming(song);if(ready!=null)return ready;
            String key=bedrockCacheKey(url,song);
            if(song!=null)com.muoniumplayer.bedrock.PlaybackFiles.global().rememberLabel(key,song.getName(),song.getArtistsName());
            File retained=com.muoniumplayer.bedrock.PlaybackFiles.global().restore(key,com.muoniumplayer.bedrock.SessionMedia.directory().toPath());
            if(retained!=null&&retained.length()>PlaybackMemoryLimits.maxDecodedPcmBytes()){retained.delete();retained=null;}
            if(cancelled.getAsBoolean()){if(retained!=null)retained.delete();throw new java.util.concurrent.CancellationException();}
            if(retained!=null)return retained;
            File progressive=bedrockStream(url,song,cancelled,ahead);
            if(cancelled.getAsBoolean()){com.muoniumplayer.bedrock.ProgressiveAudio.discard(progressive);throw new java.util.concurrent.CancellationException();}
            if(progressive!=null)return progressive;
            File complete=bedrockWholeAudio(url,song);
            if(!cancelled.getAsBoolean())com.muoniumplayer.bedrock.PlaybackFiles.global().retain(key,complete);
            return complete;
        }
        private File bedrockStream(Tuple<String,String> url,Music song,java.util.function.BooleanSupplier cancelled,boolean ahead) {
            return url!=null && song!=null && song.isNetease()
                && ("mp3".equalsIgnoreCase(url.getB()) || "flac".equalsIgnoreCase(url.getB()))
                ? com.muoniumplayer.bedrock.ProgressiveAudio.prepare(url.getA(),cancelled,bedrockCacheKey(url,song),ahead):null;
        }
        private File getMusicFile(Tuple<String, String> playUrl, Music song) {
            return bedrockPrepareAudio(playUrl,song,()->isPlaybackCancelled());
        }
        private File bedrockWholeAudio(Tuple<String, String> playUrl, Music song) {''')
            new=method_body(new,'private void prefetchTrackAudio(Music song, long expectedGeneration, long expectedRevision)','''
            File file=null;
            try {
                if(!prefetchStillValid(expectedGeneration,expectedRevision))return;
                Tuple<String,String> url=song.getPlayUrl();
                if(url==null||!prefetchStillValid(expectedGeneration,expectedRevision))return;
                file=bedrockPrepareAudio(url,song,()->!prefetchStillValid(expectedGeneration,expectedRevision),true);
                if(file==null)return;
                synchronized(this){
                    if(!prefetchStillValid(expectedGeneration,expectedRevision))return;
                    bedrockClearUpcoming();bedrockUpcoming=file;bedrockUpcomingSong=song;
                    bedrockUpcomingGeneration=expectedGeneration;bedrockUpcomingRevision=expectedRevision;file=null;
                }
                System.out.println("PROGRESSIVE_AUDIO upcoming=ready retention=one-prefix");
            }catch(Throwable failure){System.out.println("PROGRESSIVE_AUDIO upcoming=cancelled-or-unavailable");}
            finally{com.muoniumplayer.bedrock.ProgressiveAudio.discard(file);prefetchingAudio=false;}
''')
            new=new.replace('if (!worthAudioPrefetch(song)) return;', 'if (song==null || (!song.isNetease() && !worthAudioPrefetch(song))) return;')
            new=new.replace('if (armed != null || arming) return;', 'if (armed != null || arming || prefetchingAudio || activeFade != null) return;')
            new=new.replace('if (prefetchingAudio || arming || armed != null) return;', 'if (prefetchingAudio || arming || armed != null || bedrockUpcoming != null || activeFade != null) return;')
            policy='com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled()'
            new=new.replace('private boolean prefetchStillValid(long expectedGeneration, long expectedRevision) {',
                'private boolean prefetchStillValid(long expectedGeneration, long expectedRevision) {\n            if(!'+policy+')return false;')
            new=new.replace('private void maybePrefetchNextTrackAudio(AudioPlayer activePlayer) {',
                'private void maybePrefetchNextTrackAudio(AudioPlayer activePlayer) {\n            if(!'+policy+')return;')
            new=new.replace('private void maybeArmNextTrack(AudioPlayer activePlayer) {',
                'private void maybeArmNextTrack(AudioPlayer activePlayer) {\n            if(!'+policy+') { if(activeFade==null)cancelArmedTrack();bedrockClearUpcoming();return; }')
            new=new.replace('public static boolean prefetchRoomTrackAudio(String playUrl, String reportedType, Music song) {',
                'public static boolean prefetchRoomTrackAudio(String playUrl, String reportedType, Music song) {\n        if(!'+policy+')return false;')
            new=new.replace('        private void cancelPlayback() {','        private void cancelPlayback() {\n            bedrockMixReset();\n            bedrockClearUpcoming();')
            new=new.replace('                cancelManualSelection();\n                cancelArmedTrack();\n                finishAutomixFade();',
                '                bedrockClearUpcoming();\n                cancelManualSelection();\n                cancelArmedTrack();\n                finishAutomixFade();')
            # Publish a deck or discard its token on every cancellation path.
            new=new.replace('''        private void armTrack(Music song, int index, long armGeneration, long armRevision) {
            AudioPlayer deck = null;''', '''        private void armTrack(Music song, int index, long armGeneration, long armRevision) {
            File bedrockOwnedToken=null;
            AudioPlayer deck = null;''')
            begin=new.index('private void armTrack(');end=new.index('private int armTrialSwitches;',begin)
            chunk=new[begin:end].replace('File file = getMusicFile(playUrl, song);',
                'File file = bedrockPrepareAudio(playUrl,song,()->!armStillValid(armGeneration,armRevision) || !com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled(),true); bedrockOwnedToken=file;')
            chunk=chunk.replace('if (!armStillValid(armGeneration, armRevision))',
                'if (!com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled() || !armStillValid(armGeneration, armRevision))')
            chunk=chunk.replace('                deck = new AudioPlayer(file);',
                '                deck = new AudioPlayer(file); bedrockOwnedToken=null;')
            chunk=chunk.replace('boolean incomingEnvelope = warmAdvancedEnvelopeNow(file, decodedMillis);',
                'boolean incomingEnvelope = deck.getSourceFile()!=null && warmAdvancedEnvelopeNow(deck.getSourceFile(), decodedMillis);')
            chunk=chunk.replace('track.plan = AutomixPlanner.plan(ownedPlayer, deck, lastVocalEnd);',
                'track.plan = com.muoniumplayer.core.ncm.music.StreamingMixPlanner.plan(ownedPlayer, deck, lastVocalEnd);')
            chunk=chunk.replace('                arming = false;', '                com.muoniumplayer.bedrock.ProgressiveAudio.discard(bedrockOwnedToken);\n                arming = false;')
            new=new[:begin]+chunk+new[end:]
            begin=new.index('private void prepareManualSelection(');end=new.index('        private ',begin+20)
            chunk=new[begin:end].replace('AudioPlayer deck = null;', 'File bedrockOwnedToken=null;\n            AudioPlayer deck = null;')
            chunk=chunk.replace('File file = getMusicFile(url, song);', 'File file = bedrockPrepareAudio(url,song,()->!manualSelectionCurrent(selection)); bedrockOwnedToken=file;')
            chunk=chunk.replace('deck = new AudioPlayer(file);','deck = new AudioPlayer(file); bedrockOwnedToken=null;')
            chunk=chunk.replace('            } finally {','            } finally {\n                com.muoniumplayer.bedrock.ProgressiveAudio.discard(bedrockOwnedToken);')
            new=new[:begin]+chunk+new[end:]
            new=new.replace('boolean paused = active != null && active.isUsable() && !active.isFinished() && active.isPausing();',
                'boolean paused = active != null && active.isUsable() && !active.isFinished() && (active.isPausing() || active.bedrockIsBuffering());')
            new=new.replace('AutomixPlan plan = AutomixPlanner.plan(outgoing, track.deck, lastVocalEnd);',
                'AutomixPlan plan = StreamingMixPlanner.plan(outgoing, track.deck, lastVocalEnd);')
            new=new.replace('AutomixEnvelope.isPresent(track.file)', 'AutomixEnvelope.isPresent(track.deck.getSourceFile())')
            new=new.replace('loadLyric(track.song, nextSession, track.file);',
                'loadLyric(track.song, nextSession, com.muoniumplayer.bedrock.ProgressiveAudio.analysisFile(track.file));')
            new=new.replace('''long overlapOverrideMillis, ManualSelection selection) {
            armed = null;''','''long overlapOverrideMillis, ManualSelection selection) {
            if(track.deck!=null && track.deck.bedrockProgressiveTick()!=0) return false;
            armed = null;''')
            new=new.replace('loadLyric(song, targetSession, musicFile);',
                'loadLyric(song, targetSession, com.muoniumplayer.bedrock.ProgressiveAudio.isProgressive(musicFile) ? null : musicFile);')
            new=streaming_mix_overlay(new)
        elif name == 'core/ncm/music/LiveStreamPlayer.java':
            new = new.replace('    private volatile Thread decodeThread;', '''    private volatile Thread decodeThread;
    private volatile InputStream activeInput;
    private volatile SourceDataLine activeLine;''')
            new = new.replace('        paused.set(false);\n        Thread thread = this.decodeThread;', '''        paused.set(false);
        try { if(activeInput!=null) activeInput.close(); } catch(IOException ignored) { }
        SourceDataLine line=activeLine;
        if(line!=null){ try{line.stop();line.flush();line.close();}catch(Exception ignored){} }
        Thread thread = this.decodeThread;''')
            new = method_body(new, 'private static InputStream openStream(String url)',
                '        return com.muoniumplayer.bedrock.LiveRadioTransport.open(url);')
            new = method_body(new, 'private void runLoop()', '''        boolean announced=false;int failures=0;
        try {
            while(!closed.get()) {
                try(InputStream http=openStream(streamUrl)) {
                    activeInput=http;
                    try(var frames=new com.muoniumplayer.bedrock.RadioFrames(http)) {
                        while(!closed.get()) {
                            while(paused.get()&&!closed.get())Thread.sleep(25);
                            if(closed.get())break;
                            var frame=frames.next();
                            if(frame==null)throw new IOException("Radio stream ended");
                            SourceDataLine line=activeLine;
                            if(line==null||!line.getFormat().matches(frame.format())) {
                                if(line!=null)line.close();
                                activeLine=line=openLine(frame.format());
                            }
                            refreshVolumeOnLine(line);
                            byte[] pcm=frame.pcm();int offset=0;
                            while(offset<pcm.length&&!closed.get()) {
                                while(paused.get()&&!closed.get())Thread.sleep(25);
                                if(closed.get())break;
                                int n=line.write(pcm,offset,pcm.length-offset);
                                if(n>0)offset+=n;else Thread.sleep(10);
                            }
                            if(closed.get())break;
                            decodedFrameCount.incrementAndGet();playing.set(true);failures=0;
                            if(!announced){announced=true;listener.onStarted(this);
                                System.out.println("LIVE_RADIO started transport=streaming output=JavaSound");}
                        }
                    } finally {activeInput=null;SourceDataLine line=activeLine;activeLine=null;
                        if(line!=null){try{line.stop();line.flush();line.close();}catch(Exception ignored){}}}
                }catch(IOException|LineUnavailableException e) {
                    if(closed.get())break;
                    String reason=rootMessage(e);
                    if(reason.contains("Unsupported")||reason.contains("Encrypted")||++failures>=MAX_CONSECUTIVE_FAILURES){
                        fail("直播无法播放（格式不支持或连接中断）");
                        System.out.println("LIVE_RADIO failed reason="+e.getClass().getSimpleName());return;}
                    Thread.sleep(1000);
                }
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Throwable e){if(!closed.get()){fail("直播播放失败，请重试或选择其他频道");
            System.out.println("LIVE_RADIO failed reason="+e.getClass().getSimpleName());}}
        finally{playing.set(false);closed.set(true);}
''')
        elif name == 'core/widget/impl/MusicLyricsWidget.java':
            before='private static int followCoverColor(int fallbackArgb, boolean foreground) {'
            assert new.count(before)==1, 'Pinned classic desktop color boundary changed'
            new=new.replace(before,before+'\n        if(com.muoniumplayer.bedrock.HudElements.customSubtitleColor()) return com.muoniumplayer.bedrock.HudElements.subtitleColor(fallbackArgb);')
            before='lyricColor.getValue().getRGB(), currentLyricColor.getValue().getRGB(),'
            assert new.count(before)==1, 'Pinned Blossom desktop color boundary changed'
            new=new.replace(before,'com.muoniumplayer.bedrock.HudElements.subtitleColor(lyricColor.getValue().getRGB()), com.muoniumplayer.bedrock.HudElements.subtitleColor(currentLyricColor.getValue().getRGB()),')
        if name == 'core/rendering/MusicSpectrumRenderer.java':
            before='if(!HudConfig.spectrumEnabled || !HudConfig.spectrumInHud || failed) return;'
            assert new.count(before)==1, 'Pinned HUD spectrum guard changed'
            new=new.replace(before,'if(!com.muoniumplayer.bedrock.HudElements.spectrumAllowed(HudConfig.spectrumEnabled,HudConfig.spectrumInHud) || failed) return;')
        if name == 'core/lyrics3d/WorldLyricsRenderer.java':
            # Upstream buildGlyphs currently hardcodes white for Cadenza tokens.
            # Honor the renderer snapshot's explicit ink while keeping its
            # active-token brightening, timing, geometry and default palette.
            before = '''rgb = g.tokenActive
                                ? CadenzaMotion.brighten(g.tokenRgb & 0xFFFFFF, CadenzaMotion.ACTIVE_BRIGHTEN)
                                : g.tokenRgb & 0xFFFFFF;'''
            assert new.count(before) == 1, 'Pinned Cadenza color boundary changed'
            new = new.replace(before, '''int tokenInk = settings.colorCustom ? inkRgb & 0xFFFFFF : g.tokenRgb & 0xFFFFFF;
                        rgb = g.tokenActive
                                ? CadenzaMotion.brighten(tokenInk, CadenzaMotion.ACTIVE_BRIGHTEN)
                                : tokenInk;''')
        if name == 'core/MuoniumPlayerEventHandler.java':
            new = new.replace('Interpolations.calcFrameDelta();', '// The owned SkijaHost already advances this clock once per frame.')
            assert new.count('MusicSpectrumRenderer.renderHud();')==1 and new.count('DownloadDynamicIsland.render();')==1
            new = new.replace('MusicSpectrumRenderer.renderHud();','com.muoniumplayer.bedrock.HudElements.renderSpectrum(MusicSpectrumRenderer::renderHud);')
            new = new.replace('DownloadDynamicIsland.render();','com.muoniumplayer.bedrock.HudElements.renderIsland(DownloadDynamicIsland::render);')
        if name.endswith('PlayQueuePanel.java'):
            marker='    private void cancelDrag() {'
            assert new.count(marker)==1
            new=new.replace(marker,'''    public void suspendHostInput() {
        // Focus loss is cancellation, never a release/room reorder commit.
        this.cancelDrag();
    }

''' + marker)
        if name.endswith('ConfigPaths.java'):
            new = new.replace('new File(DATA_ROOT, "cache")', 'com.muoniumplayer.bedrock.SessionMedia.directory()')
            # Keep credential/config migrations; never migrate durable cache into
            # the new ephemeral session (old data gets a separate explicit cleaner).
            new = new.replace('migrateDirectory(new File("MusicCache"), MUSIC_CACHE);', '')
            new = new.replace('migrateDirectory(new File(configDir, "cache"), cacheDir);', '')
        elif name.endswith('NCMScreen.java'):
            marker='    @Override\n    public void onGuiClosed() {'
            assert new.count(marker)==1
            new=new.replace(marker,'''    @Override
    public boolean hostBackClosing(){return closing;}
    @Override
    public void suspendHostInput() {
        // Drop the deferred touch click before any release is dispatched.
        this.pendingClickActive=false;
        if(this.playQueuePanel!=null)this.playQueuePanel.suspendHostInput();
        super.suspendHostInput();
    }
''' + marker)
            new=method_body(new,'public void openAnnouncements()', '        com.muoniumplayer.bedrock.BedrockClient.getInstance().gui.setScreen(new com.muoniumplayer.bedrock.HostToolsScreen());')
            before='public void onGuiClosed() {'
            assert new.count(before)==1
            new=new.replace(before,before+'\n        com.muoniumplayer.bedrock.HostFileDialogs.cancel();')
            discarded = '''        // 提示层不跨「一次打开播放器」存活：下次打开时由 maybeShowModelNotice 重新决定要不要弹，
        // 而「本次启动已弹过」的闸门在 FoliaModelNotice 里，所以这里清掉不会导致重复提示。
        this.modelInstallNoticeOverlay = null;'''
            assert new.count(discarded) == 1, 'Pinned NCMScreen notice-close boundary changed'
            new = new.replace(discarded, '''        // A once-shown notice must survive an interrupted player visit.
        // Later/Escape still dismisses it through the original animation path.
        // Keeping this instance also retains ongoing install progress/results.''')
        elif name.endswith('MusicAudioCache.java'):
            new = new.replace('PROTECTED_RECENT_GROUPS = 6', 'PROTECTED_RECENT_GROUPS = 2')
            # Keep current/next original file-based decoders; remove every older
            # prepared track instead of retaining a library until a GB quota.
            new = method_body(new, 'private static long configuredLimitBytes()', '        return 1L;')
        elif name.endswith('LyricCacheStore.java'):
            new = new.replace('private static final int FORMAT_VERSION = 1;', '''private static final int FORMAT_VERSION = 1;
    private static final java.util.LinkedHashMap<String, String> MEMORY = new java.util.LinkedHashMap<>(32, .75f, true);''')
            new = method_body(new, 'static void store(String stableKey, List<LyricLine> lines)', '''        if (stableKey == null || lines == null || lines.isEmpty()) return;
        String value = serialize(stableKey, lines).toString();
        if (value.length() > 1_000_000) return;
        synchronized (MEMORY) {
            MEMORY.put(stableKey, value);
            while (MEMORY.size() > 16) MEMORY.remove(MEMORY.keySet().iterator().next());
        }''')
            new = method_body(new, 'static List<LyricLine> load(String stableKey)', '''        String value;
        synchronized (MEMORY) { value = MEMORY.get(stableKey); }
        if (value == null) return Collections.emptyList();
        try { return deserialize(JsonParser.parseString(value).getAsJsonObject()); }
        catch (RuntimeException e) { invalidate(stableKey); return Collections.emptyList(); }''')
            new = method_body(new, 'static void invalidate(String stableKey)', '        synchronized (MEMORY) { MEMORY.remove(stableKey); }')
        elif name.endswith('KeyboardUtils.java'):
            # Same threading/clipboard contract; the host owns the GLFW window.
            new = new.replace('import ' + CLIENT + ';', 'import ' + CLIENT + ';\nimport ' + CLIENT + '.KeyboardHandler;')
            new = re.sub(r'\bMinecraft\b', 'BedrockClient', new)
        elif name.endswith('MuoniumPlayerExtension.java'):
            new = new.replace('MultiThreadingUtil.runAsync(LxSourceService::init);',
                              'com.muoniumplayer.bedrock.HostPolicy.initializeSources();')
            # Background companion/compiler download is an optional upstream side
            # effect, not required to initialise the original player or widgets.
            new = new.replace('com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload.ensureDownload();',
                              'com.muoniumplayer.bedrock.HostPolicy.optionalCompilerDownload();')
            new = new.replace('QishuiBackendLifecycle.ensureStartedForGame();',
                              'com.muoniumplayer.bedrock.HostPolicy.optionalQishuiStart();')
            new = new.replace('MultiThreadingUtil.runAsync(CloudMusic::initNCM);',
                              'com.muoniumplayer.bedrock.HostPolicy.initializeMusic();')
        elif name.endswith('FontManager.java'):
            new = new.replace('FontAssetDownload.ensureFonts();',
                              'com.muoniumplayer.bedrock.HostPolicy.optionalFontDownload();')
        elif name.endswith('GLStateManagerImpl.java') or name.startswith('compat/gl/'):
            calls = {
                '_deleteTexture': 'org.lwjgl.opengl.GL11.glDeleteTextures',
                '_genTexture': 'org.lwjgl.opengl.GL11.glGenTextures',
                '_texParameter': 'org.lwjgl.opengl.GL11.glTexParameteri',
                '_pixelStore': 'org.lwjgl.opengl.GL11.glPixelStorei',
                '_blendFuncSeparate': 'org.lwjgl.opengl.GL14.glBlendFuncSeparate',
            }
            for method, target in calls.items():
                new = new.replace('com.mojang.renderpearl.backend.opengl.GlStateManager.' + method, target)
            new = new.replace('com.mojang.renderpearl.backend.opengl.GlStateManager._bindTexture(texture)',
                              'org.lwjgl.opengl.GL11.glBindTexture(target, texture)')
        elif name.endswith('OpenAPIImpl.java'):
            new = new.replace('import net.minecraft.network.chat.Component;', '')
            new = re.sub(r'\bMinecraft\b', 'BedrockClient', new)
            new = new.replace('BedrockClient.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal(message));',
                              'BedrockClient.getInstance().printMessage(message);')
        elif name.endswith('HudSettingsModel.java'):
            new=new.replace('import com.muoniumplayer.core.ncm.music.VoicechatBroadcast;','')
            start=new.index('new Group("设置界面", HudSettingsIcons.MODERN_UI,')
            end=new.index('new Group("外部播放器", HudSettingsIcons.NOTE,',start)
            new=new[:start]+'''new Group("设置界面", HudSettingsIcons.MODERN_UI,
                                choice("设置界面样式", "保留旧版浮动面板，另有新版居中设置中心。选择即保存，下次打开编辑界面生效，不重启游戏、不停止歌曲。新版为 Bedrock 适配界面，并非运行 Java 版 MuoniumConfig。",
                                        () -> com.muoniumplayer.bedrock.UiPresentation.global().layoutLabel(),
                                        () -> com.muoniumplayer.bedrock.UiPresentation.global().nextLayout(),
                                        com.muoniumplayer.bedrock.UiPresentation.layouts(),
                                        value -> com.muoniumplayer.bedrock.UiPresentation.global().selectLayout(value)),
                                choice("设置界面动画", "原版 / 新版分层 / 减少动态。与界面样式独立，下次打开编辑界面生效；原有播放器动画速度设置继续保留。",
                                        () -> com.muoniumplayer.bedrock.UiPresentation.global().motionLabel(),
                                        () -> com.muoniumplayer.bedrock.UiPresentation.global().nextMotion(),
                                        com.muoniumplayer.bedrock.UiPresentation.motions(),
                                        value -> com.muoniumplayer.bedrock.UiPresentation.global().selectMotion(value))),
                        '''+new[end:]
            before='new Group("外部播放器", HudSettingsIcons.NOTE,'
            assert new.count(before)==1
            new=new.replace(before,before+'''
                                slider(HudSetting.SMTC_DELAY,"外部播放器时间轴的补偿；仅系统镜像生效。未调整前保留原作自动校准。",ExternalPlayback::enabled),
                                slider(HudSetting.SMTC_WINDOW,"外部播放器暂停上报时允许外推的窗口；仅系统镜像生效。",ExternalPlayback::enabled),
                                action("恢复自动时间轴校准", "清除手动延迟和外推窗口，按外部播放器类型自动处理；不改变音量、队列或歌词设置。",
                                        () -> {
                                            com.muoniumplayer.core.ncm.music.SmtcTimelineStabilizer.setUserDelayMs(-1);
                                            com.muoniumplayer.core.ncm.music.SmtcTimelineStabilizer.setUserWindowMs(-1);
                                        }),''')
            anchor='new Group("位置与形态", HudSettingsIcons.MOVE,'
            assert new.count(anchor)==1
            new=new.replace(anchor,anchor+'''
                                choice("3D 字幕帧率", "60 / 120 / 144 / 165 / 240 / 无上限。默认无上限，跟随可用游戏相机更新；仅限制被动 3D 歌词（同一浮层的 HUD 共用提交），不限制播放器操作页面。",
                                        () -> com.muoniumplayer.bedrock.LyricRefresh.global().label(),
                                        () -> com.muoniumplayer.bedrock.LyricRefresh.global().next(),
                                        com.muoniumplayer.bedrock.LyricRefresh.labels(),
                                        value -> com.muoniumplayer.bedrock.LyricRefresh.global().select(value)),''')
            start=new.index('new Group("语音广播",')
            end=new.index('new Group("基本设置",',start)
            new=new[:start]+new[end:]
            # Only the scoped native camera adapter is implemented. Keep its
            # original controls; model spin/squash and mob tracking stay hidden.
            start=new.index('new Category("fun",')
            end=new.index('new Category("界面与主题",',start)
            cine=new[new.index('new Group("卡点运镜",',start):end]
            mob=cine.index('toggle("追踪生物",')
            cine=cine[:mob]+cine[cine.index('toggle("鼓点变焦",',mob):]
            cine=cine.replace('环绕玩家 / 风景 / 移动生物','环绕玩家 / 风景')
            cine=cine.replace('CineSettings.setIdleSeconds(minutes > 0 ? minutes * 60 : 10);',
                'CineSettings.setIdleSeconds(minutes > 0 ? minutes * 60 : Integer.parseInt(v.replace("秒", "").trim()));')
            new=new[:start]+'new Category("cine", "运镜", "仅本机画面的闲置自动镜头；任何输入恢复玩家视角", 0xFFB58CFF, HudSettingsIcons.SPEED,\n                        '+cine+new[end:]
            new=new.replace('net.minecraft.client.gui.screens.Screen','com.muoniumplayer.compat.mc.GuiScreen')
            new=re.sub(r'\bMinecraft\b','BedrockClient',new)
            start = new.index('new Group("音频缓存",')
            end = new.index('HudSettingsModel::openAnnouncements)))', start)+len('HudSettingsModel::openAnnouncements)))')
            new = new[:start] + '''new Group("临时播放文件", HudSettingsIcons.SWEEP,
                                toggle("预先下载下一首", "默认关闭。后台下载下一首；智能混音开启时也会在大小限制内补完当前歌曲供分析。关闭后不再自动预备，自动无缝混音也需开启此项。",
                                        () -> com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled(),
                                        () -> com.muoniumplayer.bedrock.PlaybackFiles.global().setPrefetch(!com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled())),
                                toggle("保留音乐缓存", "默认关闭。只保留完整曲目供下次重用，默认上限 1 GiB；位置与容量可自定义，关闭后停止读写。",
                                        () -> com.muoniumplayer.bedrock.PlaybackFiles.global().retainEnabled(),
                                        () -> com.muoniumplayer.bedrock.PlaybackFiles.global().setRetain(!com.muoniumplayer.bedrock.PlaybackFiles.global().retainEnabled())),
                                action("清理旧曲目的临时文件",
                                        "临时播放文件退出后清理；不会清理主动保留的音乐缓存。歌词仍只放内存。",
                                        () -> MusicAudioCache.enforceLimitAsync()),
                                action("查看与自定义音乐缓存", "查看曲目与占用、单曲删除、自选存储文件夹及精确容量。更换位置保留原处文件。",
                                        () -> com.muoniumplayer.bedrock.CacheManagerScreen.open()),
                                action("清理保留的音乐缓存", "只清理本播放器保留的音乐，不删除账号、歌单、模型或正在播放的临时文件。",
                                        () -> com.muoniumplayer.core.utils.other.multithreading.BackgroundWork.submit(() -> com.muoniumplayer.bedrock.PlaybackFiles.global().clearRetained()))))''' + new[end:]
            new = new.replace('缓存与公告', '临时文件').replace('音乐缓存占多大盘、公告入口', '清理不再使用的临时播放文件')
            # Keep explicit operations at the end of both owned settings layouts.
            # Passive ModMenu sidecars cannot express callbacks; do not fake action fields.
            anchor='        });\n    }\n\n    /**\n     * 标签栏的分类顺序。'
            assert new.count(anchor)==1
            new=new.replace(anchor,'''                ,new Category("host-tools", "打开界面", "仅点击后打开；不会自动播放、登录或下载", 0xFF6FA8FF, HudSettingsIcons.MOVE,
                        new Group("播放器工具", HudSettingsIcons.MOVE,
                                action("打开播放器", "返回歌曲与歌单页面，保留当前播放。",
                                        com.muoniumplayer.bedrock.HostToolsScreen::openPlayer),
                                action("HUD 编辑", "打开 HUD 编辑，可拖动和缩放各个界面。",
                                        com.muoniumplayer.bedrock.HostToolsScreen::openHudEditor),
                                action("预设管理", "打开模板预设管理；返回时回到此设置页。",
                                        com.muoniumplayer.bedrock.HostToolsScreen::openPresets),
                                action("迷你播放器", "打开迷你播放控制，不改变 HUD 开关。",
                                        com.muoniumplayer.bedrock.HostToolsScreen::openMiniPlayer),
                                action("岛内搜索", "打开搜索框；输入并提交后才搜索。需先启用岛内搜索。",
                                        com.muoniumplayer.bedrock.HostToolsScreen::openIslandSearch)))
'''+anchor)
        elif name.startswith('core/screens/Gui'):
            new = re.sub(r'\bMinecraft\b', 'BedrockClient', new)
            new = new.replace('import net.minecraft.client.gui.screens.TitleScreen;', '')
            new = new.replace('net.minecraft.client.gui.screens.Screen', 'GuiScreen')
            new = new.replace('current instanceof TitleScreen', 'mc.atMainMenu()')
            new = new.replace('mc.level != null && mc.player != null', 'mc.scene().inWorld()')
            if name.endswith('GuiAnnouncements.java'):
                new=method_body(new,'public static void open(', '        // Original publisher announcements are disabled for this port.')
                new=method_body(new,'public static void tickAutoShow(', '        // A Bedrock-owned feed can be added separately later.')
        elif name.endswith('IslandVisibilityGate.java'):
            new = method_body(new, 'static Scene capture()', '''        var client = com.muoniumplayer.bedrock.BedrockClient.getInstance();
        var state = client.scene();
        return new Scene(state.inWorld(), state.playerListVisible(),
                client.gui.screen() instanceof com.muoniumplayer.core.screens.ncm.NCMScreen,
                state.anyScreenOpen() || client.gui.screen() != null, state.hudHidden());''')
        elif name.endswith('IslandAmbientStatus.java'):
            new = method_body(new, 'static boolean sampleMoving(long now)', '''        var state = com.muoniumplayer.bedrock.BedrockClient.getInstance().scene();
        if (!state.inWorld()) { lastMovingAt = 0L; return false; }
        if (state.moving()) lastMovingAt = now;
        return lastMovingAt > 0L && now - lastMovingAt <= MOVING_HOLD_MILLIS;''')
            new = method_body(new, 'private static Snapshot captureStatus(long now, boolean moving)', '''        var state = com.muoniumplayer.bedrock.BedrockClient.getInstance().scene();
        Snapshot status = describe(HudConfig.dynamicIslandAmbientFps,
                HudConfig.dynamicIslandAmbientPing, HudConfig.dynamicIslandAmbientClock,
                state.fps(), state.ping(), state.ping() >= 0, now);
        return status.isEmpty() ? EMPTY : new Snapshot(status.chips, now, Page.STATUS, moving);''')
        dst = OUT / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(new, encoding='utf-8')
        report.append({'source': src.relative_to(ROOT).as_posix(),
                       'output': dst.relative_to(ROOT).as_posix(),
                       'original_sha256': hashlib.sha256(src.read_bytes()).hexdigest(),
                       'adapted_sha256': hashlib.sha256(dst.read_bytes()).hexdigest()})
# Other selected 2.5 classes retain their complete original implementation.
# Minecraft symbols in version-local UI are routed to the owned host only.
written={v['source'] for v in report}
for source_rel in latest['java']:
    src=DELTA/source_rel
    if src.relative_to(ROOT).as_posix() in written:continue
    rel=source_rel.split('/src/main/java/',1)[1]
    old=src.read_text(encoding='utf-8-sig')
    new=old.replace('net.minecraft.client.Minecraft',CLIENT)
    new=re.sub(r'\bMinecraft\b','BedrockClient',new)
    if rel.endswith('/GuiPresetSettings.java'):
        marker='public final class GuiPresetSettings extends GuiScreen {'
        assert new.count(marker)==1
        new=new.replace(marker,marker+'\n    public boolean hostBackClosing(){return !panel.isOpen();}')
    if rel.endswith('/MusicCapsuleCard.java'):
        # Keep the pinned card/click geometry, but use actual outline bounds.
        # Text advances were unscaled, queue used the wrong font, and mode icons
        # depended on asynchronous prewarming with no list-loop asset.
        new=method_body(new,'private void drawTransportGlyph(', '''        com.muoniumplayer.bedrock.CapsuleIcons.transport(glyph,centerX,centerY,size,color);''')
        new=method_body(new,'private void drawModeGlyph(', '''        com.muoniumplayer.bedrock.CapsuleIcons.mode(x,y,size,box,alpha);''')
    if rel.endswith('/HudSetting.java'):
        before='SPIN_BASE_SPEED("陀螺模式 过渡转速", 0.0f, 360.0f);'
        assert new.count(before)==1
        new=new.replace(before,before[:-1]+',\n    SMTC_DELAY("系统镜像 延迟补偿(ms)", -1f, 2000f),\n    SMTC_WINDOW("系统镜像 外推窗口(ms)", -1f, 30000f);')
        new=new.replace('switch (this) {', 'switch (this) {', 1)
        a=new.index('public float getValue()');b=new.index('public void setValue(',a)
        part=new[a:b].replace('switch (this) {','switch (this) {\n            case SMTC_DELAY: return HudConfig.smtcTimelineDelayMs;\n            case SMTC_WINDOW: return HudConfig.smtcTimelineWindowMs;')
        new=new[:a]+part+new[b:]
        a=new.index('public void setValue(')
        new=new[:a]+new[a:].replace('switch (this) {','switch (this) {\n            case SMTC_DELAY: com.muoniumplayer.core.ncm.music.SmtcTimelineStabilizer.setUserDelayMs(Math.round(clamped)); break;\n            case SMTC_WINDOW: com.muoniumplayer.core.ncm.music.SmtcTimelineStabilizer.setUserWindowMs(Math.round(clamped)); break;',1)
    if rel.endswith('/HudConfig.java'):
        # Original save keeps all non-host settings; bridge only mapped edits.
        new=new.replace('JsonConfigStorage.writeObject(FILE, GSON, toJson());',
            'JsonConfigStorage.writeObject(FILE, GSON, toJson());\n            com.muoniumplayer.bedrock.HostHudSync.persist();')
    if rel.endswith('/SmtcPublisher.java'):
        # Host JSON and explicit UI edits own publication. Never start an async
        # legacy config read that could race and override a newer host switch.
        new=method_body(new,'private static void ensureConfigAsync()',
            '        synchronized(CONFIG_LOCK){configLoaded=true;}')
        new=new.replace('private static volatile boolean enabled = true;', 'private static volatile boolean enabled = false;')
        new=new.replace('enabled = value;\n        ensureConfigAsync();',
            'synchronized(CONFIG_LOCK){configLoaded=true;enabled=value;}\n        com.muoniumplayer.bedrock.HostHudSync.persist();')
        # Publication must stay off even when the same helper is alive for mirroring.
        new=new.replace('ExternalMediaService.setPublishDesired(enabled);',
            'ExternalMediaService.setPublishDesired(enabled);\n            if(!enabled){collapse();return;}')
        new=new.replace('public static void shutdown() {', 'public static void shutdown() {\n        COMMANDS.clear();collapsed=true;pushedTrackKey="";pushedState="";pushedArtPath="";')
        new=new.replace('private SmtcPublisher() {', """public static void configureHost(boolean value) {
        synchronized(CONFIG_LOCK){configLoaded=true;enabled=value;}
        if(!value)shutdown();
    }
    private SmtcPublisher() {""")
        new=new.replace('new File(ConfigPaths.SMTC_DIR, name)','new File(com.muoniumplayer.bedrock.SessionMedia.directory(), name)')
        new=new.replace('new File(ConfigPaths.SMTC_DIR, "muonium-icon.png")','new File(com.muoniumplayer.bedrock.SessionMedia.directory(), "muonium-icon.png")')
        new=new.replace('ConfigPaths.SMTC_DIR.listFiles(','com.muoniumplayer.bedrock.SessionMedia.directory().listFiles(')
        new=new.replace('ART_PATHS.put(key, target.getAbsolutePath());',
            'ART_PATHS.put(key, target.getAbsolutePath());\n            ART_PATHS.entrySet().removeIf(e -> !new File(e.getValue()).isFile());')
        new=new.replace('String state = snap.playing ? "Playing" : "Paused";',
            '''String readyArt=coverPathFor(track);
            if(!java.util.Objects.equals(readyArt,pushedArtPath)) {
                pushedArtPath=readyArt;
                ExternalMediaService.sendPublishArt(readyArt==null?"":readyArt);
            }
            String state = snap.playing ? "Playing" : "Paused";''')
        new=new.replace('System.out.println("[Music/SMTC] 发布封面落盘失败: " + failure);',
            'System.out.println("[Music/SMTC] cover publication unavailable reason="+failure.getClass().getSimpleName());')
    if rel.endswith('/ExternalMediaService.java'):
        before='builder.directory(executable.getParentFile());'
        assert new.count(before)==1
        new=new.replace(before,before+'\n            builder.environment().put("MUONIUM_SMTC_ART_DIR", com.muoniumplayer.bedrock.SessionMedia.directory().getAbsolutePath());')
    if rel.endswith('/UpdateChecker.java'):
        # Bedrock packages its own editor; never download a Fabric/NeoForge mod.
        before='com.muoniumplayer.core.screens.hud.MuoniumConfigDownload.ensureDownloaded(mcVersion, loaderId);'
        assert new.count(before)==1
        new=new.replace(before,'// Bedrock settings are included in the companion.')
    if rel.endswith('/MusicCardOverlay.java'):
        new=new.replace('private MusicCardOverlay() {','public static MusicCardOverlay hostScreen(){return INSTANCE;}\n    public boolean transparentHost(){return true;}\n\n    private MusicCardOverlay() {')
    if rel.endswith('/PresetService.java'):
        new=new.replace('new PresetEngine(PresetDomains.all())','new PresetEngine(PresetDomains.all().stream().filter(d -> !java.util.Set.of("spin","transparent").contains(d.id())).toList())')
    dst=OUT/rel;dst.parent.mkdir(parents=True,exist_ok=True)
    dst.write_text(new,encoding='utf-8')
    report.append({'source':src.relative_to(ROOT).as_posix(),'output':dst.relative_to(ROOT).as_posix(),
        'original_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),
        'adapted_sha256':hashlib.sha256(dst.read_bytes()).hexdigest()})
# Original direct room server; an offline fixture may bind only to loopback.
# Normal hosting stays explicit in the original UI and listens on LAN interfaces.
src=REF/'mpparty-common/src/main/java/com/muoniumplayer/party/direct/DirectServer.java'
new=src.read_text(encoding='utf-8-sig').replace('new InetSocketAddress("0.0.0.0", port)',
    'new InetSocketAddress(Boolean.getBoolean("muonium.bedrock.roomLoopbackOnly") ? "127.0.0.1" : "0.0.0.0", port)')
dst=OUT/'com/muoniumplayer/party/direct/DirectServer.java';dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(new,encoding='utf-8')
report.append({'source':src.relative_to(ROOT).as_posix(),'output':dst.relative_to(ROOT).as_posix(),
    'original_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'adapted_sha256':hashlib.sha256(dst.read_bytes()).hexdigest()})
# Keep the original party widgets and engine; only add explicit host transport.
for relative in ['direct/MpDirect.java','ui/MpPartyOverlay.java']:
    src=REF/'mpparty-common/src/main/java/com/muoniumplayer/party'/relative
    old=src.read_text(encoding='utf-8-sig');new=old
    if relative=='direct/MpDirect.java':
        signature='public static void close() {'
        assert new.count(signature)==1
        new=new.replace(signature,signature+'\n        com.muoniumplayer.bedrock.RoomTunnel.close();')
        signature='if (!wasMember && !wasLoop) return false;'
        assert new.count(signature)==1
        new=new.replace(signature,signature+'\n        com.muoniumplayer.bedrock.RoomTunnel.close();')
    else:
        new=new.replace('hosting ? 286.0 : 300.0','hosting ? 366.0 : 380.0')
        new=new.replace('"地址:端口#密码（好友发你的整串）", 160','"公网邀请或地址:端口#密码", 512')
        new=new.replace('com.muoniumplayer.party.direct.MpDirect.joinRoom(value, err -> {','com.muoniumplayer.bedrock.RoomTunnel.joinRoom(value, err -> {')
        anchor='''        // ── 托管态 ──────────────────────────────────────────────────────────'''
        assert new.count(anchor)==1
        new=new.replace(anchor,'''        if(!joined) {
            LabelWidget tunnelStatus=label(dialog,com.muoniumplayer.bedrock.RoomTunnel::status,FontManager.pf10bold);
            tunnelStatus.setBeforeRenderCallback(()->tunnelStatus.setColor(secondary()).setPosition(20,hosting?238:276));
            btn(dialog,()->PartyIcons.ADD,com.muoniumplayer.bedrock.RoomTunnel::buttonLabel,null,()->{
                if(com.muoniumplayer.bedrock.RoomTunnel.busy())com.muoniumplayer.bedrock.RoomTunnel.cancel();
                else if(com.muoniumplayer.bedrock.RoomTunnel.ready()) {
                    KeyboardUtils.setClipboardString(com.muoniumplayer.bedrock.RoomTunnel.invitation());
                    hint="已复制公网邀请 · 好友需使用新版播放器";hintColor=OK_COLOR;
                }else {
                    String error=com.muoniumplayer.bedrock.RoomTunnel.hostRoom();
                    if(error!=null){hint=error;hintColor=ERROR_COLOR;}else if(!hosting)showDirectPage();
                }
            },STYLE_PRIMARY,b->{b.setBounds(230,28);b.setPosition(20,hosting?200:238);});
        }
''' + anchor)
    dst=OUT/'com/muoniumplayer/party'/relative;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(new,encoding='utf-8')
    report.append({'source':src.relative_to(ROOT).as_posix(),'output':dst.relative_to(ROOT).as_posix(),
        'original_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),'adapted_sha256':hashlib.sha256(dst.read_bytes()).hexdigest()})
OUT.mkdir(parents=True, exist_ok=True)
# SampleLoader is upstream code too: preserve its full source and make this
# narrow adapter as auditable as the com.muoniumplayer overlays above.
src = REF / 'muonium-common/src/main/java/repackage/com/jsyn/util/SampleLoader.java'
old = src.read_text(encoding='utf-8-sig')
signature='public static FloatSample loadStreamedFloatSample(File fileIn) {'
assert old.count(signature)==1
new=old.replace(signature,signature+'''
        FloatSample progressive = com.muoniumplayer.bedrock.ProgressiveAudio.claim(fileIn);
        if (progressive != null) return progressive;''')
dst=OUT/'repackage/com/jsyn/util/SampleLoader.java'
dst.parent.mkdir(parents=True,exist_ok=True)
dst.write_text(new,encoding='utf-8')
report.append({'source':src.relative_to(ROOT).as_posix(),'output':dst.relative_to(ROOT).as_posix(),
    'original_sha256':hashlib.sha256(src.read_bytes()).hexdigest(),
    'adapted_sha256':hashlib.sha256(dst.read_bytes()).hexdigest()})
# Reuse the upstream GLFW key mapping from the version that actually uses GLFW;
# 26.3 is SDL. Keep the same LWJGL2 persistent key namespace used by all widgets.
src = REF / '1.21.1/src/main/java/com/muoniumplayer/compat/lwjgl2/Keyboard.java'
old = src.read_text(encoding='utf-8-sig')
new = old.replace('net.minecraft.client.Minecraft', CLIENT)
new = new.replace('import com.mojang.blaze3d.platform.InputConstants;', '')
new = re.sub(r'\bMinecraft\b', 'BedrockClient', new)
new = new.replace('InputConstants.isKeyDown(handle, glfw)',
                  'com.muoniumplayer.bedrock.HostInputState.allowed() && GLFW.glfwGetKey(handle, glfw) == GLFW.GLFW_PRESS')
dst = OUT / 'com/muoniumplayer/compat/lwjgl2/Keyboard.java'
dst.parent.mkdir(parents=True, exist_ok=True)
dst.write_text(new, encoding='utf-8')
report.append({'source': src.relative_to(ROOT).as_posix(), 'output': dst.relative_to(ROOT).as_posix(),
               'original_sha256': hashlib.sha256(src.read_bytes()).hexdigest(),
               'adapted_sha256': hashlib.sha256(dst.read_bytes()).hexdigest()})
(OUT / 'adaptation-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(f'Prepared {len(report)} platform overlays; reference sources unchanged.')
