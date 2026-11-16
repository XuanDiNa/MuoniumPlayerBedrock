#include "Jobs.h"
#include "Runtime.h"
#include "MenuBEBridge.h"
namespace mp {
Jobs::Jobs(HWND h,std::filesystem::path folder):hwnd_(h),folder_(std::move(folder)),cache_(folder_/L"cache"/(L"session-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64()))),thread_([this](std::stop_token s){Run(s);}),menuThread_([this](std::stop_token stop){
    MenuBEBridge menu(folder_,workspace_);while(!stop.stop_requested()){bool enabled;{std::lock_guard lock(mutex_);enabled=enabled_;}try{if(settingsRequested_.exchange(false))menu.OpenSettings();menu.Step(hwnd_,enabled);}catch(...){/* Optional integration must not interrupt audio or networking. */}std::unique_lock lock(mutex_);cv_.wait_for(lock,stop,std::chrono::milliseconds(50),[]{return false;});}
}){}
Jobs::~Jobs(){menuThread_.request_stop();thread_.request_stop();cv_.notify_all();if(menuThread_.joinable())menuThread_.join();if(thread_.joinable())thread_.join();for(auto&r:results_)if(!r.path.empty()){std::error_code ec;std::filesystem::remove(r.path,ec);}std::error_code ec;std::filesystem::remove(cache_,ec);}
void Jobs::Submit(Job j){if(j.search)j.kind=JobKind::Search;std::lock_guard lock(mutex_);if(enabled_){std::erase_if(pending_,[&](Job const& old){return old.kind==j.kind;});if(j.kind!=JobKind::Playback)pending_.push_back(std::move(j));else pending_.push_front(std::move(j));}cv_.notify_all();}
void Jobs::Enabled(bool e){std::lock_guard lock(mutex_);enabled_=e;if(!e)pending_.clear();cv_.notify_all();}
std::deque<JobResult> Jobs::Take(){std::lock_guard lock(mutex_);std::deque<JobResult> out;out.swap(results_);return out;}
void Jobs::Run(std::stop_token stop){
    while(!stop.stop_requested()){
        std::optional<Job> job;bool enabled;
        {std::unique_lock lock(mutex_);cv_.wait_for(lock,stop,std::chrono::milliseconds(200),[&]{return !pending_.empty();});enabled=enabled_;if(!pending_.empty()){job=std::move(pending_.front());pending_.pop_front();}}
        if(stop.stop_requested())break;
        if(!job||!enabled)continue;JobResult out;out.search=job->search;out.kind=job->kind;out.generation=job->generation;
        try{if(job->kind==JobKind::Search)out.tracks=SearchNetEase(job->query);else if(job->kind==JobKind::Playlist)out.tracks=ImportNetEasePlaylist(job->id);else if(job->kind==JobKind::Lyrics)out.lyrics=NetEaseLyrics(job->id);else{auto media=ResolveNetEase(job->id);out.path=DownloadMedia(media.url,cache_,job->generation);out.lyrics=std::move(media.lyrics);out.preview=media.preview;}Log(folder_,"NETWORK explicit request completed");}
        catch(std::exception const&e){out.error=e.what();if(out.error.size()>500)out.error="Music request failed";Log(folder_,job->search?"SEARCH failed; details shown only in player":"RESOLVE failed; details shown only in player");}
        {std::lock_guard lock(mutex_);if(results_.size()>=4){auto& old=results_.front();if(!old.path.empty()){std::error_code ec;std::filesystem::remove(old.path,ec);}results_.pop_front();}results_.push_back(std::move(out));}
    }
}
}
