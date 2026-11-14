package com.muoniumplayer.bedrock;

import java.awt.Font;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public resource edition: real font parsing, glyphs and licenses. */
class PublicFontsTest {
    @Test void publicFontsRetainLatinCjkAndLicenseResources() throws Exception {
        for (String name : new String[]{"sfregular.otf", "sfbold.otf", "pf_normal.ttf", "pf_middleblack.ttf"}) {
            try (var input=getClass().getResourceAsStream("/muonium/fonts/"+name)) {
                assertNotNull(input,name);
                var font=Font.createFont(Font.TRUETYPE_FONT,input);
                assertTrue(font.getFamily(Locale.ROOT).startsWith("Noto Sans"),font.getFamily(Locale.ROOT));
                assertEquals(-1,font.canDisplayUpTo("MuoniumPlayer 0123456789"),name);
                if(name.startsWith("pf_"))
                    assertEquals(-1,font.canDisplayUpTo("音樂播放器设置歌詞繁體简体日本語한국어"),name);
            }
        }
        for (String name : new String[]{"NotoSans-OFL.txt","NotoSansCJK-OFL.txt"}) {
            try(var input=getClass().getResourceAsStream("/META-INF/licenses/public-fonts/"+name)) {
                assertNotNull(input,name);
                assertTrue(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains("SIL OPEN FONT LICENSE"));
            }
        }
    }
}
