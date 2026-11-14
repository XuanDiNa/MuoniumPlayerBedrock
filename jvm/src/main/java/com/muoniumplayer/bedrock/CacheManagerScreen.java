package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.compat.lwjgl2.Mouse;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.container.*;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.*;
import com.muoniumplayer.core.utils.other.multithreading.BackgroundWork;
import com.muoniumplayer.party.ui.PartyIcons;
import com.muoniumplayer.render.skija.SkijaGfx;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Cache controls use the original player's sheet, input and scrolling widgets. */
public final class CacheManagerScreen extends GuiScreen {
    private final GuiScreen parent;
    final CachePanel panel;
    private boolean built,closed;
    public CacheManagerScreen(GuiScreen parent,PlaybackFiles policy){this.parent=parent;panel=new CachePanel(policy);}
    public static void open(){var client=BedrockClient.getInstance();client.gui.setScreen(new CacheManagerScreen(client.gui.screen(),PlaybackFiles.global()));}
    @Override public void initGui(){Keyboard.enableRepeatEvents(true);if(!built){built=true;panel.onInit();}}
    @Override public void onGuiClosed(){closed=true;panel.dispose();Keyboard.enableRepeatEvents(false);}
    @Override public void onClose(){if(!closed)mc.gui.setScreen(parent);}
    @Override public void updateScreen(){if(!closed)panel.onTickReceived();}
    private void scaled(Runnable action){RenderSystem.refreshResolution();WindowAdaptiveScale.beginForced();try{action.run();}finally{WindowAdaptiveScale.end();}}
    @Override public void drawScreen(int x,int y,float partial){if(closed||!SkijaGfx.active())return;scaled(()->{
        double sx=WindowAdaptiveScale.correctionX(),sy=WindowAdaptiveScale.correctionY();SkijaGfx.pushMatrix();SkijaGfx.scale(sx,sy);
        try{panel.setBounds(0,0,RenderSystem.getWidth(),RenderSystem.getHeight());panel.renderWidget(x/sx,y/sy,0);
            int wheel=Mouse.getDWheel();if(wheel!=0)panel.onDWheelReceived(x/sx,y/sy,wheel);
            if(panel.shouldClose())onClose();
        }finally{SkijaGfx.popMatrix();}
    });}
    @Override protected void mouseClicked(int x,int y,int button){if(!closed)scaled(()->panel.onMouseClickReceived(x/WindowAdaptiveScale.correctionX(),y/WindowAdaptiveScale.correctionY(),button));}
    @Override protected void keyTyped(char ch,int key)throws IOException{if(key==Keyboard.KEY_ESCAPE){onClose();return;}if(!closed)panel.onKeyTypedReceived(ch,key);}

    static final class CachePanel extends NcmSheet {
        final PlaybackFiles policy;
        private final Map<Panel,Double> heights=new IdentityHashMap<>();
        List<PlaybackFiles.Entry> items=List.of();
        ScrollPanel content;
        TextFieldWidget capacity;
        private String status="正在读取缓存…";
        private boolean busy;
        private int revision;
        CachePanel(PlaybackFiles policy){this.policy=policy;}
        int primary(){return getColor(NCMScreen.ColorType.PRIMARY_TEXT);}
        int secondary(){return getColor(NCMScreen.ColorType.SECONDARY_TEXT);}
        @Override public void onInit(){rebuild();refresh();}
        @Override protected ScrollPanel feed(Panel body){
            var list=new ScrollPanel(){@Override public void onRender(double x,double y){
                heights.forEach((row,h)->row.setBounds(Math.max(1,getWidth()),h));super.onRender(x,y);
            }};
            body.addChild(list);list.setSpacing(0).setScrollStrength(34).setAlignment(ScrollPanel.Alignment.VERTICAL);list.setIndicatorInsideViewport();
            list.setBeforeRenderCallback(()->{list.setBounds(Math.max(1,body.getWidth()),Math.max(1,body.getHeight()));list.setPosition(0,0);});return list;
        }
        Panel row(double height){Panel row=strip(content,height);heights.put(row,height);return row;}
        void line(Supplier<String> text){Panel r=row(24);var l=label(r,text,FontManager.pf12);
            l.setTooltip(text);l.setBeforeRenderCallback(()->{l.setColor(secondary());l.setMaxWidth(Math.max(1,r.getWidth()-46));
                l.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);l.setPosition(23,inkTop(l,14));});}
        RoundedButtonWidget button(Panel row,String text,Runnable run,double x,double w){
            var b=action(row,null,()->text,FontManager.pf12,this::primary,()->{if(!busy)run.run();});
            b.setBeforeRenderCallback(()->{double scale=layoutScale(row);double left=23+(x-23)*scale;
                b.setBounds(Math.max(20,w*scale),30);b.setPosition(left,4);
                b.setColor(getColor(b.isHovering()?NCMScreen.ColorType.ELEMENT_HOVER:NCMScreen.ColorType.ELEMENT_BACKGROUND));});return b;
        }
        double layoutScale(Panel row){return Math.min(1,Math.max(1,row.getWidth()-46)/580);}
        void rebuild(){
            beginPage();heights.clear();
            Panel dialog=sheet(660,()->Math.max(150,Math.min(650,getHeight()-24)));
            backButton(dialog,this::dispose);
            var heading=label(dialog,"音乐缓存",FontManager.pf18bold);heading.setBeforeRenderCallback(()->{heading.setColor(primary());heading.setPosition(60,inkTop(heading,28));});
            var state=label(dialog,()->status,FontManager.pf12);state.setBeforeRenderCallback(()->{state.setColor(secondary());state.setMaxWidth(Math.max(1,dialog.getWidth()-84));state.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);state.setPosition(60,inkTop(state,49));});
            content=feed(body(dialog,()->74,()->8));
            line(()->"位置："+policy.cacheDirectory());
            var folders=row(38);button(folders,"选择存储文件夹",this::chooseFolder,23,155);button(folders,"打开文件夹",this::openFolder,186,125);button(folders,"恢复默认位置",()->work(()->{policy.resetCacheParent();return "已切换默认位置；旧文件留在原处";}),319,145);
            line(()->"切换位置不搬移或删除旧文件；默认不保留音乐。完整曲目才会保存。");
            line(()->"容量上限（GiB，0 为不限）· 默认 1 GiB · 单曲最多 512 MiB");
            line(()->"保存后超额时优先删除最久未使用的完整缓存；正在读取的曲目会跳过。");
            var limits=row(40);
            var plate=new RoundedRectWidget();limits.addChild(plate);plate.setClickable(false).setRadius(8).setBeforeRenderCallback(()->{plate.setBounds(150*layoutScale(limits),30);plate.setPosition(23,4);plate.setColor(getColor(NCMScreen.ColorType.INPUT_BACKGROUND));});
            capacity=new TextFieldWidget(FontManager.pf12){@Override public boolean onKeyTyped(char ch,int key){if(isFocused()&&(key==Keyboard.KEY_RETURN||key==Keyboard.KEY_NUMPADENTER)){saveLimit();return true;}return super.onKeyTyped(ch,key);}};
            limits.addChild(capacity);capacity.getTextField().setMaxStringLength(18);capacity.setEnabled(true).setPlaceholder("例如 2.75；0 不限").drawUnderline(false);
            capacity.getTextField().setText(java.math.BigDecimal.valueOf(policy.limitGiB()).stripTrailingZeros().toPlainString());
            capacity.setBeforeRenderCallback(()->{capacity.setBounds(Math.max(20,plate.getWidth()-16),24);capacity.setPosition(31,9);capacity.setColor(primary());});
            button(limits,"保存容量",this::saveLimit,186,125);button(limits,"刷新列表",this::refresh,319,125);
            for(var item:items){
                var track=row(53);var title=label(track,item.title(),FontManager.pf14bold);
                title.setTooltip(item.title()+" · "+item.artist());title.setBeforeRenderCallback(()->{title.setColor(primary());title.setMaxWidth(Math.max(1,track.getWidth()-150));title.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);title.setPosition(23,inkTop(title,17));});
                var details=label(track,item.artist()+" · "+bytes(item.bytes()),FontManager.pf12);details.setBeforeRenderCallback(()->{details.setColor(secondary());details.setMaxWidth(Math.max(1,track.getWidth()-150));details.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);details.setPosition(23,inkTop(details,37));});
                var remove=button(track,"删除",()->work(()->policy.deleteEntry(item.id())?"已删除一首缓存":"缓存已不存在"),0,80);
                remove.setBeforeRenderCallback(()->{remove.setBounds(80,30);remove.setPosition(Math.max(23,track.getWidth()-103),9);remove.setColor(getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));});
            }
            if(items.isEmpty())line(()->"此位置还没有完整曲目缓存；可在临时文件页启用保留音乐缓存。");
            line(()->"旧版无标题的记录显示音频编号；正在播放的临时文件不会被删除。");
        }
        static String bytes(long value){return String.format(Locale.ROOT,value>=1073741824L?"%.2f GiB":"%.1f MiB",value/(value>=1073741824L?1073741824.0:1048576.0));}
        interface IOAction{String run()throws Exception;}
        void work(IOAction action){
            if(busy||closing)return;busy=true;int mine=++revision;status="正在处理…";
            BackgroundWork.submit(()->{String result;List<PlaybackFiles.Entry> found;
                try{result=action.run();found=policy.entries();}catch(Exception e){result="操作失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());found=items;}
                String message=result;var snapshot=found;
                BedrockClient.getInstance().execute(()->{if(closing||revision!=mine)return;busy=false;items=snapshot;
                    long total=items.stream().mapToLong(PlaybackFiles.Entry::bytes).sum();status=message+" · "+items.size()+" 首 / "+bytes(total);rebuild();});
            });
        }
        void refresh(){work(()->"已读取缓存");}
        void saveLimit(){String text=capacity.getText();work(()->{policy.setLimitGiB(Double.parseDouble(text.strip()));return "容量已保存";});}
        void chooseFolder(){
            if(busy||HostFileDialogs.active())return;
            HostFileDialogs.chooseDirectory(policy.cacheParent().toString(),picked->{if(closing||picked==null)return;work(()->{policy.setCacheParent(picked.toPath());return "已切换位置；旧文件留在原处";});});
        }
        void openFolder(){work(()->{Path p=policy.cacheDirectory();SessionMedia.requirePlainRoot(p);Files.createDirectories(p);
            java.awt.Desktop.getDesktop().open(p.toFile());return "已打开缓存文件夹";});}
    }
}
