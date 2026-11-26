/* Runs only inside our dedicated page. No CDN URLs, signatures or tokens. */
(async()=>{
 const wanted=__VIDEO_ID__;
 const v=document.querySelector('video');
 const ad=()=>!!document.querySelector('.ad-showing,.ad-interrupting');
 if(!v)return {state:'loading'};
 const player=document.getElementById('movie_player');
 const id=player?.getVideoData?.()?.video_id;
 if(wanted&&id&&id!==wanted)return {state:'wrong-video'};
 if(ad()){
   // Use only the skip control offered by the official player. Never change
   // ad timers, media URLs, verification state, or server responses.
   return {state:'advertisement'};
 }
 const status=window.ytInitialPlayerResponse?.playabilityStatus?.status;
 if(status&&status!=='OK')return {state:'restricted',status};
 if(!Number.isFinite(v.duration)||v.duration<=0){v.play().catch(()=>{});return {state:'loading'};}
 if(!window.__muAudio){
   // Freeze validated metadata before the official player replaces its source.
   // Leave format/quality selection to the page's own adaptive player.
   const duration=v.duration;
   v.pause();
   const ac=new AudioContext({sampleRate:48000});
   const src=ac.createMediaElementSource(v),proc=ac.createScriptProcessor(4096,2,2);
   const q=window.__muAudio={v,ac,src,proc,duration,queue:[],cursor:0,lastSongTime:0,generation:0,ended:false,held:false,seeking:false,closed:false,callbacks:0};
   // The media clock and ScriptProcessor delivery do not end on the same
   // callback. Real post-roll replacement left PCM 0.307s behind the clock.
   // Compare callback delivery against the last media clock, not duration:
   // the last media callback itself can precede the declared tail slightly.
   // Hidden playback can deliver PCM 0.477s behind the declared tail. Require
   // the final media clock and at most six lagging callback blocks, with the
   // same hard six-block bound against duration; never
   // treat a mid-song ad or a missing audio second as completed content.
   const contentTail=()=>q.lastSongTime>=duration-2*proc.bufferSize/ac.sampleRate
     &&q.cursor/ac.sampleRate>=q.lastSongTime-6*proc.bufferSize/ac.sampleRate
     &&q.cursor/ac.sampleRate>=duration-6*proc.bufferSize/ac.sampleRate;
   proc.onaudioprocess=e=>{
     q.callbacks++;
     if(q.closed||q.ended||q.seeking||v.paused||v.seeking||v.readyState<3||ad())return;
     const current=player?.getVideoData?.()?.video_id;
     if(wanted&&current&&current!==wanted){q.wrong=true;v.pause();return;}
     q.lastSongTime=v.currentTime;
     const n=e.inputBuffer.length,a=e.inputBuffer.getChannelData(0),b=e.inputBuffer.getChannelData(Math.min(1,e.inputBuffer.numberOfChannels-1));
     let remaining=Math.max(0,Math.round(duration*ac.sampleRate)-q.cursor),frames=Math.min(n,remaining);
     if(!frames)return;
     let pcm=new Float32Array(frames*2);for(let i=0;i<frames;i++){pcm[i*2]=a[i];pcm[i*2+1]=b[i];}
     let bytes=new Uint8Array(pcm.buffer),s='';for(let i=0;i<bytes.length;i++)s+=String.fromCharCode(bytes[i]);
     q.queue.push({at:q.cursor,g:q.generation,pcm:btoa(s)});q.cursor+=frames;
     if(q.queue.length>=24){v.pause();q.held=true;}
   };
   v.addEventListener('ended',()=>{
     // The site's own ended handler can mark post-roll before ours runs.
     if(!ad()||contentTail()){q.ended=true;v.pause();}
   });
   v.addEventListener('seeked',()=>{if(q.seeking){q.seeking=false;if(!q.closed&&!q.ended)v.play().catch(()=>{});}});
   src.connect(proc);proc.connect(ac.destination);v.muted=false;v.volume=1;
   await ac.resume();
   q.seek=(frame)=>{q.generation++;q.queue=[];q.cursor=frame;q.lastSongTime=frame/ac.sampleRate;q.ended=false;q.held=false;q.seeking=true;v.currentTime=frame/ac.sampleRate;};
   q.pull=()=>{
     // Completion owns the final queued blocks even if the page starts a
     // post-roll advertisement. Never capture a later track as this song.
     // YouTube may replace the media source without emitting ended to us.
     // Both decoded PCM and the last content clock must already reach its tail.
     if(!q.ended&&ad()&&contentTail())q.ended=true;
     if(!q.ended&&q.wrong)return {state:'wrong-video'};
     if(!q.ended&&ad())return {state:'advertisement'};
     if(v.error)return {state:'media-error',code:v.error.code};
     let blocks=q.queue.splice(0,8);
     if(q.held&&q.queue.length<8&&!q.ended&&!q.seeking){q.held=false;v.play().catch(()=>{});}
     return {state:'ready',blocks,ended:q.ended&&q.queue.length===0,rate:ac.sampleRate,duration,time:v.currentTime,g:q.generation,
       paused:v.paused,held:q.held,seeking:q.seeking,ready:v.readyState,context:ac.state,
       callbacks:q.callbacks,sameVideo:document.querySelector('video')===v,cursor:q.cursor};
   };
   // An explicit play request owns this page; autoplay into a different track
   // is stopped by the ended listener, never admitted as this song's PCM.
   v.currentTime=0;await v.play();
 }
 return {state:'ready',rate:window.__muAudio.ac.sampleRate,duration:window.__muAudio.duration};
})()
