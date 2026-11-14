#include "Client.h"
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Media.Control.h>
#include <winrt/Windows.Storage.Streams.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
namespace mp {
namespace {
using namespace winrt::Windows::Media::Control;
using namespace winrt::Windows::Storage::Streams;
template<class T>auto Await(T const& op){if(op.wait_for(std::chrono::seconds(3))!=winrt::Windows::Foundation::AsyncStatus::Completed){op.Cancel();throw std::runtime_error("Desktop client did not respond");}return op.get();}
std::string Bounded(winrt::hstring const&s){auto t=winrt::to_string(s);return t.size()<=4096?t:std::string{};}
ClientSnapshot Read(GlobalSystemMediaTransportControlsSession const&s,ClientSnapshot previous){
    ClientSnapshot out;out.connected=true;out.generation=previous.generation;out.coverRevision=previous.coverRevision;out.revision=previous.revision+1;
    auto properties=Await(s.TryGetMediaPropertiesAsync());out.title=Bounded(properties.Title());out.artist=Bounded(properties.Artist());out.album=Bounded(properties.AlbumTitle());
    auto info=s.GetPlaybackInfo();auto c=info.Controls();out.playing=info.PlaybackStatus()==GlobalSystemMediaTransportControlsSessionPlaybackStatus::Playing;
    out.canPlay=c.IsPlayEnabled();out.canPause=c.IsPauseEnabled();out.canNext=c.IsNextEnabled();out.canPrevious=c.IsPreviousEnabled();out.canStop=c.IsStopEnabled();
    auto t=s.GetTimelineProperties();out.durationMs=std::max<std::int64_t>(0,(t.EndTime()-t.StartTime()).count()/10000);out.positionMs=std::max<std::int64_t>(0,(t.Position()-t.StartTime()).count()/10000);
    if(out.playing&&out.durationMs>0){auto elapsed=(winrt::clock::now()-t.LastUpdatedTime()).count()/10000;out.positionMs+=std::clamp<std::int64_t>(elapsed,0,5000);}
    if(out.durationMs)out.positionMs=std::min(out.positionMs,out.durationMs);out.canSeek=c.IsPlaybackPositionEnabled()&&out.durationMs>0;
    if(out.title==previous.title&&out.artist==previous.artist&&out.album==previous.album)out.cover=previous.cover;
    else{++out.coverRevision;try{if(auto thumb=properties.Thumbnail()){auto stream=Await(thumb.OpenReadAsync());auto size=stream.Size();if(size&&size<=4*1024*1024){DataReader reader(stream.GetInputStreamAt(0));auto n=Await(reader.LoadAsync(static_cast<std::uint32_t>(size)));if(n==size){auto bytes=std::make_shared<std::vector<std::uint8_t>>(n);reader.ReadBytes(*bytes);out.cover=bytes;}}}}catch(...){/* Artwork is optional. */}}
    out.message=out.canSeek?"Connected to NetEase desktop client.":"Connected. This client session does not expose a seekable timeline.";return out;
}
}
struct Client::Impl {
    mutable std::mutex mutex;std::condition_variable_any cv;bool wanted{},unavailable{};std::uint64_t epoch{},serial{};ClientSnapshot state;std::deque<ClientCommand> commands;std::jthread worker;
    Impl():worker([this](std::stop_token stop){Run(stop);}){}
    ~Impl(){worker.request_stop();cv.notify_all();if(worker.joinable())worker.join();}
    void Publish(ClientSnapshot s,std::uint64_t expected){std::lock_guard lock(mutex);if(expected==epoch&&wanted)state=std::move(s);}
    void Run(std::stop_token stop){
        try{winrt::init_apartment(winrt::apartment_type::multi_threaded);}catch(...){std::lock_guard lock(mutex);unavailable=true;state.message="Windows desktop media API unavailable";return;}
        GlobalSystemMediaTransportControlsSessionManager manager{nullptr};GlobalSystemMediaTransportControlsSession selected{nullptr};std::uint64_t observedEpoch=~std::uint64_t{};
        while(!stop.stop_requested()){
            bool active;std::uint64_t current;ClientSnapshot previous;std::deque<ClientCommand> pending;
            {std::unique_lock lock(mutex);cv.wait_for(lock,stop,std::chrono::milliseconds(500),[&]{return epoch!=observedEpoch||!commands.empty();});active=wanted;current=epoch;previous=state;pending.swap(commands);}
            if(stop.stop_requested())break;
            if(current!=observedEpoch){selected=nullptr;manager=nullptr;observedEpoch=current;}
            if(!active)continue;
            try{
                if(!manager)manager=Await(GlobalSystemMediaTransportControlsSessionManager::RequestAsync());
                GlobalSystemMediaTransportControlsSession found{nullptr};for(auto const&s:manager.GetSessions())if(IsNetEaseSource(winrt::to_string(s.SourceAppUserModelId()))){found=s;break;}
                if(!found){selected=nullptr;ClientSnapshot empty;empty.generation=++serial;empty.message="NetEase session not found. Open CloudMusic and select a song, then Connect.";Publish(empty,current);continue;}
                bool changed=found!=selected;selected=found;if(changed){previous={};previous.generation=++serial;}
                auto fresh=Read(selected,previous);
                for(auto const&cmd:pending){
                    {std::lock_guard lock(mutex);if(epoch!=current||!wanted)break;}
                    if(!ClientAllows(fresh,cmd))continue;
                    bool ok=false;
                    switch(cmd.action){case ClientAction::Play:ok=Await(selected.TryPlayAsync());break;case ClientAction::Pause:ok=Await(selected.TryPauseAsync());break;case ClientAction::Next:ok=Await(selected.TrySkipNextAsync());break;case ClientAction::Previous:ok=Await(selected.TrySkipPreviousAsync());break;case ClientAction::Stop:ok=Await(selected.TryStopAsync());break;case ClientAction::Seek:{auto base=selected.GetTimelineProperties().StartTime().count();ok=Await(selected.TryChangePlaybackPositionAsync(base+cmd.positionMs*10000));break;}}
                    fresh=Read(selected,fresh);if(!ok)fresh.message="NetEase rejected this playback command.";
                }
                Publish(std::move(fresh),current);
            }catch(winrt::hresult_error const&e){selected=nullptr;manager=nullptr;ClientSnapshot empty;empty.generation=++serial;empty.errorCode=static_cast<std::uint32_t>(e.code().value);empty.message="Desktop media API refused the request. Retry Connect; see plugin log for the error code.";Publish(empty,current);}
            catch(...){selected=nullptr;manager=nullptr;ClientSnapshot empty;empty.generation=++serial;empty.message="Desktop media API unavailable or timed out. Retry Connect.";Publish(empty,current);}
        }
        selected=nullptr;manager=nullptr;winrt::uninit_apartment();
    }
};
Client::Client():impl_(std::make_unique<Impl>()){}Client::~Client()=default;
void Client::Connect(){std::lock_guard lock(impl_->mutex);if(impl_->unavailable)return;impl_->wanted=true;++impl_->epoch;impl_->commands.clear();impl_->state={};impl_->state.message="Connecting to NetEase desktop client…";impl_->cv.notify_all();}
void Client::Disconnect(){std::lock_guard lock(impl_->mutex);impl_->wanted=false;++impl_->epoch;impl_->commands.clear();impl_->state={};impl_->cv.notify_all();}
bool Client::Send(ClientCommand c){std::lock_guard lock(impl_->mutex);if(!impl_->wanted||!ClientAllows(impl_->state,c)||impl_->commands.size()>=8)return false;impl_->commands.push_back(c);impl_->cv.notify_all();return true;}
ClientSnapshot Client::Snapshot()const{std::lock_guard lock(impl_->mutex);return impl_->state;}
}
