// SPDX-License-Identifier: Apache-2.0
// Standalone codec only: no Minecraft, loader, capture, network or rendering APIs.
#include <opus/opus.h>
#include <algorithm>
#include <cmath>
#include <new>
struct Codec { OpusEncoder* encoder{};OpusDecoder* decoder{}; };
#define API extern "C" __declspec(dllexport)
API void* MV_Create(){
    auto* c=new(std::nothrow) Codec;if(!c)return nullptr;int error=0;
    c->encoder=opus_encoder_create(48000,1,OPUS_APPLICATION_VOIP,&error);
    c->decoder=opus_decoder_create(48000,1,&error);
    if(!c->encoder||!c->decoder){if(c->encoder)opus_encoder_destroy(c->encoder);if(c->decoder)opus_decoder_destroy(c->decoder);delete c;return nullptr;}
    opus_encoder_ctl(c->encoder,OPUS_SET_BITRATE(32000));
    opus_encoder_ctl(c->encoder,OPUS_SET_COMPLEXITY(5));
    opus_encoder_ctl(c->encoder,OPUS_SET_DTX(1));return c;
}
API void MV_Destroy(void* handle){auto* c=static_cast<Codec*>(handle);if(!c)return;opus_encoder_destroy(c->encoder);opus_decoder_destroy(c->decoder);delete c;}
API int MV_Encode(void* handle,float const* pcm,int count,unsigned char* packet,int capacity){
    auto* c=static_cast<Codec*>(handle);if(!c||!pcm||count!=960||!packet||capacity<1||capacity>400)return -1;
    float finite[960];for(int i=0;i<960;i++)finite[i]=std::isfinite(pcm[i])?std::clamp(pcm[i],-1.0f,1.0f):0;
    return opus_encode_float(c->encoder,finite,960,packet,capacity);
}
API int MV_Decode(void* handle,unsigned char const* packet,int bytes,float* pcm,int capacity){
    auto* c=static_cast<Codec*>(handle);if(!c||!packet||bytes<1||bytes>400||!pcm||capacity!=960)return -1;
    if(opus_packet_get_nb_samples(packet,bytes,48000)!=960)return -1;
    int n=opus_decode_float(c->decoder,packet,bytes,pcm,960,0);
    if(n==960)for(int i=0;i<960;i++)pcm[i]=std::isfinite(pcm[i])?std::clamp(pcm[i],-1.0f,1.0f):0;
    return n;
}
