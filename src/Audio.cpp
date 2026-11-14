#include "Audio.h"
#include <mfapi.h>
#include <propvarutil.h>
#include <algorithm>
#include <atomic>
#include <memory>
namespace mp {
namespace {
class Callback final:public IMFPMediaPlayerCallback {
public:
    Callback(HWND h,std::uint64_t g):hwnd_(h),generation_(g){}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID iid,void** out)override{if(!out)return E_POINTER;*out=nullptr;if(iid==__uuidof(IUnknown)||iid==__uuidof(IMFPMediaPlayerCallback)){*out=static_cast<IMFPMediaPlayerCallback*>(this);AddRef();return S_OK;}return E_NOINTERFACE;}
    ULONG STDMETHODCALLTYPE AddRef()override{return ++refs_;}
    ULONG STDMETHODCALLTYPE Release()override{auto n=--refs_;if(!n)delete this;return n;}
    void STDMETHODCALLTYPE OnMediaPlayerEvent(MFP_EVENT_HEADER* e)override{
        if(!e)return;
        try{auto notice=std::make_unique<AudioNotice>();notice->generation=generation_;notice->type=e->eEventType;notice->error=e->hrEvent;
            if(e->eEventType==MFP_EVENT_TYPE_MEDIAITEM_CREATED)notice->item=reinterpret_cast<MFP_MEDIAITEM_CREATED_EVENT*>(e)->pMediaItem;
            if(PostMessageW(hwnd_,AudioMessage,0,reinterpret_cast<LPARAM>(notice.get())))notice.release();
        }catch(...){/* Never throw across Media Foundation's callback boundary. */}
    }
private:
    std::atomic<ULONG> refs_{1};HWND hwnd_;std::uint64_t generation_;
};
}
HRESULT Audio::Load(std::wstring const& path,std::uint64_t generation,bool autoPlay){
    Stop();generation_=generation;autoPlay_=autoPlay;error_=S_OK;
    Microsoft::WRL::ComPtr<Callback> cb;cb.Attach(new Callback(hwnd_,generation));
    error_=MFPCreateMediaPlayer(nullptr,FALSE,MFP_OPTION_NONE,cb.Get(),nullptr,&player_);
    if(SUCCEEDED(error_))error_=player_->CreateMediaItemFromURL(path.c_str(),FALSE,0,nullptr);
    return error_;
}
void Audio::Stop(){if(player_){player_->Shutdown();player_.Reset();}++generation_;duration_=0;playing_=loaded_=ended_=false;}
void Audio::Toggle(){if(!player_||!loaded_)return;error_=playing_?player_->Pause():player_->Play();}
void Audio::Seek(std::int64_t ms){if(!player_||!loaded_||duration_<=0)return;PROPVARIANT pv;InitPropVariantFromUInt64(static_cast<ULONGLONG>(std::clamp<std::int64_t>(ms,0,duration_))*10000,&pv);error_=player_->SetPosition(MFP_POSITIONTYPE_100NS,&pv);PropVariantClear(&pv);ended_=false;}
void Audio::Volume(int n){volume_=std::clamp(n,0,100);if(player_)player_->SetVolume(static_cast<float>(volume_)/100.0f);}
std::int64_t Audio::Position()const{if(!player_||!loaded_)return 0;PROPVARIANT p;PropVariantInit(&p);std::int64_t value{};if(SUCCEEDED(player_->GetPosition(MFP_POSITIONTYPE_100NS,&p))&&p.vt==VT_UI8)value=static_cast<std::int64_t>(p.uhVal.QuadPart/10000);PropVariantClear(&p);return value;}
bool Audio::Handle(AudioNotice const& n){
    if(n.generation!=generation_||!player_)return false;
    if(FAILED(n.error)){error_=n.error;playing_=false;return true;}
    switch(n.type){
        case MFP_EVENT_TYPE_MEDIAITEM_CREATED:
            if(n.item){PROPVARIANT pv;PropVariantInit(&pv);if(SUCCEEDED(n.item->GetDuration(MFP_POSITIONTYPE_100NS,&pv))&&pv.vt==VT_UI8)duration_=static_cast<std::int64_t>(pv.uhVal.QuadPart/10000);PropVariantClear(&pv);error_=player_->SetMediaItem(n.item.Get());}break;
        case MFP_EVENT_TYPE_MEDIAITEM_SET:loaded_=true;Volume(volume_);if(autoPlay_)error_=player_->Play();break;
        case MFP_EVENT_TYPE_PLAY:playing_=true;ended_=false;break;
        case MFP_EVENT_TYPE_PAUSE:case MFP_EVENT_TYPE_STOP:playing_=false;break;
        case MFP_EVENT_TYPE_PLAYBACK_ENDED:playing_=false;ended_=true;break;
        default:break;
    }return true;
}
}
