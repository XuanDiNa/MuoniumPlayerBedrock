package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.rendering.entities.impl.TextField;
import com.muoniumplayer.compat.lwjgl2.Mouse;

/** The upstream editable text model, not a replacement editor implementation. */
public class GuiTextField extends TextField {
    public final int id;
    private boolean background;
    public GuiTextField(int id, FontRenderer ignored, int x, int y, int w, int h) { super(id, x, y, w, h); this.id = id; }
    public void setEnableBackgroundDrawing(boolean value) { background = value; }
    public void drawTextBox() {
        if (background) Gui.drawRect((int) xPosition - 1, (int) yPosition - 1, (int) (xPosition + width + 1), (int) (yPosition + height + 1), 0xFF202020);
        drawTextBox(Mouse.getX(), com.muoniumplayer.compat.lwjgl2.Display.getHeight() - Mouse.getY() - 1);
    }
}
