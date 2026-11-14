package com.muoniumplayer.bedrock;

import com.muoniumplayer.core.ncm.music.AudioPlayer;
import java.util.Arrays;

/** Owned delayed-HTTP reproduction. Can also run with a frozen older host JAR. */
public final class PlaybackReleaseProbe {
    public static void main(String[] args) {
        int result=1;
        try(var remote=new StreamingSeekTest.Remote(StreamingSeekTest.audio(500))) {
            var token=ProgressiveAudio.prepare(remote.url());
            if(token==null)throw new AssertionError("Fixture must prepare incremental audio");
            var player=new AudioPlayer(token);
            long until=System.nanoTime()+2_000_000_000L;
            boolean reading=false;
            do {
                reading=Thread.getAllStackTraces().entrySet().stream().anyMatch(e->
                    e.getKey().getName().equals("Muonium-progressive-audio")
                    && Arrays.stream(e.getValue()).anyMatch(s->s.getClassName().contains("NioSocketImpl")
                        && (s.getMethodName().contains("read")||s.getMethodName().equals("park"))));
                if(!reading)Thread.sleep(10);
            }while(!reading&&System.nanoTime()<until);
            if(!reading)throw new AssertionError("Decoder must be inside the stalled socket read");
            var client=BedrockClient.getInstance();client.bindWindow(0);
            boolean[] dispatched={false};
            client.execute(player::close);client.execute(()->dispatched[0]=true);
            long before=System.nanoTime();client.drainTasks();
            long millis=(System.nanoTime()-before)/1_000_000;
            System.out.println("PLAYBACK_RELEASE blockedSocket=true nextUiTask="+dispatched[0]+" elapsedMs="+millis);
            if(!dispatched[0]||millis>=1500)throw new AssertionError("Player release blocked the UI task queue: "+millis+" ms");
            if(token.exists())throw new AssertionError("Released media token must be removed");
            result=0;
        }catch(Throwable failure){failure.printStackTrace();}
        finally{SessionMedia.close();}
        // Audio engine threads are confined to this disposable fixture.
        System.exit(result);
    }
}
