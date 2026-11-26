package com.muoniumplayer.bedrock;

/** Distinguishes a missed nonblocking read from an explicit invalid state. */
final class CinematicSnapshotCache {
    private CinematicState state;
    private long validatedAt;
    private String status="empty";
    private long busyReads,rejectedReads,maxValidatedAgeMs;

    void clear(String reason){state=null;validatedAt=0;status=reason;}
    CinematicState read(byte[] raw,long now){
        state=CinematicState.decode(raw,now);
        if(state==null){rejectedReads++;clear("invalid-or-expired");return null;}
        validatedAt=now;status="validated";
        maxValidatedAgeMs=Math.max(maxValidatedAgeMs,now-state.at());
        return state;
    }
    CinematicState contended(long now){
        busyReads++;
        if(state==null){status="busy-empty";return null;}
        // The 100ms limit is a lease since the last synchronized validation,
        // not a second, shorter lifetime for a successfully read native state.
        // Neither repeated contention nor a clock reversal can renew it.
        if(now<validatedAt||now-validatedAt>100){clear("busy-lease-expired");return null;}
        if(now<state.at()||now-state.at()>250){clear("busy-state-expired");return null;}
        status="busy-reused";return state;
    }
    String status(){return status;}
    long busyReads(){return busyReads;}
    long rejectedReads(){return rejectedReads;}
    long maxValidatedAgeMs(){return maxValidatedAgeMs;}
}
