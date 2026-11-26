package com.muoniumplayer.bedrock;

import com.muoniumplayer.compat.mc.GuiScreen;
import com.muoniumplayer.compat.lwjgl2.*;
import com.muoniumplayer.core.management.FontManager;
import com.muoniumplayer.core.rendering.rendersystem.RenderSystem;
import com.muoniumplayer.core.rendering.ui.container.*;
import com.muoniumplayer.core.rendering.ui.widgets.*;
import com.muoniumplayer.core.screens.ncm.*;
import com.muoniumplayer.render.skija.SkijaGfx;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Explicit settings page; opening it neither connects nor saves anything. */
public final class RoomProxyScreen extends GuiScreen {
    private final GuiScreen parent;
    final ProxyPanel panel;
    private boolean built,closed;
    RoomProxyScreen(GuiScreen parent,RoomProxySettings settings){this.parent=parent;panel=new ProxyPanel(settings);}
    public static void open(){var client=BedrockClient.getInstance();client.gui.setScreen(new RoomProxyScreen(client.gui.screen(),null));}
    @Override public void initGui(){Keyboard.enableRepeatEvents(true);if(!built){built=true;panel.onInit();}}
    @Override public void onGuiClosed(){closed=true;panel.dispose();Keyboard.enableRepeatEvents(false);}
    @Override public void onClose(){if(!closed){mc.gui.setScreen(parent);if(parent instanceof NCMScreen player)player.openMpDirect();}}
    @Override public void updateScreen(){if(!closed)panel.onTickReceived();}
    @Override public void suspendHostInput(){panel.unfocus();super.suspendHostInput();}
    private void scaled(Runnable run){RenderSystem.refreshResolution();WindowAdaptiveScale.beginForced();try{run.run();}finally{WindowAdaptiveScale.end();}}
    @Override public void drawScreen(int x,int y,float partial){if(closed||!SkijaGfx.active())return;scaled(()->{
        double sx=WindowAdaptiveScale.correctionX(),sy=WindowAdaptiveScale.correctionY();SkijaGfx.pushMatrix();SkijaGfx.scale(sx,sy);
        try{panel.setBounds(0,0,RenderSystem.getWidth(),RenderSystem.getHeight());panel.renderWidget(x/sx,y/sy,0);
            int wheel=Mouse.getDWheel();if(wheel!=0)panel.onDWheelReceived(x/sx,y/sy,wheel);if(panel.shouldClose())onClose();
        }finally{SkijaGfx.popMatrix();}
    });}
    @Override protected void mouseClicked(int x,int y,int button){if(!closed)scaled(()->panel.onMouseClickReceived(x/WindowAdaptiveScale.correctionX(),y/WindowAdaptiveScale.correctionY(),button));}
    @Override protected void keyTyped(char ch,int key)throws IOException{if(key==Keyboard.KEY_ESCAPE){onClose();return;}if(!closed)panel.onKeyTypedReceived(ch,key);}

    static final class ProxyPanel extends NcmSheet {
        private RoomProxySettings settings;
        private final Map<Panel,Double> heights=new IdentityHashMap<>();
        RoomProxySettings.Mode mode=RoomProxySettings.Mode.DIRECT;
        TextFieldWidget host,port;
        RoundedButtonWidget save;
        final Map<RoomProxySettings.Mode,RoundedButtonWidget> modes=new EnumMap<>(RoomProxySettings.Mode.class);
        ScrollPanel content;
        boolean loaded,busy;
        String status="正在读取代理设置…";
        ProxyPanel(RoomProxySettings settings){this.settings=settings;}
        int primary(){return getColor(NCMScreen.ColorType.PRIMARY_TEXT);}
        int secondary(){return getColor(NCMScreen.ColorType.SECONDARY_TEXT);}
        @Override public void onInit(){rebuild();Thread.ofVirtual().name("muonium-room-proxy-load").start(()->{
            var policy=settings==null?RoomProxySettings.global():settings;
            RoomProxySettings.Value value;String message;
            try{value=policy.get();message="保存后在下次加入公网房间时使用；不会断开当前房间。";}
            catch(IllegalStateException e){value=new RoomProxySettings.Value(RoomProxySettings.Mode.DIRECT,"",0);message=e.getMessage();}
            var snapshot=value;String result=message;
            BedrockClient.getInstance().execute(()->{if(closing)return;settings=policy;mode=snapshot.mode();loaded=true;status=result;
                host.setText(snapshot.host().isEmpty()?"127.0.0.1":snapshot.host());port.setText(snapshot.port()==0?"":Integer.toString(snapshot.port()));});
        });}
        @Override protected ScrollPanel feed(Panel body){
            var list=new ScrollPanel(){@Override public void onRender(double x,double y){heights.forEach((r,h)->r.setBounds(Math.max(1,getWidth()),h));super.onRender(x,y);}};
            body.addChild(list);list.setSpacing(0).setScrollStrength(32).setAlignment(ScrollPanel.Alignment.VERTICAL);list.setIndicatorInsideViewport();
            list.setBeforeRenderCallback(()->{list.setBounds(Math.max(1,body.getWidth()),Math.max(1,body.getHeight()));list.setPosition(0,0);});return list;
        }
        Panel row(double h){Panel row=strip(content,h);heights.put(row,h);return row;}
        void line(Supplier<String> text){var r=row(27);var label=label(r,text,FontManager.pf12);label.setTooltip(text);
            label.setBeforeRenderCallback(()->{label.setColor(secondary());label.setMaxWidth(Math.max(1,r.getWidth()-46));label.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);label.setPosition(23,inkTop(label,14));});}
        RoundedButtonWidget button(Panel r,String text,Runnable run){return action(r,null,()->text,FontManager.pf12,this::primary,()->{if(loaded&&!busy)run.run();});}
        void rebuild(){
            beginPage();heights.clear();modes.clear();var dialog=sheet(660,()->Math.max(180,Math.min(485,getHeight()-24)));
            backButton(dialog,this::dispose);var title=label(dialog,"一起听 · 连接代理",FontManager.pf18bold);
            title.setBeforeRenderCallback(()->{title.setColor(primary());title.setPosition(60,inkTop(title,28));});
            var state=label(dialog,()->status,FontManager.pf12);state.setTooltip(()->status);
            state.setBeforeRenderCallback(()->{state.setColor(secondary());state.setMaxWidth(Math.max(1,dialog.getWidth()-84));state.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);state.setPosition(60,inkTop(state,51));});
            content=feed(body(dialog,()->76,()->8));
            line(()->"仅代理加入 wss 公网邀请的房间同步；局域网／地址直连不受影响。");
            line(()->"房主穿透、歌曲下载不跟随此设置；全局／TUN 按其路由规则运行。");
            var options=row(42);int index=0;
            for(var option:RoomProxySettings.Mode.values()) {
                final int slot=index++;var b=button(options,option.label,()->mode=option);modes.put(option,b);
                b.setBeforeRenderCallback(()->{double width=Math.max(1,(options.getWidth()-58)/4);b.setBounds(width,30);b.setPosition(23+slot*(width+4),5);
                    b.setColor(getColor(mode==option?NCMScreen.ColorType.ACCENT:NCMScreen.ColorType.ELEMENT_BACKGROUND));});
            }
            line(()->mode==RoomProxySettings.Mode.SYSTEM?"系统模式读取 Windows 手动代理／PAC；规则明确直连时才直连。":"自定义代理支持无认证的 HTTP CONNECT 或 SOCKS5。");
            var inputs=row(45);host=field(inputs,"主机名或 IP",253);port=field(inputs,"端口",5);
            host.setBeforeRenderCallback(()->{host.setBounds(Math.max(30,inputs.getWidth()*.67-23),30);host.setPosition(23,5);host.setEnabled(custom()&&loaded&&!busy);host.setColor(primary());});
            port.setBeforeRenderCallback(()->{port.setBounds(Math.max(30,inputs.getWidth()*.23),30);port.setPosition(inputs.getWidth()*.72,5);port.setEnabled(custom()&&loaded&&!busy);port.setColor(primary());});
            line(()->"填写代理软件实际提供的地址和端口；不会自动启动加速器。");
            line(()->"代理不可用时会报错，不会自动改走直连。支持远端解析房间域名。");
            var actions=row(44);save=button(actions,"保存代理设置",this::save);
            save.setBeforeRenderCallback(()->{save.setBounds(Math.min(220,actions.getWidth()-46),32);save.setPosition(23,5);save.setColor(getColor(NCMScreen.ColorType.ELEMENT_BACKGROUND));});
            line(()->"打开本页不会连接、开房或保存。Esc 返回；失焦不取消后台工作。");
        }
        private boolean custom(){return mode==RoomProxySettings.Mode.HTTP||mode==RoomProxySettings.Mode.SOCKS5;}
        TextFieldWidget field(Panel r,String placeholder,int max){var input=new TextFieldWidget(FontManager.pf14);r.addChild(input);input.getTextField().setMaxStringLength(max);input.setPlaceholder(placeholder).drawUnderline(true).setEnabled(true);return input;}
        void unfocus(){if(host!=null)host.setFocused(false);if(port!=null)port.setFocused(false);}
        void save(){
            if(!loaded||busy)return;final RoomProxySettings.Value next;
            try{next=new RoomProxySettings.Value(mode,host.getText(),custom()?Integer.parseInt(port.getText().strip()):0);}
            catch(RuntimeException e){status=e instanceof NumberFormatException?"代理端口须为 1～65535":e.getMessage();return;}
            busy=true;unfocus();status="正在保存…";
            Thread.ofVirtual().name("muonium-room-proxy-save").start(()->{
                String result;try{settings.save(next);result="已保存："+next.mode().label+" · 下次加入公网房间生效";}
                catch(Exception e){result="代理设置保存失败；原有设置仍然有效";}
                String message=result;BedrockClient.getInstance().execute(()->{busy=false;if(!closing)status=message;});
            });
        }
    }
}
