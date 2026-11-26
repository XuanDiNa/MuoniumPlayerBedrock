#include "Runtime.h"
#include "NativeContracts.h"
#include <span>
#include "WorldBridge.h"
#include "GameMetrics.h"
#include "EnvironmentBridge.h"
#include "DepthBridge.h"
#include "CompositionBridge.h"
#include "CinematicBridge.h"
#include "CinematicActorRead.h"
#include <MinHook.h>
#include <atomic>
#include <array>
#include <cmath>
#include <cstring>
#include <chrono>
#include <mutex>
namespace mp {
namespace {
NativeContract const* native{};
struct Guard {std::uint32_t rva; std::array<unsigned char,32> bytes;};
#include "WorldGuards.inl"
#include "WorldGuards2660.inl"
#include "WorldGuards2645.inl"
#include "WorldGuards2651.inl"
// Reuse the existing independently verified per-image player read contracts.
#include "CinematicGuards.inl"
#include "CinematicGuards2660.inl"
#include "CinematicGuards2645.inl"
#include "CinematicGuards2651.inl"
using PreRender=void(*)(void*,void*,void*);
PreRender original{};
std::uintptr_t base{}, textLo{},textHi{},dataLo{},dataHi{};
std::atomic<bool> enabled{};
std::mutex transport;
HANDLE mapping{},gate{},frameReady{};WorldPacket* shared{};
bool hooked{},offline{},attempted{},wasValid{};
std::filesystem::path logFolder;
std::uint64_t sequence{},epoch{1},lastFrame{},loggedAt{},captures{},rejected{},busy{};
std::uintptr_t lastLevel{};int lastDimension{};
std::uintptr_t networkOwner{};
std::uint64_t networkSelectedAt{};
GameFrameCounter frameCounter;
HANDLE voiceMapping{},voiceGate{};VoiceWorldPacket* voiceShared{};
bool voiceVerified{},voiceValid{};std::uint64_t voiceEpoch{1},voiceSequence{},voiceAt{},voiceCaptures{},voiceRejected{};
std::uintptr_t voiceLevel{},voiceActor{};std::uint32_t voiceToken{};int voiceDimension{};
VoiceWorldPacket voiceLast;
void CloseVoiceWorld(){
    if(voiceShared)UnmapViewOfFile(voiceShared);voiceShared=nullptr;
    if(voiceGate)CloseHandle(voiceGate);if(voiceMapping)CloseHandle(voiceMapping);voiceGate=voiceMapping=nullptr;
}
bool OpenVoiceWorld(std::wstring const& prefix){
    voiceMapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(VoiceWorldPacket),(prefix+L"-voice-world").c_str());
    if(!voiceMapping||GetLastError()==ERROR_ALREADY_EXISTS){if(voiceMapping)CloseHandle(voiceMapping);voiceMapping=nullptr;return false;}
    voiceGate=CreateMutexW(nullptr,FALSE,(prefix+L"-voice-world-lock").c_str());
    if(!voiceGate||GetLastError()==ERROR_ALREADY_EXISTS){CloseVoiceWorld();return false;}
    voiceShared=static_cast<VoiceWorldPacket*>(MapViewOfFile(voiceMapping,FILE_MAP_WRITE,0,0,sizeof(VoiceWorldPacket)));
    if(!voiceShared){CloseVoiceWorld();return false;}*voiceShared=VoiceWorldPacket{};return true;
}
void PublishVoice(VoiceWorldPacket p){
    if(!voiceShared||!voiceGate)return;auto status=WaitForSingleObject(voiceGate,0);
    if(status!=WAIT_OBJECT_0&&status!=WAIT_ABANDONED)return;
    p.sequence=++voiceSequence;p.at=GetTickCount64();p.epoch=voiceEpoch;*voiceShared=p;ReleaseMutex(voiceGate);
}
void ClearVoiceWorld(){if(voiceValid)++voiceEpoch;voiceValid=false;voiceLast={};voiceAt=0;PublishVoice({});}

template<class T>T Read(void const* p,std::size_t off=0){T v;std::memcpy(&v,static_cast<std::byte const*>(p)+off,sizeof v);return v;}
bool Table(void* object,std::uintptr_t rva){return object && Read<std::uintptr_t>(object)==base+rva;}
void* Virtual(void* object,std::size_t offset){
    if(!object)return nullptr;auto table=Read<std::uintptr_t>(object);
    if(table<dataLo||table+offset+8>dataHi)return nullptr;
    auto fn=Read<std::uintptr_t>(reinterpret_cast<void*>(table),offset);
    return fn>=textLo&&fn<textHi?reinterpret_cast<void*>(fn):nullptr;
}
bool Hud(void* client){
    if(!native||!Table(client,native->clientTable))return false;
    if(native->version==GameVersion::V2660)
        return reinterpret_cast<bool(*)(void*)>(base+native->hudPredicate)(client);
    if(!Virtual(client,native->hudVirtual))return false;
    if(!reinterpret_cast<bool(*)(void*)>(base+native->hudPredicate)(client))return false;
    auto stack=Read<void*>(client,native->screenStack);if(!stack)return false;
    auto validity=Read<unsigned char*>(stack,8);if(!validity||*validity!=1)return false;
    auto top=Virtual(stack,424);if(!top)return false;
    auto scene=reinterpret_cast<void*(*)(void*)>(top)(stack);
    auto match=Virtual(scene,456);if(!match)return false;
    struct Text {char const* data;std::size_t size;} name{"hud_screen",10};
    return reinterpret_cast<bool(*)(void*,Text const*)>(match)(scene,&name);
}
bool Matrix(void* stack,float* out){
    auto map=Read<std::uintptr_t*>(stack,8);auto size=Read<std::size_t>(stack,16);
    auto off=Read<std::size_t>(stack,24);auto count=Read<std::size_t>(stack,32);
    if(!map||size<2||size>4096||(size&(size-1))||!count||count>size||off>SIZE_MAX-count)return false;
    auto p=map[(off+count-1)&(size-1)];if(!p)return false;
    std::memcpy(out,reinterpret_cast<void*>(p),64);return true;
}
bool RenderView(void* context,float* out) noexcept {
    // Exact base pre-render first copies ScreenContext+24's camera, then
    // subtracts the render origin from its copy. Own the unmodified view before
    // forwarding; no game object/pointer survives across the original call.
    __try{return context&&Matrix(Read<void*>(context,24),out);}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool Capture(void* renderer,WorldPacket& p,std::uintptr_t& levelId,std::uintptr_t& owner,bool& primary,void*& region,void*& owningClient) noexcept {
    __try {
        auto levelRenderer=Read<void*>(renderer,native->rendererOwner);if(!levelRenderer||Read<void*>(levelRenderer,native->rendererPlayer)!=renderer)return false;
        auto client=Read<void*>(levelRenderer,native->rendererClient);
        if(!Table(client,native->clientTable)||Read<void*>(client,native->clientRenderer)!=levelRenderer)return false;
        primary=true;
        auto level=reinterpret_cast<void*(*)(void*)>(base+native->getLevel)(client);
        auto source=reinterpret_cast<void*(*)(void*)>(base+native->getRegion)(client);
        if(!Table(level,native->levelTable)||!Table(source,native->sourceTable)||Read<void*>(source,32)!=level)return false;
        // ClientInstance owns this game context, distinct from its Level.
        // Exact game tick resolves the same tagged NetworkSystem subobject.
        auto game=Read<void*>(client,native->clientGame);
        if(game){auto raw=Read<std::uintptr_t>(game,native->gameNetwork);auto tag=Read<unsigned char>(game,native->gameNetworkTag);
            if(raw&&tag<=1&&raw<UINTPTR_MAX-0x20)owner=raw+(tag?0x18:0x20);}
        auto dimension=Read<void*>(source,48);if(!Virtual(dimension,16))return false;
        int dim=INT_MIN;reinterpret_cast<void*(*)(void*,int*)>(base+native->sourceDimension)(source,&dim);
        if(dim==INT_MIN)return false;
        region=source;owningClient=client;p.dimension=dim;levelId=reinterpret_cast<std::uintptr_t>(level);
        if(!Hud(client))return false;
        auto camera=static_cast<std::byte*>(renderer)+native->camera;
        if(!Matrix(camera,p.view)||!Matrix(camera+0x80,p.projection))return false;
        for(int i=0;i<3;++i){p.eye[i]=Read<float>(camera,0x124+4*i);p.look[i]=Read<float>(camera,0x118+4*i);}
        p.fov=2*std::atan(1./std::abs(p.projection[5]))*180/3.141592653589793;
        region=source;owningClient=client;p.dimension=dim;levelId=reinterpret_cast<std::uintptr_t>(level);
        return ValidWorldCamera(p);
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool VoiceCopy(void*,std::uintptr_t address,void* out,std::size_t bytes){
    __try{if(address<65536||address>0x7fffffffffff-bytes)return false;std::memcpy(out,reinterpret_cast<void*>(address),bytes);return true;}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool CaptureVoice(void* client,VoiceWorldPacket& p,std::uintptr_t& actorId,std::uint32_t& token) noexcept {
    __try{
        if(!native||!Table(client,native->clientTable))return false;
        auto actor=reinterpret_cast<void*(*)(void*)>(base+native->getPlayer)(client);
        if(!Table(actor,native->playerTable)||Read<unsigned char>(actor,native->actorRemoved))return false;
        CinematicState state;
        if(!ReadCinematicActor(VoiceCopy,nullptr,reinterpret_cast<std::uintptr_t>(actor),*native,state))return false;
        auto bounds=Read<void*>(actor,native->actorAabb);auto top=Read<float>(bounds,16);
        if(!std::isfinite(top)||top<state.foot[1]||top-state.foot[1]>16)return false;
        // Player-head position from the verified AABB, independent of free camera.
        p.position[0]=state.foot[0];p.position[1]=std::max(state.foot[1],double(top)-.1);p.position[2]=state.foot[2];
        auto yaw=state.yaw*3.141592653589793/180,pitch=state.pitch*3.141592653589793/180;
        p.look[0]=-std::sin(yaw)*std::cos(pitch);p.look[1]=-std::sin(pitch);p.look[2]=std::cos(yaw)*std::cos(pitch);
        actorId=reinterpret_cast<std::uintptr_t>(actor);token=Read<std::uint32_t>(actor,24);return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
void TrackVoice(VoiceWorldPacket p,bool valid,std::uintptr_t level,std::uintptr_t actor,std::uint32_t token,std::uint64_t now){
    if(!valid){++voiceRejected;ClearVoiceWorld();return;}
    if(!voiceValid||voiceLevel!=level||voiceActor!=actor||voiceToken!=token||voiceDimension!=p.dimension||now-voiceAt>350)++voiceEpoch;
    voiceLevel=level;voiceActor=actor;voiceToken=token;voiceDimension=p.dimension;voiceAt=now;voiceValid=true;
    p.valid=1;p.epoch=voiceEpoch;voiceLast=p;++voiceCaptures;PublishVoice(p);
}
void Publish(WorldPacket p){
    if(!shared||!gate)return;
    auto result=WaitForSingleObject(gate,0);
    if(result!=WAIT_OBJECT_0&&result!=WAIT_ABANDONED){++busy;return;}
    p.sequence=++sequence;p.at=GetTickCount64();p.epoch=epoch;
    std::memcpy(shared,&p,sizeof p);ReleaseMutex(gate);
    if(frameReady)SetEvent(frameReady);
}
void Detour(void* self,void* context,void* params){
    float renderView[16]{};
    bool paired=enabled.load(std::memory_order_relaxed)&&CopyRenderViewForDepth(context,renderView);
    // Only the exact derived player path is hooked. Preserve all original work.
    original(self,context,params);
    if(!enabled.load(std::memory_order_relaxed))return;
    std::unique_lock lock(transport,std::try_to_lock);if(!lock.owns_lock()||!shared)return;
    WorldPacket p;std::uintptr_t level{},owner{};bool primary{};auto now=GetTickCount64();
    void* source{},*client{};bool valid=Capture(self,p,level,owner,primary,source,client);
    if(!primary)return;
    networkOwner=owner;networkSelectedAt=now;SelectNetworkMetrics(owner);
    if(valid){
        if(!wasValid||lastLevel!=level||lastDimension!=p.dimension||now-lastFrame>250){++epoch;frameCounter.reset();}
        lastLevel=level;lastDimension=p.dimension;lastFrame=now;wasValid=true;p.valid=1;++captures;
        try{p.fps=frameCounter.frame(std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count());}
        catch(...){frameCounter.reset();} // Optional metrics cannot abort a game frame on allocation failure.
        auto metric=ReadNetworkMetrics(now,owner);p.ping=metric.ping;p.connectionKind=metric.kind;p.metricsAt=metric.at;p.networkEpoch=metric.epoch;
    }else{if(wasValid)++epoch;wasValid=false;frameCounter.reset();++rejected;}
    p.at=now;p.epoch=epoch;p.sequence=captures;DepthCamera(p,paired?renderView:nullptr);
    CompositionCamera(p,paired?renderView:nullptr);
    // At most 50Hz; the rendering hook and its camera ABI remain unchanged.
    if(voiceVerified&&client&&source){
        if(now-voiceAt>=20||!voiceValid){VoiceWorldPacket pose;std::uintptr_t actor{};std::uint32_t token{};
            pose.dimension=p.dimension;pose.geometryEpoch=epoch;
            bool ok=CaptureVoice(client,pose,actor,token);TrackVoice(pose,ok,level,actor,token,now);}
    }else if(voiceValid)ClearVoiceWorld();
    if(valid)CaptureEnvironment(source,client,p,epoch,now);
    else if(voiceValid&&now-voiceAt<=350){
        WorldPacket voiceScene;voiceScene.dimension=voiceLast.dimension;
        for(int i=0;i<3;i++){voiceScene.eye[i]=voiceLast.position[i];voiceScene.look[i]=voiceLast.look[i];}
        CaptureEnvironment(source,client,voiceScene,epoch,now);
    }else ClearEnvironment();
    Publish(p);
}
bool Install() noexcept {
    __try {
        base=reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));
        native=LoadedNativeContract(base);if(!native)return false;
        auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(base);if(dos->e_magic!=IMAGE_DOS_SIGNATURE)return false;
        auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(base+dos->e_lfanew);
        if(nt->Signature!=IMAGE_NT_SIGNATURE)return false;
        auto section=IMAGE_FIRST_SECTION(nt);
        for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i){
            if(!std::memcmp(section[i].Name,".text",5)){textLo=base+section[i].VirtualAddress;textHi=textLo+section[i].Misc.VirtualSize;}
            if(!std::memcmp(section[i].Name,".rdata",6)){dataLo=base+section[i].VirtualAddress;dataHi=dataLo+section[i].Misc.VirtualSize;}
        }
        if(!textLo||!dataLo)return false;
        for(auto const& g:VersionGuards(native->version,guards2645,guards2651,guards,guards2660))if(std::memcmp(reinterpret_cast<void*>(base+g.rva),g.bytes.data(),g.bytes.size()))return false;
        auto init=MH_Initialize();if(init!=MH_OK&&init!=MH_ERROR_ALREADY_INITIALIZED)return false;
        auto target=reinterpret_cast<void*>(base+native->preRender);
        if(MH_CreateHook(target,reinterpret_cast<void*>(&Detour),reinterpret_cast<void**>(&original))!=MH_OK)return false;
        if(MH_EnableHook(target)!=MH_OK){MH_RemoveHook(target);return false;}
        return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
}
bool CopyRenderViewForDepth(void* context,float* out) noexcept {return RenderView(context,out);}
bool CinematicHud(void* client) noexcept {__try{return base&&Hud(client);}__except(EXCEPTION_EXECUTE_HANDLER){return false;}}
bool OpenWorldBridge(std::wstring const& prefix,std::filesystem::path const& folder,bool test){
    std::lock_guard lock(transport);if(shared)return false;
    logFolder=folder;offline=test;wasValid=false;lastFrame=0;++epoch;
    mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(WorldPacket),(prefix+L"-world").c_str());
    if(!mapping||GetLastError()==ERROR_ALREADY_EXISTS){if(mapping)CloseHandle(mapping);mapping=nullptr;return false;}
    gate=CreateMutexW(nullptr,FALSE,(prefix+L"-world-lock").c_str());
    if(!gate||GetLastError()==ERROR_ALREADY_EXISTS){if(gate)CloseHandle(gate);CloseHandle(mapping);gate=mapping=nullptr;return false;}
    shared=static_cast<WorldPacket*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,sizeof(WorldPacket)));
    if(!shared){CloseHandle(gate);CloseHandle(mapping);gate=mapping=nullptr;return false;}
    if(!OpenEnvironmentBridge(prefix,folder)){UnmapViewOfFile(shared);shared=nullptr;CloseHandle(gate);CloseHandle(mapping);gate=mapping=nullptr;return false;}
    OpenDepthBridge(prefix,folder,test);
    if(!OpenCinematicBridge(prefix,folder,test))Log(folder,"CINEMATIC transport unavailable; ordinary player retained");
    voiceValid=false;voiceAt=0;++voiceEpoch;
    if(!OpenVoiceWorld(prefix))Log(folder,"VOICE_WORLD transport unavailable; ordinary room voice retained");
    frameReady=CreateEventW(nullptr,FALSE,FALSE,(prefix+L"-world-frame").c_str());
    if(frameReady&&GetLastError()==ERROR_ALREADY_EXISTS){CloseHandle(frameReady);frameReady=nullptr;}
    Log(folder,frameReady?"FRAME_SYNC READY auto-reset camera publication signal":"FRAME_SYNC unavailable; host rate-target fallback retained");
    Publish({});return true;
}
void EnableWorldBridge(bool value){
    std::lock_guard lock(transport);
    if(value&&shared&&!hooked&&!attempted&&!offline){attempted=true;hooked=Install();Log(logFolder,hooked?"WORLD READY exact-version read-only player camera hook":"WORLD REFUSED camera hook guard/ownership mismatch; ordinary player remains available");
        if(hooked)Log(logFolder,VerifyEnvironmentNative(base)?"ENVIRONMENT READY exact-version bounded shape/material/light/weather/biome/time/health capture":"ENVIRONMENT REFUSED exact guard mismatch; ordinary player/camera retained");
        if(hooked)Log(logFolder,InstallNetworkMetrics(base)?"METRICS READY game-frame FPS and native current-connection RTT":"METRICS REFUSED network guards/hooks; RTT unknown; camera/player retained");}
    auto active=value&&hooked;if(!active)ClearEnvironment();
    EnableCinematicBridge(active,base);
    EnableCompositionBridge(active,base);
    // The unsuccessful 0.7.12 write-snapshot experiment remains offline only.
    // A refused compositor falls back to ordinary ULW/voxel output, not that cost.
    EnableDepthBridge(false,base);
    voiceVerified=false;
    if(active){
        VerifyEnvironmentNative(base); // Reopened companion gets fresh private mappings.
        // Read-only reuse only: do not install any cinematic hooks here.
        // Only getPlayer is a new call in this path; other context calls already
        // passed WorldGuards. Cinematic render functions may already be hooked.
        for(auto const& g:VersionGuards(native->version,cinematicGuards2645,cinematicGuards2651,cinematicGuards,cinematicGuards2660))
            if(g.rva==native->getPlayer)voiceVerified=!std::memcmp(reinterpret_cast<void*>(base+g.rva),g.bytes.data(),g.bytes.size());
    }
    if(!active)ClearVoiceWorld();
    if(enabled.exchange(active)!=active){++epoch;wasValid=false;frameCounter.reset();networkOwner=0;SelectNetworkMetrics(0);Publish({});}
}
void PollWorldBridge(){
    std::lock_guard lock(transport);auto now=GetTickCount64();
    RefreshDepthBridge();
    if(networkOwner&&now-networkSelectedAt>500){networkOwner=0;SelectNetworkMetrics(0);}
    if(!shared||now-loggedAt<5000)return;loggedAt=now;
    Log(logFolder,"VOICE_WORLD verified="+std::to_string(voiceVerified)+" ready="+std::to_string(voiceValid&&now-voiceAt<=350)
        +" captured="+std::to_string(voiceCaptures)+" rejected="+std::to_string(voiceRejected));
    LogEnvironment();
    LogDepthBridge();
    LogCompositionBridge();
    LogCinematicBridge();
    if(enabled){auto metric=ReadNetworkMetrics(now,networkOwner);auto counts=ReadMetricDiagnostics();
        Log(logFolder,"WORLD camera captured="+std::to_string(captures)+" rejected="+std::to_string(rejected)+" transportBusy="+std::to_string(busy)
            +" gameFps="+std::to_string(frameCounter.value())+" connection="+std::to_string(metric.kind)+" ping="+std::to_string(metric.ping)+" networkEpoch="+std::to_string(metric.epoch)
            +" selected="+std::to_string(networkOwner!=0)+" networkTicks="+std::to_string(counts.networkUpdates)+" clientTicks="+std::to_string(counts.selectedUpdates)
            +" peerUpdates="+std::to_string(counts.peerUpdates)+" snapshots="+std::to_string(counts.snapshots));}
}
void CloseWorldBridge(){
    enabled=false;std::lock_guard lock(transport);++epoch;wasValid=false;frameCounter.reset();networkOwner=0;SelectNetworkMetrics(0);Publish({});
    ClearVoiceWorld();CloseVoiceWorld();voiceVerified=false;
    CloseEnvironmentBridge();
    CloseDepthBridge();
    CloseCompositionBridge();
    CloseCinematicBridge();
    if(frameReady)CloseHandle(frameReady);frameReady=nullptr;
    if(shared)UnmapViewOfFile(shared);shared=nullptr;
    if(gate)CloseHandle(gate);if(mapping)CloseHandle(mapping);gate=mapping=nullptr;
    // DLL is pinned. Leave this one dormant detour forwarder in place; never
    // tear down another plugin's MinHook state or race an in-flight callback.
}
}
