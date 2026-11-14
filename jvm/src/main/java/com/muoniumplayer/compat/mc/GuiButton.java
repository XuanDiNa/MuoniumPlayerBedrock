package com.muoniumplayer.compat.mc;
public class GuiButton {
    public final int id;
    public int xPosition, yPosition, width, height;
    public String displayString;
    public boolean enabled = true, visible = true;
    public GuiButton(int id, int x, int y, String text) { this(id, x, y, 200, 20, text); }
    public GuiButton(int id, int x, int y, int w, int h, String text) { this.id = id; xPosition = x; yPosition = y; width = w; height = h; displayString = text; }
    public boolean mousePressed(int x, int y) { return visible && enabled && x >= xPosition && x < xPosition + width && y >= yPosition && y < yPosition + height; }
    public void drawButton(int x, int y) {
        if (!visible) return;
        Gui.drawRect(xPosition, yPosition, xPosition + width, yPosition + height, mousePressed(x, y) ? 0xFF505050 : 0xFF303030);
        var font = FontRenderer.vanilla(); font.drawStringWithShadow(displayString, xPosition + (width - font.getStringWidth(displayString)) / 2f, yPosition + (height - 9) / 2f, enabled ? 0xFFFFFFFF : 0xFF888888);
    }
}
