#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <mfplay.h>
#include <wrl/client.h>
#include <cstdint>
#include <string>
namespace mp {
constexpr UINT AudioMessage=WM_APP+41;
struct AudioNotice {
    std::uint64_t generation{}; MFP_EVENT_TYPE type{}; HRESULT error{};
    Microsoft::WRL::ComPtr<IMFPMediaItem> item;
};
class Audio {
public:
    explicit Audio(HWND hwnd):hwnd_(hwnd){}
    ~Audio(){Stop();}
    HRESULT Load(std::wstring const&,std::uint64_t generation,bool autoPlay=true);
    void Stop();
    void Toggle();
    void Seek(std::int64_t milliseconds);
    void Volume(int percent);
    bool Handle(AudioNotice const&);
    std::int64_t Position()const;
    std::int64_t Duration()const{return duration_;}
    bool Playing()const{return playing_;}
    bool Loaded()const{return loaded_;}
    bool Ended()const{return ended_;}
    std::uint64_t Generation()const{return generation_;}
    HRESULT Error()const{return error_;}
private:
    HWND hwnd_{};Microsoft::WRL::ComPtr<IMFPMediaPlayer> player_;
    std::uint64_t generation_{};std::int64_t duration_{};
    bool playing_{},loaded_{},ended_{},autoPlay_{};int volume_{70};HRESULT error_{S_OK};
};
}
