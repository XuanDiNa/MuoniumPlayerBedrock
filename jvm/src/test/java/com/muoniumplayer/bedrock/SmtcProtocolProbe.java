package com.muoniumplayer.bedrock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Compile the shipped helper and exercise stdin/EOF only. No media-session
 * enumeration, publication, external-player controls or account access. */
public final class SmtcProtocolProbe {
    public static void main(String[] args) throws Exception {
        Class<?> tooling=Class.forName("com.muoniumplayer.core.ncm.music.SmtcTooling");
        var ensure=tooling.getDeclaredMethod("ensure");ensure.setAccessible(true);
        Object result=ensure.invoke(null);
        var ready=result.getClass().getDeclaredMethod("isReady");ready.setAccessible(true);
        if(!(boolean)ready.invoke(result)) {
            var reason=result.getClass().getDeclaredField("reason");reason.setAccessible(true);
            throw new IllegalStateException("SMTC compiler unavailable: "+reason.get(result));
        }
        var field=result.getClass().getDeclaredField("executable");field.setAccessible(true);
        File exe=(File)field.get(result);
        Process process=new ProcessBuilder(exe.getAbsolutePath()).redirectErrorStream(true).start();
        try {
            process.getOutputStream().write("fixture-unknown-command\nexit\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if(!process.waitFor(15,TimeUnit.SECONDS))throw new IllegalStateException("SMTC helper EOF timeout");
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if(process.exitValue()!=0||!output.contains("\"event\":\"ready\"")||!output.contains("unknown_command"))
                throw new IllegalStateException("SMTC helper protocol failed: "+output);
            System.out.println("SMTC_HELPER compiled=true ready=true protocol-error=true clean-exit=true no-media-control=true");
        }finally {if(process.isAlive())process.destroyForcibly().waitFor();}
    }
}
