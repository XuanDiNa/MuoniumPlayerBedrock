"""Narrow, checked additions to the original playback owner; no vendor writes."""

def replace_once(text, old, new):
    assert text.count(old) == 1, old[:100]
    return text.replace(old, new)

def apply(text):
    text = replace_once(text, '        private static final class ArmedTrack {', '''        private static final class ArmedTrack {
            private boolean bedrockStreamingPlan, bedrockMixDone, bedrockMixApplied, bedrockMixInstalling;
            private FoliaVocalStem bedrockReplannedVocal;''')
    text = replace_once(text, '                    track.plan = com.muoniumplayer.core.ncm.music.StreamingMixPlanner.plan(ownedPlayer, deck, lastVocalEnd);', '''                    track.bedrockStreamingPlan = ownedPlayer != null
                            && (ownedPlayer.getSourceFile()==null || deck.getSourceFile()==null);
                    track.plan = com.muoniumplayer.core.ncm.music.StreamingMixPlanner.plan(ownedPlayer, deck, lastVocalEnd);''')
    text = replace_once(text, '                maybeReplanArmedTrack(activePlayer);', '''                bedrockStreamingMixTick(activePlayer);
                maybeReplanArmedTrack(activePlayer);''')
    text = replace_once(text, '        private void maybeReplanArmedTrack(AudioPlayer activePlayer) {', '''        private final StreamingMixPreparation bedrockMix = new StreamingMixPreparation();
        private AudioPlayer bedrockAnalysisDeck;
        private ArmedTrack bedrockMixTrack;
        private String bedrockMixSettings;
        private void bedrockMixReset() {
            bedrockMix.cancel();bedrockMixTrack=null;
            if(bedrockAnalysisDeck!=null)bedrockAnalysisDeck.bedrockCompleteForMix(false);
            bedrockAnalysisDeck=null;
        }
        private void bedrockStreamingMixTick(AudioPlayer active) {
            boolean enabled=com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled()
                    && AutomixSettings.isEnabled() && !isPlaybackCancelled() && active!=null && active==ownedPlayer
                    && !MpPartyClient.inRoom() && !NeteaseListenTogetherService.isActive() && !KuGouListenTogetherService.isActive();
            if(bedrockAnalysisDeck!=active) {
                if(bedrockAnalysisDeck!=null)bedrockAnalysisDeck.bedrockCompleteForMix(false);
                bedrockAnalysisDeck=active;
            }
            if(active!=null)active.bedrockCompleteForMix(enabled);
            ArmedTrack track=armed;
            if(!enabled || track==null || !track.bedrockStreamingPlan || track.roomMode
                    || track.preRolled || activeFade!=null || !armStillValid(track.generation,track.queueRevision)) {
                bedrockMix.cancel();bedrockMixTrack=null;return;
            }
            String settings=StreamingMixPreparation.settingsKey();
            if(bedrockMixTrack!=track || !settings.equals(bedrockMixSettings)) {
                bedrockMix.cancel();bedrockMixTrack=track;bedrockMixSettings=settings;track.bedrockMixDone=false;
            }
            if(track.bedrockMixDone || track.plan==null || track.replanning)return;
            if(active.getCurrentTimeMillis()+REPLAN_MIN_LEAD_MILLIS>track.plan.fireMillis-track.preRollMillis) {
                bedrockMix.cancel();track.bedrockMixDone=true;return;
            }
            bedrockMix.start(active,track.deck,estimateLastVocalEndMillis(active),()->armed==track && ownedPlayer==active
                    && !track.preRolled && activeFade==null && armStillValid(track.generation,track.queueRevision)
                    && com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled());
            if(!bedrockMix.finished())return;
            StreamingMixPreparation.Result result=bedrockMix.result();track.bedrockMixDone=true;
            if(result==null)return;
            track.replannedPlan=result.plan;track.bedrockReplannedVocal=result.vocal;track.bedrockMixInstalling=true;
            track.replanned=true;track.replanCount++;
            try {synchronized(PLAYER_STATE_LOCK) {
                if(armed==track && ownedPlayer==active && armStillValid(track.generation,track.queueRevision)
                        && com.muoniumplayer.bedrock.PlaybackFiles.global().prefetchEnabled()
                        && settings.equals(StreamingMixPreparation.settingsKey()))installReplannedPlan(track,active);
            }}
            finally {track.bedrockMixInstalling=false;track.bedrockReplannedVocal=null;}
            System.out.println("STREAMING_MIX analysis=complete applied="+track.bedrockMixApplied+" engine="+AutomixSettings.getEngine());
        }
        private void maybeReplanArmedTrack(AudioPlayer activePlayer) {
            if(armed!=null && armed.bedrockStreamingPlan && !armed.bedrockMixDone)return;''')
    text = replace_once(text, '                    && fresh.overlapMillis == old.overlapMillis) {',
                        '                    && fresh.overlapMillis == old.overlapMillis && !track.bedrockMixInstalling) {')
    text = replace_once(text, '                track.preRollMillis = preRoll;', '''                if(track.bedrockMixInstalling && track.bedrockReplannedVocal!=null
                        && deck.attachVocalMaterial(track.bedrockReplannedVocal))track.vocalMaterial=track.bedrockReplannedVocal;
                track.preRollMillis = preRoll;''')
    text = replace_once(text, '            track.plan = fresh;', '''            track.plan = fresh;
            if(track.bedrockMixInstalling)track.bedrockMixApplied=true;''')
    text = replace_once(text, '        private void cancelArmedTrack() {', '''        private void cancelArmedTrack() {
            bedrockMixReset();''')
    return text
