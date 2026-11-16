#include "JvmHost.h"
#include "Player.h"
#include "Jobs.h"
#include "WorldBridge.h"
#include "CinematicBridge.h"
#include "CompositionBridge.h"
#include "WorkspaceTransport.h"
#include <bcrypt.h>
#include <array>
#include <fstream>
#include <memory>
#include <vector>

namespace mp {
namespace {
class Handle {
    HANDLE value_{};
public:
    Handle()=default;
    explicit Handle(HANDLE value):value_(value){}
    ~Handle(){reset();}
    Handle(Handle const&)=delete;
    Handle& operator=(Handle const&)=delete;
    HANDLE get()const{return value_;}
    void reset(HANDLE value=nullptr){if(value_&&value_!=INVALID_HANDLE_VALUE)CloseHandle(value_);value_=value;}
};
struct JvmApp {
    std::atomic<bool> const* stopRequested{};
    HINSTANCE module{};
    HWND owner{}, window{};
    std::filesystem::path folder;
    Config config;
    std::wstring lastConfig;
    std::unique_ptr<Jobs> menu;
    std::array<Handle,14> controls;
    Handle job, child;
    WorkspaceTransport workspace;
    WorkspaceRequest workspaceIssued;
    std::array<modmenu::binding::Tracker,8> bindings{};
    bool ready{}, smoke{};
    int result{};
    ULONGLONG started{}, readyAt{}, reloadAt{};

    void Reload(){
        try{
            auto path=folder/L"MuoniumPlayerBedrock.json";
            if(std::filesystem::file_size(path)>65536)return;
            std::ifstream input(path,std::ios::binary);
            std::string text((std::istreambuf_iterator<char>(input)),{});
            if(!input.bad())config=ParseConfig(text);
            if(menu)menu->Enabled(config.enabled);
        }catch(...){/* Preserve the last valid complete config. */}
    }
    bool Start(bool open){
        if(child.get()) {if(open)SetEvent(controls[0].get());return true;}
        CloseWorldBridge();
        auto executable=folder/L"runtime"/L"bin"/L"javaw.exe";
        auto libs=folder/L"jvm"/L"lib";
        if(!std::filesystem::is_regular_file(executable)||!std::filesystem::is_directory(libs)){
            Log(folder,"JVM REFUSED bundled runtime or upstream libraries missing");return false;
        }
        std::array<unsigned char,16> random{};
        if(BCryptGenRandom(nullptr,random.data(),static_cast<ULONG>(random.size()),BCRYPT_USE_SYSTEM_PREFERRED_RNG)<0)return false;
        std::wstring prefix=L"Local\\MuoniumBedrock-";
        constexpr wchar_t hex[]=L"0123456789abcdef";
        for(auto byte:random){prefix+=hex[byte>>4];prefix+=hex[byte&15];}
        for(std::size_t i=0;i<controls.size();++i){
            auto name=prefix+L"-"+std::to_wstring(i);
            controls[i].reset(CreateEventW(nullptr,FALSE,FALSE,name.c_str()));
            if(!controls[i].get()||GetLastError()==ERROR_ALREADY_EXISTS)return false;
        }
        if(!workspace.Open(prefix)){Log(folder,"JVM REFUSED private workspace transport unavailable");return false;}
        if(!OpenWorldBridge(prefix,folder,smoke)){Log(folder,"JVM REFUSED private world transport unavailable");return false;}
        SetCinematicOwner(owner);
        if(!OpenCompositionBridge(prefix,folder,owner,smoke))Log(folder,"COMPOSITION transport unavailable; external renderer retained");
        auto state=folder/L"player-state";
        std::filesystem::create_directories(state/L"temp");
        std::filesystem::create_directories(folder/L"logs");
        std::vector<std::wstring> arguments{executable.wstring(),L"-Xmx768m",
            L"-XX:ErrorFile="+(folder/L"logs"/L"jvm-fatal-%p.log").wstring(),L"-Dfile.encoding=UTF-8",
            L"-Dstdout.encoding=UTF-8",L"-Dstderr.encoding=UTF-8",L"-Duser.home="+state.wstring(),
            L"-Djava.io.tmpdir="+(state/L"temp").wstring(),L"-Dmuonium.skija.autoDownload=false",
            L"-cp",(libs/L"*").wstring(),L"com.muoniumplayer.bedrock.HostMain",L"--bedrock-host",
            L"--parent-pid",std::to_wstring(GetCurrentProcessId()),L"--owner",std::to_wstring(reinterpret_cast<UINT_PTR>(owner)),
            L"--control-prefix",prefix,L"--config",(folder/L"MuoniumPlayerBedrock.json").wstring()};
        if(smoke)arguments.insert(arguments.begin()+1,L"-Dmuonium.bedrock.offlineTest=true");
        std::wstring command;
        for(auto const& argument:arguments){if(!command.empty())command+=L' ';command+=QuoteJavaArg(argument);}
        SECURITY_ATTRIBUTES security{sizeof security,nullptr,TRUE};
        auto logPath=folder/L"logs"/(L"jvm-private-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64())+L".log");
        Handle log(CreateFileW(logPath.c_str(),GENERIC_WRITE,FILE_SHARE_READ,&security,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr));
        Handle input(CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,nullptr));
        if(log.get()==INVALID_HANDLE_VALUE||input.get()==INVALID_HANDLE_VALUE)return false;
        job.reset(CreateJobObjectW(nullptr,nullptr));
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        if(!job.get()||!SetInformationJobObject(job.get(),JobObjectExtendedLimitInformation,&limits,sizeof limits))return false;
        SIZE_T attributeBytes{};
        InitializeProcThreadAttributeList(nullptr,1,0,&attributeBytes);
        std::vector<unsigned char> storage(attributeBytes);
        auto attributes=reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(storage.data());
        if(!InitializeProcThreadAttributeList(attributes,1,0,&attributeBytes))return false;
        HANDLE inherited[]{log.get(),input.get()};
        bool ok=UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,inherited,sizeof inherited,nullptr,nullptr)!=FALSE;
        STARTUPINFOEXW startup{};startup.StartupInfo.cb=sizeof startup;startup.lpAttributeList=attributes;
        startup.StartupInfo.dwFlags=STARTF_USESTDHANDLES;
        startup.StartupInfo.hStdInput=input.get();startup.StartupInfo.hStdOutput=log.get();startup.StartupInfo.hStdError=log.get();
        PROCESS_INFORMATION process{};
        if(ok)ok=CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,TRUE,
            CREATE_SUSPENDED|CREATE_NO_WINDOW|EXTENDED_STARTUPINFO_PRESENT,nullptr,state.c_str(),&startup.StartupInfo,&process)!=FALSE;
        DeleteProcThreadAttributeList(attributes);
        if(!ok){Log(folder,"JVM REFUSED companion process creation failed");return false;}
        Handle primaryThread(process.hThread);child.reset(process.hProcess);
        if(!AssignProcessToJobObject(job.get(),child.get())){
            TerminateProcess(child.get(),1);child.reset();job.reset();Log(folder,"JVM REFUSED lifetime job unavailable");return false;
        }
        if(ResumeThread(primaryThread.get())==static_cast<DWORD>(-1)){job.reset();child.reset();return false;}
        started=GetTickCount64();ready=false;
        if(open)SetEvent(controls[0].get());
        Log(folder,"JVM START upstream player in owned companion; optional read-only camera bridge, no world writes");
        EnableWorldBridge(config.enabled);
        return true;
    }
    void Stop(){
        if(child.get()){
            SetEvent(controls[4].get());
            if(WaitForSingleObject(child.get(),3000)==WAIT_TIMEOUT)Log(folder,"JVM STOP companion timeout; closing lifetime job");
            job.reset();child.reset();
        }
        CloseWorldBridge();
        workspace.Close();
    }
    void Tick(){
        if(stopRequested&&stopRequested->load()){DestroyWindow(window);return;}
        auto now=GetTickCount64();
        if(!IsWindow(owner)){DestroyWindow(window);return;}
        if(now-reloadAt>=500){reloadAt=now;Reload();}
        EnableWorldBridge(child.get()&&config.enabled);
        PollWorldBridge();
        if(child.get()){
            if(WaitForSingleObject(controls[5].get(),0)==WAIT_OBJECT_0){ready=true;readyAt=now;Log(folder,"JVM READY upstream UI and lyric renderers initialized; camera admission reported separately");}
            if(WaitForSingleObject(child.get(),0)==WAIT_OBJECT_0){
                DWORD code{};GetExitCodeProcess(child.get(),&code);
                Log(folder,"JVM EXIT code="+std::to_string(code));result=smoke?(!ready||code!=0):0;
                child.reset();job.reset();CloseWorldBridge();if(smoke){DestroyWindow(window);return;}
            }else if(!ready&&now-started>60000){result=1;Stop();Log(folder,"JVM REFUSED initialization timeout");if(smoke)DestroyWindow(window);}
            else if(smoke&&ready&&now-readyAt>750)SetEvent(controls[4].get());
        }
        if(menu&&controls[13].get()&&WaitForSingleObject(controls[13].get(),0)==WAIT_OBJECT_0)menu->OpenSettings();
        if(menu){
            auto r=menu->Workspace().Read();
            if(r.phase==WorkspacePhase::Requested){
                bool issued=workspaceIssued.session==r.session&&workspaceIssued.request==r.request;
                if(!config.enabled||now>=r.deadline||(issued&&!child.get())||(!issued&&!Start(false)))menu->Workspace().Transition(r,r.phase,WorkspacePhase::Failed);
                workspaceIssued=r;
            }
            if(child.get())workspace.Exchange(menu->Workspace());
            else if(r.phase==WorkspacePhase::Accepted)menu->Workspace().Transition(r,r.phase,WorkspacePhase::Cancelled);
        }
        if(smoke)return;
        bool eligible=GetForegroundWindow()==owner;
        std::array<std::string,8> keys{config.openKey,config.playPauseKey,config.nextKey,config.previousKey,config.speakerKey,config.miniPlayerKey,config.hudEditorKey,config.islandSearchKey};
        for(std::size_t i=0;i<keys.size();++i){
            auto binding=modmenu::binding::ParseInput(keys[i]).value_or(modmenu::binding::Binding{});
            auto sample=modmenu::binding::Sample(binding,[](unsigned k){return (GetAsyncKeyState(static_cast<int>(k))&0x8000)!=0;});
            if(bindings[i].Update(binding,sample,eligible&&config.enabled).pressed){
                if(i==0)Start(true);else if(i>=5){if(Start(false))SetEvent(controls[i==5?9:i==6?7:10].get());}
                else if(child.get()&&ready&&(i!=4||config.speakerEnabled))SetEvent(controls[i==4?6:i].get());
            }
        }
    }
    static LRESULT CALLBACK Proc(HWND window,UINT message,WPARAM w,LPARAM l){
        auto self=reinterpret_cast<JvmApp*>(GetWindowLongPtrW(window,GWLP_USERDATA));
        if(message==WM_NCCREATE){self=static_cast<JvmApp*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);SetWindowLongPtrW(window,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));self->window=window;}
        if(self)try{
            if(message==WM_TIMER){self->Tick();return 0;}
            if(message==OpenPlayerMessage){if(self->config.enabled){if(w>=7&&w<=12){if(self->Start(false))SetEvent(self->controls[w].get());}else self->Start(true);}return 0;}
            if(message==WM_DESTROY){KillTimer(window,1);PostQuitMessage(0);return 0;}
        }catch(...){Log(self->folder,"JVM action refused after exception");}
        return DefWindowProcW(window,message,w,l);
    }
};
}
int RunJvmPlayer(HINSTANCE module,HWND owner,std::filesystem::path const& folder,bool offlineSmoke,std::atomic<bool> const* stopRequested){
    JvmApp app;app.module=module;app.owner=owner;app.folder=folder;app.smoke=offlineSmoke;app.stopRequested=stopRequested;
    WNDCLASSW wc{};wc.hInstance=module;wc.lpfnWndProc=JvmApp::Proc;wc.lpszClassName=L"MuoniumPlayerBedrock.Jvm.030";
    if(!RegisterClassW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)return 1;
    auto window=CreateWindowExW(0,wc.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,nullptr,module,&app);
    if(!window)return 2;
    if(!EnsurePlayerConfig(folder))Log(folder,"CONFIG unavailable: defaults could not be created; existing settings were not changed");
    app.Reload();
    if(!offlineSmoke){app.menu=std::make_unique<Jobs>(window,folder);app.menu->Enabled(app.config.enabled);}
    if(offlineSmoke&&!app.Start(false)){DestroyWindow(window);return 3;}
    SetTimer(window,1,20,nullptr);
    Log(folder,"READY native bridge; Java starts on explicit Open; shortcut defaults NONE");
    MSG message{};while(GetMessageW(&message,nullptr,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}
    app.menu.reset();app.Stop();return app.result;
}
}
