package com.muoniumplayer.compat.mc;
import com.muoniumplayer.bedrock.BedrockClient;
import com.muoniumplayer.compat.lwjgl2.Keyboard;
import com.muoniumplayer.core.utils.KeyboardUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
public abstract class GuiScreen {
    protected final BedrockClient mc = BedrockClient.getInstance();
    protected final FontRenderer fontRendererObj = FontRenderer.vanilla();
    protected final List<GuiButton> buttonList = new ArrayList<>();
    public int width, height;
    public void initGui() {}
    public void onGuiClosed() {}
    public void updateScreen() {}
    public void onClose() { closeScreen(); }
    public void closeScreen() { mc.gui.setScreen(null); }
    public boolean doesGuiPauseGame() { return false; }
    public boolean transparentHost() { return false; }
    public void resize(int w, int h) { width = w; height = h; buttonList.clear(); initGui(); }
    public void drawScreen(int x, int y, float partial) { for (var b : buttonList) b.drawButton(x, y); }
    public final void drawHostFrame(float partial) {
        double scale = mc.getWindow().getGuiScale();
        drawScreen((int) (com.muoniumplayer.compat.lwjgl2.Mouse.getX() / scale),
                (int) ((mc.getWindow().getHeight() - com.muoniumplayer.compat.lwjgl2.Mouse.getY() - 1) / scale), partial);
    }
    protected void keyTyped(char ch, int code) throws IOException { if (code == Keyboard.KEY_ESCAPE) onClose(); }
    protected void mouseClicked(int x, int y, int button) throws IOException {
        if (button == 0) for (var b : new ArrayList<>(buttonList)) if (b.mousePressed(x, y)) { actionPerformed(b); break; }
    }
    protected void mouseReleased(int x, int y, int button) {}
    protected void actionPerformed(GuiButton button) throws IOException {}
    public final void keyEvent(char ch, int code) throws IOException { keyTyped(ch, code); }
    public final void clickEvent(int x, int y, int button) throws IOException { mouseClicked(x, y, button); }
    public final void releaseEvent(int x, int y, int button) { mouseReleased(x, y, button); }
    public void handleMouseInput() throws IOException {}
    public void drawDefaultBackground() { Gui.drawRect(0, 0, width, height, 0x90000000); }
    public void drawCenteredString(FontRenderer f, String text, int x, int y, int color) { f.drawStringWithShadow(text, x - f.getStringWidth(text) / 2f, y, color); }
    public static void drawRect(int l, int t, int r, int b, int c) { Gui.drawRect(l, t, r, b, c); }
    public static String getClipboardString() { return KeyboardUtils.getClipboardString(); }
    public static void setClipboardString(String s) { KeyboardUtils.setClipboardString(s); }
}
