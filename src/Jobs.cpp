#include "Jobs.h"
#include "Runtime.h"
#include "json.hpp"
#include "modmenu_workspace_v1.h"
#include "Version.h"
namespace mp {
using J=nlohmann::json;
std::string MenuDeclaration(){
    return J{{"schemaVersion",1},{"plugin",{{"id","MuoniumPlayerBedrock"},{"entry","MuoniumPlayerBedrock.dll"},{"versions",{PluginVersion}}}},
        {"pages",J::array({{{"id","player"},{"label",{{"en_US","Player"},{"zh_TW","播放器"},{"zh_CN","播放器"}}},{"controls",J::array({
            {{"id","open"},{"type","action"},{"label",{{"en_US","Open player"},{"zh_TW","開啟播放器"},{"zh_CN","打开播放器"}}}},
            {{"id","hudEditor"},{"type","action"},{"label",{{"en_US","Edit HUD"},{"zh_TW","編輯 HUD"},{"zh_CN","编辑 HUD"}}}},
            {{"id","presets"},{"type","action"},{"label",{{"en_US","Presets and sharing"},{"zh_TW","預設與分享"},{"zh_CN","预设与分享"}}}},
            {{"id","miniPlayer"},{"type","action"},{"label",{{"en_US","Open mini player"},{"zh_TW","開啟迷你播放器"},{"zh_CN","打开迷你播放器"}}}},
            {{"id","islandSearch"},{"type","action"},{"label",{{"en_US","Search in island"},{"zh_TW","島內搜尋"},{"zh_CN","岛内搜索"}}}},
            {{"id","status"},{"type","status"},{"label",{{"en_US","Status"},{"zh_TW","狀態"},{"zh_CN","状态"}}}}
        })}}})}}.dump();
}
std::string MenuSnapshot(bool enabled,std::uint64_t revision,std::uint64_t request,bool succeeded){
    J acknowledgements=J::array();if(request)acknowledgements.push_back({{"requestId",request},{"state",succeeded?"succeeded":"failed"},{"message",succeeded?"Player opening":"Player unavailable"}});
    return J{{"protocolVersion",1},{"revision",revision},{"controls",{{"open",{{"enabled",enabled}}},{"hudEditor",{{"enabled",enabled}}},{"presets",{{"enabled",enabled}}},{"miniPlayer",{{"enabled",enabled}}},{"islandSearch",{{"enabled",enabled}}},{"status",{{"text",enabled?"Ready. Open the native player; all shortcuts default to NONE.":"Disabled. Enable the plugin in Configure."}}}}},{"results",acknowledgements}}.dump();
}
namespace {
void OwnerMarker(){}
class MenuBridge {
    std::uint64_t session_{};MMB_WorkspaceRegisterV1Fn reg_{};MMB_WorkspacePublishV1Fn publish_{};MMB_WorkspacePollV1Fn poll_{};MMB_WorkspaceUnregisterV1Fn unregister_{};
    bool published_{},lastEnabled_{};std::uint64_t revision_{};HMODULE module_{};std::filesystem::path folder_;
    void Reset(){if(session_&&unregister_)unregister_(session_);session_=0;published_=false;module_=nullptr;reg_=nullptr;publish_=nullptr;poll_=nullptr;unregister_=nullptr;}
    template<class F>F Resolve(HMODULE menu,char const* symbol){
        auto address=GetProcAddress(menu,symbol);HMODULE owner{};
        if(!address||!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,reinterpret_cast<LPCWSTR>(address),&owner)||owner!=menu)return nullptr;
        return reinterpret_cast<F>(address);
    }
public:
    explicit MenuBridge(std::filesystem::path folder):folder_(std::move(folder)){}
    ~MenuBridge(){Reset();}
    void Step(HWND window,bool enabled){
        auto vanilla=GetModuleHandleW(L"ModMenuBedrock.dll"),levi=GetModuleHandleW(L"ModMenuBedrockLL.dll");
#ifdef MPB_LEVILAMINA
        auto menu=levi;
#else
        auto menu=vanilla;
#endif
        if((vanilla&&levi)||!menu){Reset();return;}
        auto ready=Resolve<int(*)()>(menu,"MMB_GetStatus");
        if(!ready||ready()!=3){Reset();return;}
        if(module_&&module_!=menu)Reset();
        if(!session_){
            reg_=Resolve<MMB_WorkspaceRegisterV1Fn>(menu,"MMB_WorkspaceRegisterV1");publish_=Resolve<MMB_WorkspacePublishV1Fn>(menu,"MMB_WorkspacePublishV1");poll_=Resolve<MMB_WorkspacePollV1Fn>(menu,"MMB_WorkspacePollV1");unregister_=Resolve<MMB_WorkspaceUnregisterV1Fn>(menu,"MMB_WorkspaceUnregisterV1");if(!reg_||!publish_||!poll_||!unregister_){Reset();return;}
            auto declaration=MenuDeclaration();
            if(reg_(reinterpret_cast<void const*>(&OwnerMarker),declaration.data(),static_cast<std::uint32_t>(declaration.size()),&session_)!=0){session_=0;return;}
            module_=menu;revision_=0;
            Log(folder_,levi?"MODMENU workspace registered edition=LeviLamina ABI=WorkspaceV1":"MODMENU workspace registered edition=vanilla ABI=WorkspaceV1");
        }
        auto publish=[&](std::uint64_t request=0,bool succeeded=true){auto state=MenuSnapshot(enabled,++revision_,request,succeeded);if(publish_(session_,state.data(),static_cast<std::uint32_t>(state.size()))==0){published_=true;lastEnabled_=enabled;}};
        if(!published_||lastEnabled_!=enabled)publish();
        char buffer[32768];std::uint32_t needed{};int result=poll_(session_,buffer,sizeof buffer,&needed);
        if(result==-2)return;
        if(result<0){Reset();return;}
        if(result==0&&needed){auto cmd=J::parse(buffer,nullptr,false);if(!cmd.is_object()||!cmd.contains("requestId"))return;
            auto action=cmd.value("action",std::string{});int event=action=="open"?0:action=="hudEditor"?7:action=="presets"?8:action=="miniPlayer"?9:action=="islandSearch"?10:-1;
            bool ok=enabled&&event>=0&&PostMessageW(window,OpenPlayerMessage,event,0);publish(cmd["requestId"].get<std::uint64_t>(),ok);}
    }
};
}
Jobs::Jobs(HWND h,std::filesystem::path folder):hwnd_(h),folder_(std::move(folder)),cache_(folder_/L"cache"/(L"session-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64()))),thread_([this](std::stop_token s){Run(s);}),menuThread_([this](std::stop_token stop){
    MenuBridge menu(folder_);while(!stop.stop_requested()){bool enabled;{std::lock_guard lock(mutex_);enabled=enabled_;}try{menu.Step(hwnd_,enabled);}catch(...){/* Optional integration must not interrupt audio or networking. */}std::unique_lock lock(mutex_);cv_.wait_for(lock,stop,std::chrono::milliseconds(200),[]{return false;});}
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
