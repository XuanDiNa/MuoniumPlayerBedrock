package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.rendering.MusicCapsuleCard;
import com.muoniumplayer.core.ncm.music.CloudMusic;
import com.muoniumplayer.core.management.FontManager;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.types.RRect;
import com.muoniumplayer.render.skija.SkijaHost;

/** Explicit transparent input page using the original capsule renderer and controls.
 * Independent of passive HUD enable/visibility and never changes those settings. */
public final class HostMiniPlayer extends GuiScreen {
    private float x,y,scale=1;
    private boolean seeking;
    public boolean transparentHost(){return true;}
    public void drawScreen(int mouseX,int mouseY,float partial) {
        scale=(float)Math.max(.35,Math.min(1.5,Math.min((width-24f)/MusicCapsuleCard.CARD_WIDTH,(height-24f)/MusicCapsuleCard.CARD_HEIGHT)));
        x=(float)(width-MusicCapsuleCard.CARD_WIDTH*scale)/2;y=Math.max(12,height*.15f);
        y=(float)Math.min(y,height-MusicCapsuleCard.CARD_HEIGHT*scale-12);
        var canvas=SkijaHost.canvas();
        try(var paint=new Paint().setColor(0xF0202330).setAntiAlias(true)) {
            canvas.drawRRect(RRect.makeXYWH(x,y,(float)MusicCapsuleCard.CARD_WIDTH*scale,(float)MusicCapsuleCard.CARD_HEIGHT*scale,14*scale),paint);
        }
        MusicCapsuleCard.tick(16.67f);
        MusicCapsuleCard.INSTANCE.drawCard(x,y,scale,1,false);
        if(CloudMusic.getSnapshot().music==null)FontManager.pf18.drawString("Play a song to use the mini player",x+16,y+32,0xFFFFFFFF);
        if(seeking&&Mouse.isButtonDown(0))seek(mouseX);
        else seeking=false;
    }
    protected void mouseClicked(int mx,int my,int button) {
        if(button!=0)return;
        double px=(mx-x)/scale,py=(my-y)/scale;
        if(px<0||py<0||px>MusicCapsuleCard.CARD_WIDTH||py>MusicCapsuleCard.CARD_HEIGHT){closeScreen();return;}
        seeking=Math.abs(py-MusicCapsuleCard.PROGRESS_Y)<=10&&px>=MusicCapsuleCard.PROGRESS_X&&px<=MusicCapsuleCard.CARD_WIDTH-MusicCapsuleCard.PROGRESS_X;
        MusicCapsuleCard.INSTANCE.click(px,py);
    }
    private void seek(int mx){MusicCapsuleCard.INSTANCE.seekToFraction(((mx-x)/scale-MusicCapsuleCard.PROGRESS_X)/(MusicCapsuleCard.CARD_WIDTH-2*MusicCapsuleCard.PROGRESS_X));}
    protected void mouseReleased(int x,int y,int button){if(button==0)seeking=false;}
    public void suspendHostInput(){seeking=false;super.suspendHostInput();}
    public void onGuiClosed(){seeking=false;}
}
