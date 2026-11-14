#include "CinematicBridge.h"
#include "CinematicTrace.h"
#include "Runtime.h"
#include "NativeContracts.h"
#include <span>
#include "EnvironmentRead.h"
#include <MinHook.h>
#include <atomic>
#include <mutex>
#include <cstring>
#include <cmath>
#include <cstdio>
#include <limits>
namespace mp {
namespace {
NativeContract const* native{};
struct Guard{std::uint32_t rva;std::array<unsigned char,32> bytes;};
#include "CinematicGuards.inl"
#include "CinematicGuards2660.inl"
#include "CinematicGuards2645.inl"
#include "CinematicGuards2651.inl"
struct Transport{CinematicCommand command;CinematicState state;};
static_assert(sizeof(Transport)==200);
HANDLE mapping{},gate{},request{};Transport* shared{};std::mutex mutex,logMutex;
std::filesystem::path folder;bool offline{},attempted{},installed{};std::atomic<bool> enabled{},faulted{};
std::atomic<HWND> window{};std::uintptr_t base{};
std::atomic<std::uint64_t> scopes{},applied{},refused{},conflicts{},eligibleSamples{},validCommands{};
std::atomic<std::uint64_t> attempts{},lifetimeBusy{},ipcBusy{},cachedApplied{},concurrentBusy{};
// Protected by the native lifetime mutex and rendererBusy; owns no game pointer.
CinematicCommandCache commandCache;
CinematicTrace continuity;
std::atomic<std::uint64_t> hideUntil{};std::atomic<std::uintptr_t> hideClient{};std::atomic<DWORD> hideInput{};
std::atomic<std::uint64_t> hudCalls{},hudHidden{};
std::uint64_t epoch{1},sequence{},lastSeen{},inputSerial{1};
std::uintptr_t lastLevel{},lastActor{};std::uint32_t lastToken{},lastInput{};int lastDimension{};
CinematicState lastState{};bool wasEligible{},heldInput{};std::uint64_t lastHeldPoll{};
using Frame=std::uintptr_t(*)(void*,void*,void*,void*);
using Render=std::uintptr_t(*)(void*,void*,void*);
using Body=bool(*)(void*);
using Hand=std::uintptr_t(*)(void*,void*,void*);
using Save=std::uintptr_t(*)(void*,void*,void*);
using Screen=std::uintptr_t(*)(void*,void*);
Frame originalFrame{};Render originalRender{};Body originalBody{};Hand originalHand{};Save originalSave{};
Screen originalScreen{};
struct Scope{void* client{};void* component{};void* registry{};std::uint32_t token{};CinematicPatch patch;bool active{};float vanillaFov{};CinematicTraceSample trace;};
thread_local Scope current;
std::atomic_flag rendererBusy=ATOMIC_FLAG_INIT;
template<class T>T Read(void const* ptr,std::size_t offset=0){T out{};std::memcpy(&out,static_cast<std::byte const*>(ptr)+offset,sizeof out);return out;}
bool Copy(void*,std::uintptr_t p,void* out,std::size_t bytes){
    __try{if(p<65536||p>0x7fffffffffff-bytes)return false;std::memcpy(out,reinterpret_cast<void*>(p),bytes);return true;}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
// Read-only lookup. Missing and invalid storage are distinguished; no native
// get-or-create storage function is called by this probe.
bool Member(void* registry,std::uint32_t token,std::uint32_t key,std::size_t stride,void*& value) {
    std::uintptr_t address{};bool ok=CinematicMember(Copy,nullptr,reinterpret_cast<std::uintptr_t>(registry),token,key,stride,address);
    value=reinterpret_cast<void*>(address);return ok;
}

bool Resolve(void* client,void*& component,void*& actor,void*& level,int& dimension,CinematicState& state) noexcept {
    __try{
        if(!client||Read<std::uintptr_t>(client)!=base+native->clientTable)return false;
        level=reinterpret_cast<void*(*)(void*)>(base+native->getLevel)(client);
        actor=reinterpret_cast<void*(*)(void*)>(base+native->getPlayer)(client);
        auto source=reinterpret_cast<void*(*)(void*)>(base+native->getRegion)(client);
        if(!level||Read<std::uintptr_t>(level)!=base+native->levelTable||!actor||Read<std::uintptr_t>(actor)!=base+native->playerTable
            ||Read<unsigned char>(actor,native->actorRemoved)||!source||Read<void*>(source,32)!=level)return false;
        dimension=INT_MIN;reinterpret_cast<void*(*)(void*,int*)>(base+native->sourceDimension)(source,&dimension);if(dimension==INT_MIN)return false;
        alignas(8) std::byte context[32]{};
        reinterpret_cast<void*(*)(void*,void*)>(base+native->renderContext)(context,client);
        auto registry=Read<void*>(context,8);auto token=Read<std::uint32_t>(context,16);void* found{};
        if(Read<unsigned char>(context,24)!=1||!registry||Read<void*>(actor,16)!=registry)return false;
        if(!Member(registry,token,0x119E772B,0,found)||!found)return false;
        auto dense=Read<std::uint32_t*>(found,32),end=Read<std::uint32_t*>(found,40);
        // Split-screen/multiple render cameras are deliberately not eligible.
        if(!dense||end!=dense+1||*dense!=token)return false;
        if(!Member(registry,token,0x113040A1,0,found)||found)return false;
        if(!Member(registry,token,0x0E49CF3B,0,found)||found)return false;
        if(!Member(registry,token,0x9EC7D9E5,0,found)||!found)return false;
        if(!Member(registry,token,0x4F6047C7,288,component)||!component)return false;
        auto box=Read<float*>(actor,native->actorAabb),rotation=Read<float*>(actor,native->actorRotation);if(!box||!rotation)return false;
        state.foot[0]=(box[0]+box[3])*.5;state.foot[1]=box[1];state.foot[2]=(box[2]+box[5])*.5;
        state.pitch=rotation[0];state.yaw=rotation[1];state.fov=Read<float>(component,80)*180/3.14159265358979323846f;
        for(int i=0;i<3;i++){state.eye[i]=Read<float>(component,64+4*i);
            if(!std::isfinite(state.eye[i])||!std::isfinite(state.foot[i])||std::abs(state.foot[i])>32000000)return false;}
        if(!std::isfinite(state.pitch)||!std::isfinite(state.yaw)||!std::isfinite(state.fov)||state.fov<10||state.fov>170)return false;
        if(!ReadEnvironmentHealth(reinterpret_cast<std::uintptr_t>(actor),base+native->healthAttribute,Copy,nullptr,state.health,native->healthStride)||state.health<=0)return false;
        return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool Focus() {auto owner=window.load();return owner&&GetForegroundWindow()==owner&&!IsIconic(owner);}
bool Scene(void* scene,void*& client) noexcept {
    __try{auto validity=Read<unsigned char*>(scene,native->sceneValidity);client=Read<void*>(scene,native->sceneClient);return validity&&*validity==1;}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool CaptureOwner(void* client,void*& registry,std::uint32_t& token) noexcept {
    __try{alignas(8) std::byte context[32]{};reinterpret_cast<void*(*)(void*,void*)>(base+native->renderContext)(context,client);
        registry=Read<void*>(context,8);token=Read<std::uint32_t>(context,16);return registry&&Read<unsigned char>(context,24)==1;
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool Restore() noexcept {
    __try{
        void* component{};
        if(!Member(current.registry,current.token,0x4F6047C7,288,component)||component!=current.component)return false;
        return current.patch.restore(component);
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool Apply(void* camera,CinematicCommand const& command) noexcept {
    __try{return current.patch.apply(camera,command);}
    __except(EXCEPTION_EXECUTE_HANDLER){Restore();faulted=true;return false;}
}
void TraceCamera(CinematicTraceSample sample,void* camera) noexcept {
    // Existing, exact scalar layout only. No new game lookup or camera write.
    if(sample.applied){
        float radians{};
        if(!Copy(nullptr,reinterpret_cast<std::uintptr_t>(camera)+80,&radians,4)
            ||!Copy(nullptr,reinterpret_cast<std::uintptr_t>(camera)+76,&sample.aspect,4))sample.renderFov=std::numeric_limits<float>::quiet_NaN();
        else sample.renderFov=radians*180/3.14159265358979323846f;
    }
    continuity.observe(sample);
}
bool Begin(void* scene,CinematicTracePhase phase) noexcept {
    if(current.client||!enabled||faulted)return false;
    ++attempts;
    // Borrow the event/mapping only while holding the lifetime mutex. Closing
    // the host can never race a WaitForSingleObject on a recycled HANDLE.
    std::unique_lock lock(mutex,std::try_to_lock);
    if(!lock.owns_lock()){++lifetimeBusy;return false;}
    if(!enabled||!shared||!request||WaitForSingleObject(request,0)!=WAIT_OBJECT_0){commandCache.clear();return false;}
    if(rendererBusy.test_and_set()){++concurrentBusy;return false;}
        void* client{};
        if(!Scene(scene,client)){rendererBusy.clear();return false;}
        CinematicState state;void* camera{},*actor{},*level{};int dimension{};auto now=GetTickCount64();
        bool eligible=Resolve(client,camera,actor,level,dimension,state)&&Focus()&&CinematicHud(client);
        std::uint32_t token{};float vanillaFov{};
        if(actor&&!Copy(nullptr,reinterpret_cast<std::uintptr_t>(actor)+24,&token,4))eligible=false;
        if(camera&&!Copy(nullptr,reinterpret_cast<std::uintptr_t>(camera)+80,&vanillaFov,4))eligible=false;
        if(!eligible||lastLevel!=reinterpret_cast<std::uintptr_t>(level)||lastActor!=reinterpret_cast<std::uintptr_t>(actor)
            ||lastToken!=token||lastDimension!=dimension||now-lastSeen>250){if(wasEligible||eligible)++epoch;lastState=state;}
        lastLevel=reinterpret_cast<std::uintptr_t>(level);lastActor=reinterpret_cast<std::uintptr_t>(actor);lastToken=token;lastDimension=dimension;lastSeen=now;wasEligible=eligible;
        LASTINPUTINFO input{sizeof input};if(!GetLastInputInfo(&input))eligible=false;
        // Immediate events use GetLastInputInfo; held-key polling only needs
        // tick cadence, avoiding hundreds of Win32 calls per high-FPS frame.
        if(now-lastHeldPoll>=50){lastHeldPoll=now;heldInput=false;
            for(int key=1;key<255;key++)if(GetAsyncKeyState(key)&0x8000){heldInput=true;break;}}
        if(input.dwTime!=lastInput||heldInput||CinematicInputChanged(lastState,state)){++inputSerial;lastInput=input.dwTime;}
        lastState=state;state.sequence=++sequence;state.at=now;state.epoch=epoch;state.input=inputSerial;state.eligible=eligible;
        auto wait=WaitForSingleObject(gate,0);bool acquired=wait==WAIT_OBJECT_0||wait==WAIT_ABANDONED;
        if(!acquired)++ipcBusy;
        CinematicCommand command;
        bool valid=commandCache.select(acquired?&shared->command:nullptr,state,now,command);
        if(eligible)++eligibleSamples;
        if(valid)++validCommands;
        current.client=client;current.component=camera;current.vanillaFov=vanillaFov;
        current.registry=nullptr;current.token=0;
        if(camera&&!CaptureOwner(client,current.registry,current.token))eligible=false;
        current.active=eligible&&valid&&Apply(camera,command);
        state.applied=current.active;
        if(acquired){shared->state=state;ReleaseMutex(gate);}
        else if(current.active)++cachedApplied;
        current.trace={};current.trace.phase=phase;current.trace.at=now;current.trace.epoch=state.epoch;current.trace.input=state.input;
        current.trace.eligible=eligible;current.trace.valid=valid;current.trace.applied=current.active;current.trace.baseFov=state.fov;
        if(valid){current.trace.commandAt=command.at;current.trace.command=command.sequence;current.trace.offset=command.fovOffset;
            std::memcpy(current.trace.position,command.position,sizeof command.position);current.trace.yaw=command.yaw;current.trace.pitch=command.pitch;current.trace.roll=command.roll;}
        TraceCamera(current.trace,camera);
        hideClient=reinterpret_cast<std::uintptr_t>(client);hideInput=input.dwTime;hideUntil=current.active&&(command.flags&1)?now+100:0;
        if(current.active)++applied;else ++refused;++scopes;return true;
}
void End() noexcept {
    __try{if(current.active&&!Restore()){++conflicts;faulted=true;}}
    __except(EXCEPTION_EXECUTE_HANDLER){++conflicts;faulted=true;}
    current.client=nullptr;current.component=nullptr;current.active=false;rendererBusy.clear();
}
std::uintptr_t FrameHook(void* a,void* b,void* c,void* d){bool own=Begin(a,CinematicTracePhase::Frame);std::uintptr_t result{};
    __try{result=originalFrame(a,b,c,d);}__finally{if(own)End();}return result;}
std::uintptr_t RenderHook(void* a,void* b,void* c){bool own=Begin(a,CinematicTracePhase::Render);std::uintptr_t result{};
    __try{result=originalRender(a,b,c);}__finally{if(own)End();}return result;}
bool BodyHook(void* client){return current.active&&current.client==client?true:originalBody(client);}
std::uintptr_t HandHook(void* scene,void* context,void* renderer){
    void* client{};return current.active&&Scene(scene,client)&&client==current.client?0:originalHand(scene,context,renderer);
}
std::uintptr_t SaveHook(void* input,void* render,void* matrix){
    // Native writes input camera's saved view/projection. Keep its vanilla FOV;
    // cinematic FOV must not affect the input/picking camera.
    if(!current.active||render!=current.component)return originalSave(input,render,matrix);
    auto consumed=current.trace;consumed.phase=CinematicTracePhase::Saved;consumed.at=GetTickCount64();TraceCamera(consumed,render);
    alignas(8) std::byte scalarView[288]{};std::memcpy(scalarView+76,static_cast<std::byte*>(render)+76,16);
    std::memcpy(scalarView+80,&current.vanillaFov,4);return originalSave(input,scalarView,matrix);
}
bool HudOwner(void* scene) noexcept {
    LASTINPUTINFO input{sizeof input};if(!GetLastInputInfo(&input)||input.dwTime!=hideInput)return false;
    __try{
        if(!scene||Read<std::uintptr_t>(scene)!=base+native->sceneTable)return false;
        auto client=Read<void*>(scene,0x38),view=Read<void*>(scene,0x40);
        if(reinterpret_cast<std::uintptr_t>(client)!=hideClient||!CinematicHud(client)||!view)return false;
        auto validity=Read<unsigned char*>(scene,0x28);if(!validity||*validity!=1||Read<void*>(scene,0x38)!=client)return false;
        auto holder=Read<void*>(view,0x50);if(!holder)return false;auto meta=Read<void*>(holder,8);if(!meta)return false;
        auto length=Read<std::uint64_t>(meta,0x30),capacity=Read<std::uint64_t>(meta,0x38);
        if(length!=10||capacity<length||capacity>4095)return false;
        auto name=capacity<16?static_cast<char const*>(meta)+0x20:Read<char const*>(meta,0x20);
        return name&&!std::memcmp(name,"hud_screen",10);
    }__except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
bool HideHud(void* scene) noexcept {
    if(!enabled||faulted||GetTickCount64()>hideUntil||!Focus())return false;
    std::unique_lock lock(mutex,std::try_to_lock);
    if(!lock.owns_lock()||!enabled||!request||WaitForSingleObject(request,0)!=WAIT_OBJECT_0)return false;
    return HudOwner(scene);
}
std::uintptr_t ScreenHook(void* scene,void* screen){
    ++hudCalls;if(HideHud(scene)){++hudHidden;return 0;}return originalScreen(scene,screen);
}
bool AdmissionFailure(char const* stage,std::uint32_t rva,int status=0) noexcept {
    char text[160]{};std::snprintf(text,sizeof text,"CINEMATIC ADMISSION refused stage=%s rva=0x%08X status=%d",stage,rva,status);
    Log(folder,text);return false;
}
// Exact UIScene::render virtual slot. The pointer still names the canonical
// function even when another plugin detours its code. Forwarding to that same
// address retains those plugins; no foreign trampoline is inspected or replaced.

bool InstallSceneDispatch() noexcept {
    auto slot=reinterpret_cast<void* volatile*>(base+native->sceneSlot);
    auto expected=reinterpret_cast<void*>(base+native->sceneRender);
    if(Read<void*>(reinterpret_cast<void*>(base+native->sceneSlot))!=expected)return AdmissionFailure("scene-slot",native->sceneSlot);
    originalScreen=reinterpret_cast<Screen>(expected);DWORD old{};
    if(!VirtualProtect(reinterpret_cast<void*>(base+native->sceneSlot),8,PAGE_READWRITE,&old))return AdmissionFailure("scene-protect",native->sceneSlot,GetLastError());
    auto previous=InterlockedCompareExchangePointer(slot,reinterpret_cast<void*>(ScreenHook),expected);
    DWORD discarded{};bool protectedAgain=VirtualProtect(reinterpret_cast<void*>(base+native->sceneSlot),8,old,&discarded)!=0;
    if(previous!=expected)return AdmissionFailure("scene-slot-race",native->sceneSlot);
    if(protectedAgain)return true;
    // Roll back only the pointer we own. If another writer changed it, the
    // pinned forwarder remains safe with enabled=false; never overwrite theirs.
    InterlockedCompareExchangePointer(slot,expected,reinterpret_cast<void*>(ScreenHook));
    VirtualProtect(reinterpret_cast<void*>(base+native->sceneSlot),8,old,&discarded);
    return AdmissionFailure("scene-restore-protection",native->sceneSlot,GetLastError());
}
bool Install() noexcept {
    __try{
        native=LoadedNativeContract(base);if(!native)return AdmissionFailure("host-identity",0);
        for(auto const& guard:VersionGuards(native->version,cinematicGuards2645,cinematicGuards2651,cinematicGuards,cinematicGuards2660))if(std::memcmp(reinterpret_cast<void*>(base+guard.rva),guard.bytes.data(),32))return AdmissionFailure("entry-guard",guard.rva);
        if(Read<void*>(reinterpret_cast<void*>(base+native->sceneSlot))!=reinterpret_cast<void*>(base+native->sceneRender))return AdmissionFailure("scene-slot",native->sceneSlot);
        auto init=MH_Initialize();if(init!=MH_OK&&init!=MH_ERROR_ALREADY_INITIALIZED)return AdmissionFailure("hook-init",0,init);
        void* hooks[]{reinterpret_cast<void*>(base+native->frame),reinterpret_cast<void*>(base+native->render),reinterpret_cast<void*>(base+native->body),reinterpret_cast<void*>(base+native->hand),reinterpret_cast<void*>(base+native->saveCamera)};
        void* detours[]{reinterpret_cast<void*>(FrameHook),reinterpret_cast<void*>(RenderHook),reinterpret_cast<void*>(BodyHook),reinterpret_cast<void*>(HandHook),reinterpret_cast<void*>(SaveHook)};
        void** originals[]{reinterpret_cast<void**>(&originalFrame),reinterpret_cast<void**>(&originalRender),reinterpret_cast<void**>(&originalBody),reinterpret_cast<void**>(&originalHand),reinterpret_cast<void**>(&originalSave)};
        unsigned made=0,on=0;
        for(;made<5;made++){auto status=MH_CreateHook(hooks[made],detours[made],originals[made]);
            if(status!=MH_OK){AdmissionFailure("hook-create",static_cast<std::uint32_t>(reinterpret_cast<std::uintptr_t>(hooks[made])-base),status);break;}}
        if(made==5)for(;on<5;on++){auto status=MH_EnableHook(hooks[on]);
            if(status!=MH_OK){AdmissionFailure("hook-enable",static_cast<std::uint32_t>(reinterpret_cast<std::uintptr_t>(hooks[on])-base),status);break;}}
        if(on==5&&InstallSceneDispatch())return true;
        while(on)MH_DisableHook(hooks[--on]);while(made)MH_RemoveHook(hooks[--made]);return false;
    }__except(EXCEPTION_EXECUTE_HANDLER){return AdmissionFailure("native-exception",0,GetExceptionCode());}
}
}
bool OpenCinematicBridge(std::wstring const& prefix,std::filesystem::path const& path,bool test){
    std::lock_guard lock(mutex);if(shared)return false;
    {std::lock_guard logLock(logMutex);folder=path;}
    offline=test;commandCache.clear();
    mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(Transport),(prefix+L"-cinematic").c_str());
    if(!mapping||GetLastError()==ERROR_ALREADY_EXISTS){if(mapping)CloseHandle(mapping);mapping=nullptr;return false;}
    gate=CreateMutexW(nullptr,FALSE,(prefix+L"-cinematic-lock").c_str());
    if(!gate||GetLastError()==ERROR_ALREADY_EXISTS){if(gate)CloseHandle(gate);CloseHandle(mapping);gate=mapping=nullptr;return false;}
    shared=static_cast<Transport*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,sizeof(Transport)));
    if(!shared){CloseHandle(mapping);CloseHandle(gate);gate=mapping=nullptr;return false;}
    request=CreateEventW(nullptr,TRUE,FALSE,(prefix+L"-cinematic-active").c_str());
    if(!request||GetLastError()==ERROR_ALREADY_EXISTS){if(request)CloseHandle(request);request=nullptr;UnmapViewOfFile(shared);shared=nullptr;CloseHandle(mapping);CloseHandle(gate);gate=mapping=nullptr;return false;}
    *shared=Transport{};++epoch;wasEligible=false;return true;
}
void SetCinematicOwner(HWND owner){window=owner;}
bool EnableCinematicBridge(bool value,std::uintptr_t image){
    // Repeated host polling need not contend with an already enabled renderer.
    // First admission, fault handling and disable/close still take the lock.
    if(value&&enabled.load()&&!faulted.load())return true;
    std::lock_guard lock(mutex);
    if(value&&shared&&!offline&&!attempted){attempted=true;base=image;installed=base&&Install();
        Log(folder,installed?"CINEMATIC READY exact-version render-only scoped adapter; actor/input components unchanged":"CINEMATIC REFUSED native guard/hook conflict; ordinary player retained");}
    enabled=value&&installed&&!faulted&&shared;
    if(!enabled)commandCache.clear();
    return enabled;
}
void CloseCinematicBridge(){
    enabled=false;hideUntil=0;std::lock_guard lock(mutex);++epoch;wasEligible=false;window=nullptr;
    commandCache.clear();
    if(shared){auto result=WaitForSingleObject(gate,0);if(result==WAIT_OBJECT_0||result==WAIT_ABANDONED){shared->command={};shared->state={};ReleaseMutex(gate);}UnmapViewOfFile(shared);shared=nullptr;}
    if(request)CloseHandle(request);request=nullptr;
    if(gate)CloseHandle(gate);if(mapping)CloseHandle(mapping);gate=mapping=nullptr;
    // Pinned dormant forwarders remain; never uninitialize global MinHook.
}
void LogCinematicBridge(){
    // File I/O must not hold the render lifetime mutex and drop camera scopes.
    std::filesystem::path logFolder;{std::lock_guard logLock(logMutex);logFolder=folder;}
    Log(logFolder,"CINEMATIC scopes="+std::to_string(scopes.load())+" eligible="+std::to_string(eligibleSamples.load())+" commands="+std::to_string(validCommands.load())+" applied="+std::to_string(applied.load())+" refused="+std::to_string(refused.load())+" conflicts="+std::to_string(conflicts.load())+" faulted="+std::to_string(faulted.load())+" hudCalls="+std::to_string(hudCalls.load())+" hudHidden="+std::to_string(hudHidden.load())
        +" attempts="+std::to_string(attempts.load())+" lifetimeBusy="+std::to_string(lifetimeBusy.load())+" ipcBusy="+std::to_string(ipcBusy.load())+" cachedApplied="+std::to_string(cachedApplied.load())+" concurrentBusy="+std::to_string(concurrentBusy.load()));
    auto trace=continuity.take();
    if(trace.samples){
        char line[768]{};
        std::snprintf(line,sizeof line,"CINEMATIC_TRACE uptimeMs=%llu samples=%llu applied=%llu saved=%llu losses=%llu resumes=%llu resets=%llu invalid=%llu captureSkips=%llu events=%u eventDrops=%llu maxAgeMs=%llu maxGapMs=%llu baseStepDeg=%.4f offsetStepDeg=%.4f outputStepDeg=%.4f aspectStep=%.5f positionStep=%.4f rotationStepDeg=%.4f",
            GetTickCount64(),trace.samples,trace.applied,trace.saved,trace.losses,trace.resumes,trace.resets,trace.invalid,trace.skipped,trace.events,trace.droppedEvents,trace.maxAge,trace.maxGap,
            trace.maxBaseDelta,trace.maxOffsetDelta,trace.maxOutputDelta,trace.maxAspectDelta,trace.maxPositionDelta,trace.maxRotationDelta);
        Log(logFolder,line);
        for(unsigned i=0;i<trace.events;i++){
            auto const& e=trace.event[i];
            std::snprintf(line,sizeof line,"CINEMATIC_EVENT uptimeMs=%llu phase=%u flags=%u command=%llu ageMs=%llu eligible=%d valid=%d applied=%d baseFov=%.4f offset=%.4f outputFov=%.4f aspect=%.5f baseStep=%.4f offsetStep=%.4f outputStep=%.4f aspectStep=%.5f positionStep=%.4f rotationStep=%.4f",
                e.at,static_cast<unsigned>(e.phase),e.reasons,e.command,e.age,e.eligible,e.valid,e.applied,e.base,e.offset,e.output,e.aspect,
                e.baseDelta,e.offsetDelta,e.outputDelta,e.aspectDelta,e.positionDelta,e.rotationDelta);
            Log(logFolder,line);
        }
    }
}
}
