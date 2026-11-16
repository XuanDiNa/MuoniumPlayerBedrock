"""Owned presentation choices over the pinned shared editor; legacy stays default."""
def once(source, old, new):
    assert source.count(old)==1,old
    return source.replace(old,new)

def apply(source):
    source=once(source,'public class GuiHudEditorPro extends GuiScreen {', '''public class GuiHudEditorPro extends GuiScreen {
    private final com.muoniumplayer.bedrock.UiPresentation.Selection bedrockUi =
            com.muoniumplayer.bedrock.UiPresentation.global().snapshot();
    public boolean transparentHost(){return true;}
    public boolean hostBackClosing(){return page.isDismissing();}
    // Modern panel geometry is independent; never overwrite legacy placement.
    private float modernPanelX=-1,modernPanelY=-1,modernScaleW=-1,modernScaleH=-1;
''')
    for name,owned in [('hudEditorPanelX','modernPanelX'),('hudEditorPanelY','modernPanelY'),
                       ('hudEditorPanelScaleW','modernScaleW'),('hudEditorPanelScaleH','modernScaleH')]:
        import re
        # Writes route to the selected presentation. Reads use the same source.
        pattern=r'HudConfig\.'+name+r' = ([^;]+);'
        source=re.sub(pattern,lambda m:'if(bedrockUi.modern()) '+owned+' = '+m[1]+'; else HudConfig.'+name+' = '+m[1]+';',source)
        source=re.sub(r'HudConfig\.'+name+r'(?!\s*=)', '(bedrockUi.modern() ? '+owned+' : HudConfig.'+name+')',source)
    source=once(source,'private static boolean panelMoved()', 'private boolean panelMoved()')
    source=once(source,'int ideal = (int) (width * 0.46f);','int ideal = (int) (width * (bedrockUi.modern() ? 0.82f : 0.46f));')
    source=once(source,'int cap = portrait ? 460 : PANEL_MAX_W;','int cap = portrait ? 460 : (bedrockUi.modern() ? 960 : PANEL_MAX_W);')
    source=once(source,'Math.min(PANEL_MAX_H, Math.min((int) (height * 0.72f), height - PANEL_MARGIN * 2))',
        'Math.min(bedrockUi.modern() ? 720 : PANEL_MAX_H, Math.min((int) (height * (bedrockUi.modern() ? .86f : .72f)), height - PANEL_MARGIN * 2))')
    source=once(source,'panelX = maxX > PANEL_MARGIN ? maxX - PANEL_MARGIN : maxX;',
        'panelX = bedrockUi.modern() ? maxX / 2 : (maxX > PANEL_MARGIN ? maxX - PANEL_MARGIN : maxX);')
    source=once(source,'"音乐 HUD 设置", panelX + 28','(bedrockUi.modern() ? "Muonium 设置中心" : "音乐 HUD 设置"), panelX + 28')
    source=once(source,'frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, PANEL_BG) : PANEL_BG',
        'frosted ? GlassLook.tintedArgb(GlassLook.Surface.EDITORS, bedrockUi.modern() ? 0xF21A2030 : PANEL_BG) : (bedrockUi.modern() ? 0xF21A2030 : PANEL_BG)')
    source=once(source,'CARD_RADIUS, CARD_BG);','CARD_RADIUS, bedrockUi.modern() ? 0x202E4160 : CARD_BG);')
    source=once(source,'pageFade = page.tick();','pageFade = bedrockUi.progress(page.tick());')
    source=once(source,'listFade = list.tick();','listFade = bedrockUi.progress(list.tick());')
    source=once(source,'long now = System.nanoTime();\n        double span =',
        'if (bedrockUi.reduced()) { chan.from = chan.to = chan.current = target; return target; }\n        long now = System.nanoTime();\n        double span =')
    source=once(source,'durationMs / SheetClock.speed());','durationMs * (bedrockUi.motion() == 1 ? 1.15 : 1.0) / SheetClock.speed());')
    source=once(source,'displayScroll = Interpolations.interpolate(displayScroll, scroll(), 19f);',
        'displayScroll = bedrockUi.reduced() ? scroll() : Interpolations.interpolate(displayScroll, scroll(), 19f);')
    source=once(source,'return listDirection * (1f - gate) * CONTENT_SLIDE;',
        'return bedrockUi.reduced() ? 0 : listDirection * (1f - gate) * CONTENT_SLIDE * (bedrockUi.motion() == 1 ? 1.6f : 1f);')
    source=once(source,'return (1f - gate) * CONTENT_DROP;',
        'return bedrockUi.reduced() ? 0 : (1f - gate) * CONTENT_DROP * (bedrockUi.motion() == 1 ? 1.5f : 1f);')
    source=once(source,'        page.requestDismiss();','        if (bedrockUi.reduced()) { closeScreen(); return; }\n        page.requestDismiss();')
    # The shared dropdown originally clipped to seven rows with no scrolling.
    # Keep all choices in the shared model; scroll drawing and hit testing together.
    source=once(source,'private HudSettingsModel.Entry openChoice;',
        'private HudSettingsModel.Entry openChoice;\n    private int choiceScroll;')
    source=once(source,'if (retiring || resetConfirmationVisible) wheel = 0;', '''if (retiring || resetConfirmationVisible) wheel = 0;
            if (openChoice != null) {
                int[] dropdown = choiceDropdownBounds(fr, panelX, panelY, panelW, panelH);
                if (wheel != 0 && dropdown != null
                        && isInside(mouseX, mouseY, dropdown[0], dropdown[1], dropdown[2], dropdown[3])) {
                    int steps = Math.max(1, Math.abs(wheel / 120));
                    choiceScroll = Math.max(0, Math.min(openChoice.getChoices().length - dropdown[5],
                            choiceScroll + (wheel > 0 ? -steps : steps)));
                }
                // A modal dropdown owns wheel input, including outside clicks/scrolls.
                wheel = 0;
            }''')
    source=once(source,'|| overPopover || draggingPanel;', '|| overPopover || draggingPanel || openChoice != null;')
    source=once(source,'openChoice = openChoice == row.entry ? null : row.entry;',
        'openChoice = openChoice == row.entry ? null : row.entry;\n                choiceScroll = 0;')
    source=once(source,'return new int[] { x, y, w, h, itemH, visible };',
        'choiceScroll = Math.max(0, Math.min(choiceScroll, choices.length - visible));\n        return new int[] { x, y, w, h, itemH, visible };')
    source=once(source,'if (index >= drop[5]) return -1;\n        return index;',
        'if (index >= drop[5]) return -1;\n        return choiceScroll + index;')
    source=once(source,'for (int index = 0; index < choices.length && index < visible; index++) {',
        'for (int row = 0; row < visible && choiceScroll + row < choices.length; row++) {\n                int index = choiceScroll + row;')
    source=once(source,'int itemY = y + 4 + index * itemH;', 'int itemY = y + 4 + row * itemH;')
    source=once(source,'''            // 条数太多被裁掉时给一个明确的「下面还有」记号，而不是无声截断。
            drawCenteredText(fr, "… 共 " + choices.length + " 项",
                    x + w / 2.0, y + h - 11, TEXT_TERTIARY);''', '''            // Scroll thumb stays clear of labels and never covers the last option.
            int track = h - 8;
            float thumb = Math.max(12f, track * visible / (float) choices.length);
            float at = y + 4 + (track - thumb) * choiceScroll / (choices.length - visible);
            drawRoundedRect(x + w - 5, y + 4, 2, track, 1, 0x22FFFFFF);
            drawRoundedRect(x + w - 5, at, 2, thumb, 1, 0x88FFFFFF);''')
    # Cull against the stationary sidebar viewport, not its scrolled content
    # origin. Otherwise the last categories stay invisible at every offset.
    source=once(source,
        'if (rowY + rowH < navTop - 4 || rowY > navTop + navH + 4) return;',
        'int viewportTop = navTop + navScroll - 6;\n        if (rowY + rowH < viewportTop || rowY > viewportTop + navH) return;')
    source=once(source,'        drawNav(fr, panelX, bodyY, navW, bodyH, categories, mouseX, mouseY);',
        '''        beginScissor(panelX, bodyY, navW, bodyH);
        try { drawNav(fr, panelX, bodyY, navW, bodyH, categories, mouseX, mouseY); }
        finally { endScissor(); }''')
    return source
