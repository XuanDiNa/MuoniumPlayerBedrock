package com.muoniumplayer.bedrock;

/** Rate-target fallback when native camera notifications are unavailable.
 * World lyrics follow the game rate regardless of crop size or prior cost. */
final class OverlayCadence {
    static long interval(boolean panel,boolean worldLyrics,int gameFps,long copied,long full){
        int hz=!panel&&worldLyrics&&gameFps>0&&gameFps<=999999?gameFps:60;
        return 1_000_000_000L/hz;
    }
    static long interval(boolean panel,boolean worldLyrics,int gameFps,long copied,long full,long workNanos){
        return interval(panel,worldLyrics,gameFps,copied,full);
    }
}
