package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.lyrics3d.WorldLyricsRenderer;
import com.muoniumplayer.core.lyrics3d.WorldLyricsState;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.Rect;

/** Presentation fallback only: both modes read the original playback/lyric
 * clock. A renderer failure must not turn into a fake synchronized timeline. */
public final class LyricOverlay implements AutoCloseable {
    private final StageLyrics stage = new StageLyrics();
    private boolean stageFailed;
    private int mode=-1;
    private final WorldFrame.Session worldSession=new WorldFrame.Session();
    void composition(NativeComposition value){stage.composition(value);}
    public long drawnGlyphs(){return stage.drawnGlyphs();}
    public void updateEnvironment(EnvironmentFrame environment){stage.environment(environment);}
    void updateDepth(DepthFrame depth,long now){stage.depth(depth,now);}
    int[] depthCrop(){return stage.depthCrop();}
    long depthApplied(){return stage.depthApplied();}
    long depthPartial(){return stage.depthPartial();}
    long depthReprojected(){return stage.depthReprojected();}
    public void retryStage() { stageFailed = false; WorldLyricsRenderer.clearBrokenLatch(); }
    public void render(HostSettings settings, long nowNanos) {
        render(settings,nowNanos,null);
    }
    public void updateWorld(WorldFrame frame) {
        if(worldSession.update(frame)) {
            resetAnchors();
            System.out.println(frame==null?"WORLD_FRAME unavailable; anchors cleared":"WORLD_FRAME ready epoch="+frame.epoch()+" dimension="+frame.dimension());
        }
    }
    private void resetAnchors() {
        com.muoniumplayer.core.lyrics3d.BedrockWorldLifecycle.reset();stageFailed=false;
    }
    public void render(HostSettings settings, long nowNanos,WorldFrame frame) {
        if(mode!=settings.lyricsMode()){mode=settings.lyricsMode();resetAnchors();}
        if (!settings.lyricsEnabled() || !SkijaHost.inFrame()) return;
        if(mode==2&&frame==null)return;
        if (mode!=0 && !stageFailed) {
            var canvas = SkijaHost.canvas(); int save = canvas.save();
            try { stage.configure(settings);stage.render(nowNanos,mode==2?frame:null); return; }
            catch (RuntimeException failure) {
                stageFailed = true;
                canvas.clear(0);
                System.err.println("LYRICS renderer unavailable; change settings to retry");
            } finally { canvas.restoreToCount(save); }
        }
        if(mode==2)return;
        renderFlat(settings);
    }
    private static void renderFlat(HostSettings settings) {
        if (!WorldLyricsState.hasLyrics()) return;
        var snapshot = CloudMusic.getSnapshot();
        if (snapshot.music == null) return;
        var line = CloudMusic.findCurrentLyric(snapshot.positionMs);
        if (line == null || line.isBreakLine || line.lyric.isBlank()) return;
        String text = line.lyric.replace('\n', ' ');
        var font = FontManager.pf32;
        if (font == null || font.getFontHeight() <= 0) return;
        float textWidth = (float) font.getStringWidthD(text);
        if (textWidth <= 0) return;
        float scale = Math.min(settings.lyricsFontSize() / (float) font.getFontHeight(), SkijaHost.guiWidth() * .9f / textWidth);
        float width = textWidth * scale;
        float height = (float) font.getFontHeight() * scale;
        float x = (SkijaHost.guiWidth() - width) / 2;
        float y = Math.max(12, SkijaHost.guiHeight() - settings.lyricsOffsetY() - height);
        var canvas = SkijaHost.canvas(); canvas.save();
        try (Paint backdrop = new Paint().setColor(0x90000000)) {
            canvas.drawRect(Rect.makeXYWH(x - 12, y - 7, width + 24, height + 14), backdrop);
            canvas.translate(x, y); canvas.scale(scale, scale);
            font.drawStringWithShadow(text, 0, 0, settings.lyricColorCustom()?settings.lyricArgb():0xFFFFFFFF);
        } finally { canvas.restore(); }
    }
    @Override public void close() { stage.close(); }
}
