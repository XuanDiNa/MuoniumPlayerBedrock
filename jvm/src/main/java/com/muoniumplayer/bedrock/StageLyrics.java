package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.lyrics3d.*;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.render.skija.SkijaHost;
import io.github.humbleui.skija.*;
import io.github.humbleui.types.Rect;
import java.util.HashMap;
import java.util.Map;

/** Client-only stage using the original lyric renderer, owned world-camera
 * values and optional depth. No server or world writes. */
public final class StageLyrics implements WorldLyricsRenderer.Host, LyricGlyphAtlas.Host, AutoCloseable {
    private final WorldLyricsState state = WorldLyricsState.get();
    private final Map<Integer, Image> trailImages = new HashMap<>();
    private WorldLyricsSettings settings;
    private int stackDepth;
    private long drawnGlyphs;
    private long gpuTrailPictures;
    private ColorFilter shadowTint;
    private Paint shadowPaint;
    private WorldFrame world;
    private NativeComposition composition;
    void composition(NativeComposition value){composition=value;}
    private void include(float x,float y,float w,float h){gameDepth.include(x,y,w,h);if(composition!=null)composition.include(x,y,w,h);}
    private EnvironmentFrame environment;
    private final VoxelLyricClip voxelClip=new VoxelLyricClip();
    private final LyricOcclusionTrace occlusionTrace=new LyricOcclusionTrace();
    void environment(EnvironmentFrame frame){environment=frame;}
    private final GameDepthClip gameDepth=new GameDepthClip();
    void depth(DepthFrame frame,long now){gameDepth.update(frame,now);}
    int[] depthCrop(){return gameDepth.crop();}
    long depthApplied(){return gameDepth.applied();}
    long depthPartial(){return gameDepth.partial();}
    long depthReprojected(){return gameDepth.motionApplied();}
    private HostSettings hostSettings=HostSettings.parse("{}");
    void configure(HostSettings settings){hostSettings=settings;}
    private final WorldLyricsState.CameraView stageCamera = new WorldLyricsState.CameraView() {
        public double eyeX() { return 0; } public double eyeY() { return 0; } public double eyeZ() { return 0; }
        public double lookX() { return 0; } public double lookY() { return 0; } public double lookZ() { return 1; }
        public double yawDeg() { return 0; } public double pitchDeg() { return 0; }
        public int windowWidth() { return SkijaHost.guiWidth(); }
        public int windowHeight() { return SkijaHost.guiHeight(); }
    };
    public StageLyrics() { LyricGlyphAtlas.setHost(this); }
    public void render(long nowNanos) {
        render(nowNanos,null);
    }
    public void render(long nowNanos,WorldFrame frame) {
        world=frame;
        occlusionTrace.begin(frame,environment,hostSettings.worldLyricsOcclusion());
        gameDepth.beginFrame();
        settings = WorldLyricsSettings.snapshot();
        HudElements.applyLyricColor(settings,hostSettings);
        if (!settings.enabled || !SkijaHost.inFrame()) return;
        WorldLyricsRenderer.render(state, frame==null?stageCamera:frame, settings, this, nowNanos);
        occlusionTrace.end(nowNanos);
        if (stackDepth != 0) throw new IllegalStateException("Lyric transform leak");
    }
    public long drawnGlyphs() { return drawnGlyphs; }
    long gpuTrailPictures() { return gpuTrailPictures; }
    @Override public boolean supportsGpuTrailCanvas() { return true; }
    @Override public boolean drawGpuTrailCanvas(Picture picture, int w, int h, float x, float y,
                                                float pw, float ph, float alpha, int slot) {
        if (picture == null || w < 1 || h < 1 || !SkijaHost.inFrame()) return false;
        include(x,y,pw,ph);
        var canvas = SkijaHost.canvas();
        int saved=canvas.save();
        try (Paint paint = new Paint().setAlphaf(Math.max(0,Math.min(1,alpha))).setBlendMode(BlendMode.PLUS)) {
            canvas.translate(x,y); canvas.scale(pw/w,ph/h);
            canvas.clipRect(Rect.makeWH(w,h));
            canvas.saveLayer(null,paint);
            canvas.drawPicture(picture); canvas.restore();
            gpuTrailPictures++;
        } finally { canvas.restoreToCount(saved); }
        return true;
    }
    @Override public void pushBillboard(double x, double y, double z, double yaw, double pitch, float scale) {
        if(composition!=null&&world!=null){
            if(composition.begin(x,y,z,yaw,pitch,scale)){stackDepth++;return;}
            // Exhaustion/inactive export must not leak an unoccluded screen-space
            // duplicate into the flat layer.
            SkijaHost.canvas().save();SkijaHost.canvas().clipRect(Rect.makeXYWH(0,0,0,0));stackDepth++;return;
        }
        var matrix = world==null?StageProjection.billboard(SkijaHost.guiWidth(), SkijaHost.guiHeight(), stageCamera.fovDeg(), x, y, z, yaw, pitch, scale)
                :WorldProjection.billboard(world,SkijaHost.guiWidth(),SkijaHost.guiHeight(),x,y,z,yaw,pitch,scale);
        SkijaHost.canvas().save(); stackDepth++;
        if(matrix==null){gameDepth.begin(world,null,x,y,z,yaw,pitch,scale,false);SkijaHost.canvas().clipRect(Rect.makeXYWH(0,0,0,0));}
        else {
            SkijaHost.canvas().concat(StageProjection.skija(matrix));
            gameDepth.begin(world,matrix,x,y,z,yaw,pitch,scale,hostSettings.worldLyricsOcclusion());
            if(!gameDepth.recording()&&world!=null&&hostSettings.worldLyricsOcclusion())clipVoxels(x,y,z,yaw,pitch,scale);
        }
        planeX=x;planeY=y;planeZ=z;planeYaw=yaw;planePitch=pitch;planeScale=scale;
    }
    private double planeX,planeY,planeZ,planeYaw,planePitch;private float planeScale;
    @Override public void popBillboard() {
        if (stackDepth < 1) throw new IllegalStateException("Lyric transform underflow");
        if(composition!=null&&composition.recording()){composition.end();stackDepth--;return;}
        gameDepth.end(()->clipVoxels(planeX,planeY,planeZ,planeYaw,planePitch,planeScale));
        SkijaHost.canvas().restore(); stackDepth--;
    }
    private void clipVoxels(double x,double y,double z,double yaw,double pitch,float scale){
        occlusionTrace.row(voxelClip.apply(SkijaHost.canvas(),environment,world,x,y,z,yaw,pitch,scale));
    }
    @Override public void drawPixelText(String text, float x, float y, int argb, boolean shadow) {
        include(x-2,y-2,pixelWidth(text)+5,(float)FontManager.pf18.getFontHeight()+5);
        if (shadow) FontManager.pf18.drawStringWithShadow(text, x, y, argb);
        else FontManager.pf18.drawString(text, x, y, argb);
    }
    @Override public float pixelWidth(String text) { return (float) FontManager.pf18.getStringWidthD(text); }
    @Override public float pixelCharWidth(char c) { return pixelWidth(String.valueOf(c)); }
    @Override public float smoothCharWidth(char c, float size) {
        float value = LyricGlyphAtlas.designAdvance(c);
        return value > 0 ? value : pixelCharWidth(c);
    }
    @Override public void drawSmoothGlyph(char c, float x, float y, float size, int rgb, float alpha,
                                          float rot, float dx, float dy, float scale,
                                          float glowMul, boolean glowOn, int glowRgb, int inkRgb) {
        var ink = LyricGlyphAtlas.getInkCell(c, inkRgb);
        if (ink == null) {
            // A working atlas can temporarily exhaust its per-frame budget.
            // Match upstream: defer that glyph rather than flash a pixel font.
            if (!LyricGlyphAtlas.isFunctional())
                drawPixelText(String.valueOf(c), x, y, ((int) (255 * alpha) << 24) | rgb & 0xFFFFFF, true);
            return;
        }
        float radius=(Math.max(ink.w,ink.h)/ink.scale+64)*Math.max(1,Math.abs(scale));
        include(x+dx-radius,y+dy-radius,radius*2,radius*2);
        var canvas = SkijaHost.canvas(); canvas.save();
        try {
            float cx = x + ink.advance / ink.scale / 2f, cy = y + ink.inkHeight / ink.scale / 2f;
            canvas.translate(cx + dx, cy + dy); canvas.rotate(rot); canvas.scale(scale, scale); canvas.translate(-cx, -cy);
            drawShadowCell(ink, x + .5f, y + 1f, alpha);
            if (glowOn && settings != null && settings.glowEnabled) {
                var glow = LyricGlyphAtlas.getGlowCell(c, settings.glowStrength * glowMul, settings.glowSpread, glowRgb, rgb, settings.glowTier);
                drawCell(glow, x, y, alpha, BlendMode.PLUS);
            }
            drawCell(ink, x, y, alpha, BlendMode.SRC_OVER); drawnGlyphs++;
        } finally { canvas.restore(); }
    }
    private void drawCell(LyricGlyphAtlas.GlyphCell cell, float x, float y, float alpha, BlendMode mode) {
        if (cell == null) return;
        Page page = (Page) cell.page;
        try (Paint paint = new Paint().setAlphaf(Math.max(0, Math.min(1, alpha))).setBlendMode(mode)) {
            float inv = 1f / cell.scale;
            SkijaHost.canvas().drawImageRect(page.image(), Rect.makeXYWH(cell.x, cell.y, cell.w, cell.h),
                    Rect.makeXYWH(x - cell.inkX * inv, y - cell.inkY * inv, cell.w * inv, cell.h * inv), SamplingMode.LINEAR, paint, true);
        }
    }
    private void drawShadowCell(LyricGlyphAtlas.GlyphCell cell, float x, float y, float alpha) {
        if (alpha <= 0) return;
        if (shadowPaint == null) {
            shadowTint = ColorFilter.makeBlend(0xFF000000, BlendMode.SRC_IN);
            shadowPaint = new Paint().setColorFilter(shadowTint).setBlendMode(BlendMode.SRC_OVER);
        }
        shadowPaint.setAlphaf(Math.max(0, Math.min(1, alpha)) * .22f);
        Page page = (Page) cell.page;
        float inv = 1f / cell.scale;
        SkijaHost.canvas().drawImageRect(page.image(), Rect.makeXYWH(cell.x, cell.y, cell.w, cell.h),
                Rect.makeXYWH(x - cell.inkX * inv, y - cell.inkY * inv, cell.w * inv, cell.h * inv),
                SamplingMode.LINEAR, shadowPaint, true);
    }
    @Override public Object createPage(int width, int height) { return new Page(width, height); }
    @Override public void deletePage(Object raw) { ((Page) raw).close(); }
    @Override public void uploadCell(Object raw, int x, int y, int w, int h, int[] pixels) {
        Page page = (Page) raw;
        try (Image image = image(pixels, w, h); Paint paint = new Paint().setBlendMode(BlendMode.SRC)) {
            page.surface.getCanvas().drawImage(image, x, y, paint); page.invalidate();
        }
    }
    private static Image image(int[] argb, int w, int h) {
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || argb.length != Math.multiplyExact(w, h)) throw new IllegalArgumentException("Lyric pixels");
        byte[] bgra = new byte[Math.multiplyExact(argb.length, 4)];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i], at = i * 4;
            int a = p >>> 24;
            bgra[at] = (byte) (((p & 255) * a + 127) / 255);
            bgra[at + 1] = (byte) ((((p >>> 8) & 255) * a + 127) / 255);
            bgra[at + 2] = (byte) ((((p >>> 16) & 255) * a + 127) / 255);
            bgra[at + 3] = (byte) a;
        }
        return Image.makeRaster(new ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bgra, w * 4L);
    }
    private static final class Page implements AutoCloseable {
        final Surface surface;
        private Image snapshot;
        Page(int w, int h) {
            surface = Surface.makeRenderTarget(SkijaHost.context(), false, ImageInfo.makeN32Premul(w, h));
            if (surface == null) throw new IllegalStateException("Lyric atlas allocation failed");
            surface.getCanvas().clear(0);
        }
        void invalidate() { if (snapshot != null) { snapshot.close(); snapshot = null; } }
        Image image() { if (snapshot == null) snapshot = surface.makeImageSnapshot(); return snapshot; }
        public void close() { invalidate(); surface.close(); }
    }
    @Override public boolean drawTrailCanvas(int[] pixels, int w, int h, float x, float y, float pw, float ph, float alpha, int slot) {
        if (slot < 0 || slot > 32) return false;
        include(x,y,pw,ph);
        Image old = trailImages.remove(slot); if (old != null) old.close();
        Image image = image(pixels, w, h); trailImages.put(slot, image);
        try (Paint paint = new Paint().setAlphaf(alpha).setBlendMode(BlendMode.PLUS)) {
            SkijaHost.canvas().drawImageRect(image, Rect.makeXYWH(x, y, pw, ph), paint);
        }
        return true;
    }
    @Override public void drawTrail(float[] xs, float[] ys, int count, int argb, float width) {
        if (count < 2) return;
        for(int i=0;i<count;i++)include(xs[i]-width,ys[i]-width,width*2,width*2);
        try (PathBuilder path = new PathBuilder(); Paint paint = new Paint().setColor(argb).setAntiAlias(true)
                .setMode(PaintMode.STROKE).setStrokeWidth(width).setStrokeCap(PaintStrokeCap.ROUND).setStrokeJoin(PaintStrokeJoin.ROUND)) {
            path.moveTo(xs[0], ys[0]); for (int i = 1; i < count; i++) path.lineTo(xs[i], ys[i]);
            try (Path shape = path.detach()) { SkijaHost.canvas().drawPath(shape, paint); }
        }
    }
    @Override public void drawHalo(float cx, float cy, float size, int argb) {
        include(cx-size/2,cy-size/2,size,size);
        try (Shader shader = Shader.makeRadialGradient(cx, cy, size / 2, new int[]{argb, argb & 0xFFFFFF});
             Paint paint = new Paint().setShader(shader).setBlendMode(BlendMode.PLUS)) {
            SkijaHost.canvas().drawCircle(cx, cy, size / 2, paint);
        }
    }
    @Override public void drawStar(float cx, float cy, float size, float rotation, int argb) {
        include(cx-size,cy-size,size*2,size*2);
        var canvas = SkijaHost.canvas(); canvas.save(); canvas.translate(cx, cy); canvas.rotate(rotation);
        try (PathBuilder path = new PathBuilder(); Paint paint = new Paint().setColor(argb).setAntiAlias(true).setBlendMode(BlendMode.PLUS)) {
            path.moveTo(0, -size / 2); path.lineTo(size / 10, -size / 10); path.lineTo(size / 2, 0);
            path.lineTo(size / 10, size / 10); path.lineTo(0, size / 2); path.lineTo(-size / 10, size / 10);
            path.lineTo(-size / 2, 0); path.lineTo(-size / 10, -size / 10); path.closePath();
            try (Path shape = path.detach()) { canvas.drawPath(shape, paint); }
        } finally { canvas.restore(); }
    }
    @Override public void drawBurstLayer(float cx, float cy, float w, float h, float rotation, int argb) {
        float radius=(float)Math.hypot(w,h);include(cx-radius,cy-radius,radius*2,radius*2);
        var canvas = SkijaHost.canvas(); canvas.save(); canvas.translate(cx, cy); canvas.rotate(rotation);
        try (Paint paint = new Paint().setColor(argb).setAntiAlias(true).setBlendMode(BlendMode.PLUS)) {
            canvas.drawOval(Rect.makeXYWH(-w / 2, -h / 2, w, h), paint);
        } finally { canvas.restore(); }
    }
    @Override public void drawRing(float cx, float cy, float size, int argb) {
        include(cx-size,cy-size,size*2,size*2);
        try (Paint paint = new Paint().setColor(argb).setAntiAlias(true).setMode(PaintMode.STROKE).setStrokeWidth(Math.max(.2f, size / 30))) {
            SkijaHost.canvas().drawCircle(cx, cy, size / 2, paint);
        }
    }
    @Override public void drawGlowBackdrop(String text, float width, int rgb, float alpha, int slot) {
        if (settings == null || !settings.glowEnabled) return;
        int[] pixels = RowGlowSprite.bake(text, rgb, settings.glowStrength, settings.glowSpread);
        if (pixels == null) return;
        int w = RowGlowSprite.width() * 2, h = RowGlowSprite.height() * 2;
        float pad = RowGlowSprite.padPx(settings.glowStrength, settings.glowSpread);
        drawTrailCanvas(pixels, w, h, -RowGlowSprite.textWidth() / 2 - pad,
                -WorldLyricsState.LINE_H / 2 - pad, RowGlowSprite.textWidth() + pad * 2,
                WorldLyricsState.LINE_H + pad * 2, alpha, slot);
    }
    @Override public void close() {
        if (shadowPaint != null) { shadowPaint.close(); shadowPaint = null; }
        if (shadowTint != null) { shadowTint.close(); shadowTint = null; }
        gameDepth.close();
        voxelClip.clear();
        LyricGlyphAtlas.invalidateAll(); LyricGlyphAtlas.setHost(null);
        for (Image image : trailImages.values()) image.close(); trailImages.clear();
    }
}
