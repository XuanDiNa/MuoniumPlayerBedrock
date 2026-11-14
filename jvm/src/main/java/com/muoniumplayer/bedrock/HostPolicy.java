package com.muoniumplayer.bedrock;

/** Startup is passive. Optional upstream tools are downloaded on explicit use. */
public final class HostPolicy {
    public static void initializeSources() {
        if (!Boolean.getBoolean("muonium.bedrock.offlineTest"))
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(
                    com.muoniumplayer.core.ncm.lxsource.LxSourceService::init);
    }
    public static void initializeMusic() {
        if (!Boolean.getBoolean("muonium.bedrock.offlineTest"))
            com.muoniumplayer.core.utils.other.multithreading.MultiThreadingUtil.runAsync(
                    com.muoniumplayer.core.ncm.music.CloudMusic::initNCM);
    }
    public static void optionalFontDownload() {
        if (Boolean.getBoolean("muonium.bedrock.prefetchFonts"))
            com.muoniumplayer.core.management.FontAssetDownload.ensureFonts();
    }
    public static void optionalCompilerDownload() {
        if (Boolean.getBoolean("muonium.bedrock.prefetchCompiler"))
            com.muoniumplayer.core.ncm.lxsource.ClosureCompilerDownload.ensureDownload();
    }
    public static void optionalQishuiStart() {
        if (Boolean.getBoolean("muonium.bedrock.startQishui"))
            com.muoniumplayer.core.ncm.music.QishuiBackendLifecycle.ensureStartedForGame();
    }
}
