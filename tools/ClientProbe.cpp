#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <iostream>
int main(){try{
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    auto manager=winrt::Windows::Media::Control::GlobalSystemMediaTransportControlsSessionManager::RequestAsync().get();
    auto sessions=manager.GetSessions();std::cout<<"SESSIONS="<<sessions.Size()<<"\n";
    for(auto const& s:sessions){auto id=winrt::to_string(s.SourceAppUserModelId());std::cout<<"SOURCE="<<id<<"\n";std::string low=id;for(auto&c:low)c=static_cast<char>(tolower(c));if(low.find("cloudmusic")==low.npos&&low.find("netease")==low.npos)continue;
        auto playback=s.GetPlaybackInfo();auto controls=playback.Controls();auto media=s.TryGetMediaPropertiesAsync().get();auto time=s.GetTimelineProperties();
        std::cout<<"NETEASE_METADATA="<<(!media.Title().empty())<<" PLAY="<<controls.IsPlayEnabled()<<" PAUSE="<<controls.IsPauseEnabled()<<" NEXT="<<controls.IsNextEnabled()<<" PREVIOUS="<<controls.IsPreviousEnabled()<<" SEEK="<<controls.IsPlaybackPositionEnabled()<<" DURATION_MS="<<(time.EndTime().count()-time.StartTime().count())/10000<<" COVER="<<bool(media.Thumbnail())<<"\n";
    }return 0;
}catch(winrt::hresult_error const&e){std::cerr<<"WINRT_ERROR=0x"<<std::hex<<static_cast<unsigned>(e.code().value)<<"\n";return 1;}}
