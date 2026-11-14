package com.muoniumplayer.compat.mc;
import com.muoniumplayer.core.rendering.font.CFontRenderer;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
public final class FontRenderer {
    private static final FontRenderer INSTANCE = new FontRenderer();
    public final int FONT_HEIGHT = 9;
    private CFontRenderer renderer;
    private CFontRenderer font() {
        if (renderer == null) renderer = new CFontRenderer(new Font("Dialog", Font.PLAIN, 18), 18f);
        return renderer;
    }
    public static FontRenderer vanilla() { return INSTANCE; }
    public int getStringWidth(String text) { return text == null ? 0 : font().getStringWidth(text); }
    public List<String> listFormattedStringToWidth(String text, int width) {
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        if (text == null) return result;
        for (int cp : text.codePoints().toArray()) {
            String s = new String(Character.toChars(cp));
            if (cp == '\n' || (line.length() > 0 && getStringWidth(line + s) > Math.max(1, width))) {
                result.add(line.toString()); line.setLength(0);
            }
            if (cp != '\n') line.append(s);
        }
        result.add(line.toString()); return result;
    }
    public int drawString(String text, int x, int y, int color) { font().drawString(text, x, y, color | 0xFF000000); return x + getStringWidth(text); }
    public int drawStringWithShadow(String text, float x, float y, int color) { return font().drawStringWithShadow(text, x, y, color | 0xFF000000); }
}
