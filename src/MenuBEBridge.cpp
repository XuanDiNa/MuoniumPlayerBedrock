#include "MenuBEBridge.h"
#include "Jobs.h"
#include <cstddef>
#include <memory>
#include <mutex>
#include <vector>
#include "Version.h"
#include "json.hpp"
#include <set>
namespace mp {
namespace {
constexpr char Name[]="MuoniumPlayerBedrock"; // manifest.json name, for both loaders.
bool InModule(HMODULE module,void const* pointer,bool executable){
    MEMORY_BASIC_INFORMATION info{};
    if(!pointer||!VirtualQuery(pointer,&info,sizeof info)||info.AllocationBase!=module||info.State!=MEM_COMMIT||info.Type!=MEM_IMAGE||(info.Protect&(PAGE_GUARD|PAGE_NOACCESS)))return false;
    auto protection=info.Protect&0xff;
    return !executable||protection==PAGE_EXECUTE||protection==PAGE_EXECUTE_READ||protection==PAGE_EXECUTE_READWRITE||protection==PAGE_EXECUTE_WRITECOPY;
}
bool CopyApi(HMODULE module,ModMenuApi& out){
    auto raw=GetProcAddress(module,"ModMenu_GetApi");
    if(!InModule(module,reinterpret_cast<void const*>(raw),true))return false;
    auto table=reinterpret_cast<ModMenu_GetApiFn>(raw)(1);
    if(!InModule(module,table,false))return false;
    struct Prefix{uint32_t version,size;} prefix{};SIZE_T read{};
    if(!ReadProcessMemory(GetCurrentProcess(),table,&prefix,sizeof prefix,&read)||read!=sizeof prefix)return false;
    constexpr auto v1=offsetof(ModMenuApi,addCommand);
    if((prefix.version!=1&&prefix.version!=2)||prefix.size<(prefix.version==1?v1:sizeof(ModMenuApi))||prefix.size>65536)return false;
    out={};auto bytes=prefix.version==1?v1:sizeof(ModMenuApi);
    if(!ReadProcessMemory(GetCurrentProcess(),table,&out,bytes,&read)||read!=bytes)return false;
    return InModule(module,reinterpret_cast<void const*>(out.setExternalScreen),true)&&InModule(module,reinterpret_cast<void const*>(out.unregister),true);
}
bool CopyWorkspaceApi(HMODULE module,ModMenuWorkspaceApi& out){
    auto raw=GetProcAddress(module,"ModMenu_GetWorkspaceApi");
    if(!InModule(module,reinterpret_cast<void const*>(raw),true))return false;
    auto table=reinterpret_cast<ModMenu_GetWorkspaceApiFn>(raw)(1);
    if(!InModule(module,table,false))return false;
    struct Prefix{uint32_t version,size;} p{};SIZE_T read{};
    if(!ReadProcessMemory(GetCurrentProcess(),table,&p,sizeof p,&read)||read!=sizeof p||p.version!=1||p.size<sizeof out||p.size>65536)return false;
    if(!ReadProcessMemory(GetCurrentProcess(),table,&out,sizeof out,&read)||read!=sizeof out)return false;
    return InModule(module,reinterpret_cast<void const*>(out.isReady),true)
        &&InModule(module,reinterpret_cast<void const*>(out.registerProvider),true)
        &&InModule(module,reinterpret_cast<void const*>(out.publish),true)
        &&InModule(module,reinterpret_cast<void const*>(out.poll),true)
        &&InModule(module,reinterpret_cast<void const*>(out.unregisterProvider),true)
        &&InModule(module,reinterpret_cast<void const*>(out.acknowledgeExternal),true)
        &&InModule(module,reinterpret_cast<void const*>(out.returnToWorkspace),true);
}
nlohmann::json Caption(char const* en,char const* zh){return {{"en_US",en},{"zh_TW",zh},{"zh_CN",zh}};}
std::string Declaration(){
    using J=nlohmann::json;
    auto status=J{{"id","status"},{"label",Caption("Player","播放器")},{"controls",J::array({J{{"id","availability"},{"type","status"},{"label",Caption("Availability","可用狀態")}}})}};
    auto help=J{{"id","help"},{"label",Caption("Help","使用說明")},{"controls",J::array({J{{"id","navigation"},{"type","status"},{"label",Caption("Navigation","操作方式")}}})}};
    return J{{"schemaVersion",1},{"plugin",{{"id",Name},{"entry","MuoniumPlayerBedrock.dll"},{"versions",J::array({PluginVersion})}}},
        {"pages",J::array({status,help})},{"externalTargets",J::array({"player","hud","presets","mini","island"})}}.dump();
}
nlohmann::json Command(std::string const& bytes){
    std::vector<std::set<std::string>> stack;
    return nlohmann::json::parse(bytes,[&](int depth,nlohmann::json::parse_event_t event,nlohmann::json& v){
        if(depth>16)throw std::runtime_error("command depth");
        if(event==nlohmann::json::parse_event_t::object_start)stack.emplace_back();
        if(event==nlohmann::json::parse_event_t::key&&!stack.back().insert(v.get<std::string>()).second)throw std::runtime_error("duplicate command key");
        if(event==nlohmann::json::parse_event_t::object_end)stack.pop_back();return true;
    });
}
}
MenuBEBridge::MenuBEBridge(std::filesystem::path folder,WorkspaceChannel& channel):folder_(std::move(folder)),route_(folder_),channel_(channel){
    // The menu may retain a copy-on-write screen snapshot after unregister.
    // Tickets stay valid for this pinned DLL's process lifetime; retired tickets
    // are inert and never reference a Jobs object or its window.
    static std::mutex mutex;
    static std::vector<std::unique_ptr<Request>> tickets;
    std::lock_guard lock(mutex);tickets.push_back(std::make_unique<Request>());request_=tickets.back().get();
}
void MenuBEBridge::Open(void* user) noexcept{
    auto& request=*static_cast<Request*>(user);
    if(request.alive.load()&&request.enabled.load())request.pending.store(true);
}
MenuBEBridge::~MenuBEBridge(){
    request_->alive=false;request_->enabled=false;request_->pending=false;
    if(registered_)api_.unregister(Name);
    if(session_)workspace_.unregisterProvider(session_);
    auto r=channel_.Read();channel_.Transition(r,r.phase,WorkspacePhase::Cancelled);
    if(module_)FreeLibrary(module_);
}
void MenuBEBridge::Step(HWND window,bool enabled){
    request_->enabled=enabled;
    if(!module_){
        // Optional dependency: never load it by search path or request its installation.
        HMODULE candidate{};
        if(GetModuleHandleExW(0,L"ModMenuBE.dll",&candidate)) {
            ModMenuApi checked{};
            if(CopyApi(candidate,checked)){module_=candidate;api_=checked;workspaceExport_=GetProcAddress(module_,"ModMenu_GetWorkspaceApi")!=nullptr;}
            else FreeLibrary(candidate);
        }
    }
    if(module_&&workspaceExport_){
        WorkspaceStep(enabled);
        if(settingsRequested_){
            settingsRequested_=false;
            if(enabled&&InModule(module_,reinterpret_cast<void const*>(api_.openMenu),true)){
                auto r=channel_.Read();channel_.Transition(r,r.phase,WorkspacePhase::Cancelled);api_.openMenu(Name,1);
            }
        }
        return;
    }
    if(module_&&!registered_){
        if(api_.setExternalScreen(Name,&Open,request_)) {
            registered_=true;
            Log(folder_,"MODMENU external-screen registered name=MuoniumPlayerBedrock ABI="+std::to_string(api_.version)+" size="+std::to_string(api_.structSize)+" module=ModMenuBE.dll");
        }
    }
    bool routingApi=registered_&&module_&&InModule(module_,reinterpret_cast<void const*>(api_.setMetadata),true)
        &&InModule(module_,reinterpret_cast<void const*>(api_.openMenu),true)
        &&InModule(module_,reinterpret_cast<void const*>(api_.isMenuOpen),true);
    if(routingApi){
        if(settingsRequested_){
            settingsRequested_=false;
            if(enabled&&route_.HideDescription()&&route_.RestoreDescription()){
                route_.Begin();routed_=false;api_.setMetadata(Name,"{}");api_.openMenu(Name,1);
                Log(folder_,"MODMENU ordinary settings opened through local3 public API");
            }
        }else if(!route_.KeepEditing(api_.isMenuOpen()!=0)&&!routed_&&route_.HideDescription()){
            api_.setMetadata(Name,"{}");routed_=true;
            Log(folder_,"MODMENU direct Configure buttons ready; player description preserved in interface");
        }
    }
    if(request_->pending.exchange(false)&&enabled)PostMessageW(window,OpenPlayerMessage,12,0);
}
void MenuBEBridge::WorkspaceStep(bool enabled){
    if(!workspace_.version){ModMenuWorkspaceApi checked{};if(!CopyWorkspaceApi(module_,checked))return;workspace_=checked;}
    if(workspace_.isReady()!=1)return;
    if(!routed_){
        // Packages contain the ordinary root descriptor. Recover test4's nested
        // sidecar only when it is absent; preserve custom root files unchanged.
        std::error_code ec;
        if(!std::filesystem::exists(folder_/L"modmenu.settings.json",ec))route_.RestoreDescription();
        if(InModule(module_,reinterpret_cast<void const*>(api_.setMetadata),true))api_.setMetadata(Name,"{}");
        routed_=true;
    }
    if(!session_){
        auto declaration=Declaration();std::uint64_t s{};
        if(workspace_.registerProvider(reinterpret_cast<void const*>(&Open),declaration.data(),uint32_t(declaration.size()),&s)!=0||!s)return;
        session_=s;revision_=0;
        Log(folder_,"MODMENU workspace registered API=1 module=ModMenuBE.dll Configure=ordinary-settings");
    }
    if(!revision_||enabled!=lastEnabled_){
        auto state=nlohmann::json{{"protocolVersion",1},{"revision",++revision_},{"controls",{
            {"availability",{{"text",enabled?Caption("Ready. Choose an interface button; opening this page does not start playback.","已就緒。請按介面按鈕；開頁不會自動播放。"):Caption("Player disabled in Configure.","播放器已在設定中停用。")}}},
            {"navigation",{{"text",Caption("Configure edits settings. Player tools has Return to Workspace. Esc and focus loss keep music and background tasks running.","Configure 修改普通設定；播放器工具提供返回工作頁。Esc 和失焦會保留音樂及背景任務。")}}}}},{"results",nlohmann::json::array()}}.dump();
        if(workspace_.publish(session_,state.data(),uint32_t(state.size()))!=0){workspace_.unregisterProvider(session_);session_=0;return;}lastEnabled_=enabled;
    }
    auto r=channel_.Read();
    if(r.session==session_){
        if(r.phase==WorkspacePhase::Requested&&(!enabled||GetTickCount64()>=r.deadline)){
            workspace_.acknowledgeExternal(session_,r.request,0);channel_.Transition(r,r.phase,WorkspacePhase::Cancelled);
        }else if(r.phase==WorkspacePhase::Created||r.phase==WorkspacePhase::Failed){
            bool ok=enabled&&r.phase==WorkspacePhase::Created&&GetTickCount64()<r.deadline;
            auto accepted=workspace_.acknowledgeExternal(session_,r.request,ok?1:0)==0;
            channel_.Transition(r,r.phase,ok&&accepted?WorkspacePhase::Accepted:WorkspacePhase::Cancelled);
            Log(folder_,ok&&accepted?"MODMENU workspace target created and acknowledged":"MODMENU workspace target refused or expired");
        }else if(r.phase==WorkspacePhase::Return){
            auto result=workspace_.returnToWorkspace(session_,r.request);
            channel_.Transition(r,r.phase,WorkspacePhase::Cancelled);
            Log(folder_,result==0?"MODMENU explicit workspace return accepted":"MODMENU workspace return refused after origin/session change");
        }else if(!enabled&&r.phase==WorkspacePhase::Accepted)channel_.Transition(r,r.phase,WorkspacePhase::Cancelled);
    }
    char output[32768]{};uint32_t required{};
    if(workspace_.poll(session_,output,sizeof output,&required)!=0||!required||required>sizeof output||output[required-1]!=0)return;
    try{
        auto c=Command(std::string(output,required-1));
        if(c.size()!=5||c.at("protocolVersion")!=1||!c.at("session").is_number_unsigned()||c.at("session").get<std::uint64_t>()!=session_
           ||!c.at("requestId").is_number_unsigned()||c.at("kind")!="openExternal"||!c.at("target").is_string())return;
        auto request=c.at("requestId").get<std::uint64_t>();auto target=c.at("target").get<std::string>();
        std::uint32_t control=target=="player"?11:target=="hud"?7:target=="presets"?8:target=="mini"?9:target=="island"?10:0;
        if(!control||!enabled){workspace_.acknowledgeExternal(session_,request,0);return;}
        if(r.session==session_&&r.request==request)return;
        channel_.Offer({session_,request,GetTickCount64()+14000,control,WorkspacePhase::Requested});
    }catch(...){Log(folder_,"MODMENU malformed workspace command refused");}
}
}
