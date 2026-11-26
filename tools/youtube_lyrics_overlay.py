"""Validate official YouTube cues before admitting them to the shared timeline."""


def apply_service(text):
    begin = text.index('    static String buildLrc(JsonObject response) {')
    end = text.index('    /** 毫秒 →', begin)
    text = text[:begin] + TIMELINE + '\n' + text[end:]
    marker = '            List<JsonObject> shelves = new ArrayList<>();'
    # Only the lyrics response, not other shelves in this large service.
    start = text.index('    public static Lyrics getLyrics(String videoId) {')
    pos = text.index(marker, start)
    text = text[:pos] + '''            String untimed = buildUntimedLyrics(response);
            if (!untimed.isEmpty()) return new Lyrics("", untimed, readSourceMessage(response), false);

''' + text[pos:]
    return text


def apply_loader(text):
    marker = '            if (lyrics == null || lyrics.isEmpty()) return null;'
    assert text.count(marker) == 1
    return text.replace(marker, marker + '''
            if (!lyrics.timed) {
                System.out.println("[Music/YouTube] 官方歌词缺少有效时间轴，继续匹配同步歌词 for " + music.getStableKey());
                return null;
            }''')


TIMELINE = '''    static String buildLrc(JsonObject response) {
        List<JsonElement> blocks = new ArrayList<>();
        collectValues(response, "timedLyricsData", blocks);
        for (JsonElement block : blocks) {
            if (block == null || !block.isJsonArray()) continue;
            StringBuilder out = new StringBuilder();
            long first = -1, previous = -1;
            int rows = 0;
            boolean valid = true;
            for (JsonElement element : block.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) { valid = false; break; }
                JsonObject line = element.getAsJsonObject();
                String text = string(line, "lyricLine");
                if (text.trim().isEmpty()) continue;
                JsonElement raw = object(line, "cueRange").get("startTimeMilliseconds");
                long start;
                try {
                    if (raw == null || !raw.isJsonPrimitive()
                            || raw.getAsJsonPrimitive().isBoolean()) { valid = false; break; }
                    start = new java.math.BigDecimal(raw.getAsString()).longValueExact();
                } catch (NumberFormatException | ArithmeticException invalid) { valid = false; break; }
                if (start < 0 || start < previous) { valid = false; break; }
                if (rows++ == 0) first = start;
                previous = start;
                if (out.length() > 0) out.append('\\n');
                out.append('[').append(timestamp(start)).append(']').append(text);
            }
            // A whole block is required: never drop untimed rows and publish a
            // misleading partial song. Explicit zero is valid, but a multi-line
            // song whose every cue is identical has no usable progression.
            if (valid && rows > 0 && (rows == 1 || previous > first)) return out.toString();
        }
        return "";
    }

    private static String buildUntimedLyrics(JsonObject response) {
        List<JsonElement> blocks = new ArrayList<>();
        collectValues(response, "timedLyricsData", blocks);
        for (JsonElement block : blocks) {
            if (block == null || !block.isJsonArray()) continue;
            StringBuilder out = new StringBuilder();
            for (JsonElement element : block.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) continue;
                String text = string(element.getAsJsonObject(), "lyricLine");
                if (text.trim().isEmpty()) continue;
                if (out.length() > 0) out.append('\\n');
                out.append(text);
            }
            if (out.length() > 0) return out.toString();
        }
        return "";
    }
'''
