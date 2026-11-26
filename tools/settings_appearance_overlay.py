"""Settings-page themes on the existing HUD editor and its shared model."""


def once(text, old, new):
    assert text.count(old) == 1, old[:120]
    return text.replace(old, new)


def apply_model(text):
    text=once(text,'        NONE,','        NONE,\n        SETTINGS_ACCENT,')
    text=once(text,'        public Color getColor() {','''        public Color getColor() {
            if(colorTarget==ColorTarget.SETTINGS_ACCENT)
                return new Color(com.muoniumplayer.bedrock.SettingsAppearance.global().accent(),true);''')
    text=once(text,'            if (selected == null) return;','''            if (selected == null) return;
            if(colorTarget==ColorTarget.SETTINGS_ACCENT){com.muoniumplayer.bedrock.SettingsAppearance.global().setAccent(selected.getRGB());return;}''')
    anchor='                ,new Category("host-tools",'
    text=once(text,anchor,'''                ,new Category("settings-appearance", "设置页主题", "设置中心的主题、主色与动画；不改变歌曲和游戏 HUD 配色", 0xFF2B4FFF, HudSettingsIcons.MODERN_UI,
                        new Group("设置页外观", HudSettingsIcons.MODERN_UI,
                                choice("设置页主题", "新版设置中心使用四张主题卡；旧版保留原有外观。选择立即预览并保存。",
                                        () -> com.muoniumplayer.bedrock.SettingsAppearance.global().label(),
                                        () -> com.muoniumplayer.bedrock.SettingsAppearance.global().nextTheme(),
                                        com.muoniumplayer.bedrock.SettingsAppearance.themes(),
                                        value -> com.muoniumplayer.bedrock.SettingsAppearance.global().selectTheme(value)),
                                color("设置页主色", "按钮、开关与导航的强调色。支持取色及十六进制输入；不修改歌词颜色。",ColorTarget.SETTINGS_ACCENT),
                                toggle("设置页动画", "关闭后跳过设置页面的过渡、滚动缓动及控件弹动；保留原有动画风格选择。",
                                        () -> com.muoniumplayer.bedrock.SettingsAppearance.global().animations(),
                                        () -> com.muoniumplayer.bedrock.SettingsAppearance.global().setAnimations(!com.muoniumplayer.bedrock.SettingsAppearance.global().animations()))))
''' + anchor)
    text=once(text,'            "界面与主题",','            "设置页主题",\n            "界面与主题",')
    return text


def apply_editor(text):
    roles={'PANEL_BG':'panel','PANEL_EDGE':'edge','NAV_BG':'nav','NAV_HOVER':'hover','NAV_SELECTED':'selected',
           'CARD_BG':'card','CARD_EDGE':'cardEdge','ROW_HOVER':'rowHover','TEXT_PRIMARY':'text',
           'TEXT_SECONDARY':'secondary','TEXT_TERTIARY':'tertiary','TRACK_BG':'track','SWITCH_OFF':'off',
           'BUTTON_BG':'button','BUTTON_HOVER':'buttonHover'}
    for name in roles:
        text=once(text,'private static final int '+name+' =','private int '+name+' =')
    assignments='\n'.join('        '+name+'=p.'+role+'();' for name,role in roles.items())
    text=once(text,'    public void drawScreen(int mouseX, int mouseY, float partialTicks) {',
        '    public void drawScreen(int mouseX, int mouseY, float partialTicks) {\n        applySettingsAppearance();')
    text=once(text,'    public void onGuiClosed() {','    public void onGuiClosed() {\n        com.muoniumplayer.bedrock.SettingsAppearance.global().flush();')
    text=once(text,'        super.mouseReleased(mouseX, mouseY, state);',
        '        com.muoniumplayer.bedrock.SettingsAppearance.global().flush();\n        super.mouseReleased(mouseX, mouseY, state);')
    text=once(text,'        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;',
        '        if(bedrockUi.modern() && com.muoniumplayer.bedrock.SettingsAppearance.global().square())radius=0;\n        float alpha = ((color >>> 24) & 0xFF) / 255.0F * layerAlpha;')
    # The existing modern overlay had two hard-coded dark surfaces.
    text=once(text,'bedrockUi.modern() ? 0x202E4160 : CARD_BG','CARD_BG')
    text=text.replace('bedrockUi.modern() ? 0xF21A2030 : PANEL_BG','PANEL_BG')
    text=once(text,'            case CURRENT: return "当前歌词颜色";',
        '            case SETTINGS_ACCENT: return "设置页主色";\n            case CURRENT: return "当前歌词颜色";')
    # Popovers must use the same light/dark palette as their text.
    for surface in ['0xF71A202A','0xF41A202A']:
        assert surface in text
        text=text.replace(surface,'(bedrockUi.modern()?PANEL_BG:'+surface+')')
    text=once(text,'hexColorInput.isFocused() ? 0x33FFFFFF : 0x1FFFFFFF',
        'bedrockUi.modern() ? (hexColorInput.isFocused()?BUTTON_HOVER:BUTTON_BG) : (hexColorInput.isFocused() ? 0x33FFFFFF : 0x1FFFFFFF)')
    text=once(text,'drawRoundedRect(caretX, inputY + 4, 1, HEX_INPUT_H - 8, 1, 0xFFEAF3FF)',
        'drawRoundedRect(caretX, inputY + 4, 1, HEX_INPUT_H - 8, 1, TEXT_PRIMARY)')
    text=once(text,'frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, PANEL_BG)',
        'frosted && !(bedrockUi.modern() && com.muoniumplayer.bedrock.SettingsAppearance.global().light()) ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, PANEL_BG)')
    import re
    text=re.sub(r'(?<!this)\b(active|category|row)\.accent\b',r'settingsAccent(\1.accent)',text)
    text=once(text,'categories[selected].accent);','settingsAccent(categories[selected].accent));')
    text=once(text,'int h = entry.kind == HudSettingsModel.Kind.THEME_GRID ? themeGridHeight() + 14',
        'int h = settingsThemeGrid(entry) ? 160 : entry.kind == HudSettingsModel.Kind.THEME_GRID ? themeGridHeight() + 14')
    text=once(text,'        if (row.entry.kind == HudSettingsModel.Kind.THEME_GRID) {',
        '        if(settingsThemeGrid(row.entry)){drawSettingsThemes(fr,row,mouseX,mouseY);return;}\n        if (row.entry.kind == HudSettingsModel.Kind.THEME_GRID) {')
    text=once(text,'        if (row.entry.isLocked()) return;',
        '''        if (row.entry.isLocked()) return;
        if(settingsThemeGrid(row.entry)){
            for(int i=0;i<4;i++){
                int[] r=settingsThemeBounds(row,i);
                if(isInside(mouseX,mouseY,r[0],r[1],r[2],r[3])){
                    row.entry.setChoice(com.muoniumplayer.bedrock.SettingsAppearance.themes()[i]);break;
                }
            }
            return;
        }''')
    marker='    private void drawRoundedRect(double x, double y, double w, double h, double radius, int color) {'
    code='''    private void applySettingsAppearance(){
        if(!bedrockUi.modern())return;
        var p=com.muoniumplayer.bedrock.SettingsAppearance.global().palette();
'''+assignments+'''
    }
    private int settingsAccent(int fallback){return bedrockUi.modern()?com.muoniumplayer.bedrock.SettingsAppearance.global().accent():fallback;}
    private boolean settingsThemeGrid(HudSettingsModel.Entry entry){return bedrockUi.modern() && entry!=null && "设置页主题".equals(entry.getLabel());}
    private int[] settingsThemeBounds(Row row,int index){
        int gap=8,w=(row.w-24-3*gap)/4;
        return new int[]{row.x+12+index*(w+gap),row.y+40,w,108};
    }
    private void drawSettingsThemes(CFontRenderer fr,Row row,int mouseX,int mouseY){
        drawText(FontManager.pf12bold,row.entry.getLabel(),row.x+14,row.y+8,TEXT_PRIMARY);
        drawText(fr,"预览设置页外观；不改变播放器主题",row.x+14,row.y+24,TEXT_SECONDARY);
        var appearance=com.muoniumplayer.bedrock.SettingsAppearance.global();
        for(int i=0;i<4;i++){
            int[] r=settingsThemeBounds(row,i);int x=r[0],y=r[1],w=r[2],h=r[3];
            boolean selected=appearance.snapshot().theme()==i,hover=isInside(mouseX,mouseY,x,y,w,h);
            drawRoundedRect(x,y,w,h,10,selected?appearance.accent():hover?TEXT_TERTIARY:PANEL_EDGE);
            drawRoundedRect(x+2,y+2,w-4,h-4,8,PANEL_BG);
            var p=com.muoniumplayer.bedrock.SettingsAppearance.palette(i);
            int inset=8,px=x+inset,py=y+8,pw=w-2*inset,ph=68;
            // Preview surfaces use the actual palette. These are vector UI
            // previews, never downloaded screenshots or persistent image cache.
            drawRoundedRect(px,py,pw,ph,i>=2?0:5,p.panel());
            drawRoundedRect(px,py,pw*.24,ph,i>=2?0:5,p.nav());
            for(int n=0;n<5;n++)drawRoundedRect(px+4,py+10+n*9,pw*.14,3,1,n==1?appearance.accent():p.secondary());
            for(int n=0;n<6;n++){
                double cx=px+pw*.29+(n%3)*pw*.22,cy=py+12+(n/3)*25;
                drawRoundedRect(cx,cy,pw*.19,19,i>=2?0:3,p.cardEdge());
                drawRoundedRect(cx,cy+15,pw*.19,4,1,appearance.accent());
            }
            String label=com.muoniumplayer.bedrock.SettingsAppearance.themes()[i];
            drawCenteredText(fr,fr.trim(label,w-10),x+w/2.0,y+86,TEXT_PRIMARY);
        }
    }
'''
    return once(text,marker,code+'\n'+marker)
