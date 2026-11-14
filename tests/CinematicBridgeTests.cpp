#include "CinematicBridge.h"
#include "Runtime.h"
#include "NativeContracts.h"
#include <span>
#include <stdexcept>
#include <iostream>
#include <array>
#include <cstring>
#include <string>
#include <MinHook.h>
#include <thread>
void need(bool v){if(!v)throw std::runtime_error("Cinematic transport assertion");}
namespace {
void commandContinuityFixture(){
    mp::CinematicState state;state.sequence=1;state.at=1000;state.epoch=8;state.input=7;state.eligible=1;
    mp::CinematicCommand receipt;receipt.sequence=1;receipt.at=1000;receipt.epoch=8;receipt.input=7;receipt.active=1;
    receipt.position[0]=4;receipt.position[1]=2;receipt.yaw=90;
    mp::CinematicCommandCache cache;mp::CinematicCommand selected;
    need(cache.select(&receipt,state,1010,selected));
    HANDLE gate=CreateMutexW(nullptr,FALSE,nullptr),held=CreateEventW(nullptr,TRUE,FALSE,nullptr),release=CreateEventW(nullptr,TRUE,FALSE,nullptr);
    need(gate&&held&&release);
    std::thread writer([&]{WaitForSingleObject(gate,INFINITE);SetEvent(held);WaitForSingleObject(release,5000);ReleaseMutex(gate);});
    need(WaitForSingleObject(held,2000)==WAIT_OBJECT_0);
    bool continuity=true;
    for(unsigned i=0;i<8;i++){
        need(WaitForSingleObject(gate,0)==WAIT_TIMEOUT);
        std::array<std::byte,288> camera{};float fov=1.2f;std::memcpy(camera.data()+80,&fov,4);auto before=camera;
        mp::CinematicPatch patch;
        bool valid=cache.select(nullptr,state,1020+i,selected);
        if(!valid){continuity=false;continue;}
        need(patch.apply(camera.data(),selected));float x{};std::memcpy(&x,camera.data()+64,4);need(x==4);
        need(patch.restore(camera.data())&&camera==before);
    }
    SetEvent(release);writer.join();CloseHandle(gate);CloseHandle(held);CloseHandle(release);
    if(!continuity)throw std::runtime_error("A contended IPC mutex must not replace an already fresh cinematic pose with a vanilla frame");
    need(cache.select(nullptr,state,1150,selected));
    need(!cache.select(nullptr,state,1151,selected)); // Original 150 ms bound, no extension.
    need(cache.select(&receipt,state,1020,selected));auto changed=state;changed.input++;
    need(!cache.select(nullptr,changed,1021,selected));
    need(cache.select(&receipt,state,1020,selected));changed=state;changed.epoch++;
    need(!cache.select(nullptr,changed,1021,selected));
    need(cache.select(&receipt,state,1020,selected));changed=state;changed.eligible=0;
    need(!cache.select(nullptr,changed,1021,selected));
    need(cache.select(&receipt,state,1020,selected));auto inactive=receipt;inactive.active=0;
    need(!cache.select(&inactive,state,1021,selected)&&!cache.select(nullptr,state,1022,selected));
    need(cache.select(&receipt,state,1020,selected));auto invalid=receipt;invalid.position[0]=17;
    need(!cache.select(&invalid,state,1021,selected)&&!cache.select(nullptr,state,1022,selected));
    need(cache.select(&receipt,state,1020,selected));changed=state;changed.at=1;
    need(!cache.select(nullptr,changed,1021,selected));
    need(cache.select(&receipt,state,1020,selected));cache.clear();need(!cache.select(nullptr,state,1021,selected));
    std::cout<<"Cinematic contended-IPC continuity, unchanged expiry, input/world/UI revocation and scalar restoration passed\n";
}
struct Guard{std::uint32_t rva;std::array<unsigned char,32> bytes;};
#include "../src/CinematicGuards.inl"
#include "../src/CinematicGuards2660.inl"
#include "../src/CinematicGuards2645.inl"
#include "../src/CinematicGuards2651.inl"
int forwarded{};void* passedScene{};void* passedContext{};
std::uintptr_t ExistingSceneHook(void* scene,void* context){++forwarded;passedScene=scene;passedContext=context;return 792;}
void installFixture(std::string scenario){
    unsigned index=0;auto contract=&mp::Contract2652;
    for(auto [suffix,id,c]:{std::tuple{"-2645",7u,&mp::Contract2645},std::tuple{"-2651",9u,&mp::Contract2651},std::tuple{"-2660",2u,&mp::Contract2660}})
        if(scenario.ends_with(suffix)){index=id;contract=c;scenario.resize(scenario.size()-5);break;}
    auto const& p=*contract;
    auto const& identity=mp::HostIdentities[index];mp::VerifiedHostIdentity.store(&identity);
    auto image=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x1383B000,MEM_RESERVE,PAGE_NOACCESS));need(image);
    auto page=[&](std::uint32_t rva){need(VirtualAlloc(image+(rva&~0xfffu),4096,MEM_COMMIT,PAGE_EXECUTE_READWRITE)!=nullptr);};
    page(0);auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(image);dos->e_magic=IMAGE_DOS_SIGNATURE;dos->e_lfanew=128;
    auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(image+128);nt->Signature=IMAGE_NT_SIGNATURE;nt->FileHeader.Machine=0x8664;
    nt->FileHeader.TimeDateStamp=identity.timestamp;nt->OptionalHeader.Magic=IMAGE_NT_OPTIONAL_HDR64_MAGIC;nt->OptionalHeader.SizeOfImage=identity.imageSize;
    for(auto const& g:mp::VersionGuards(p.version,cinematicGuards2645,cinematicGuards2651,cinematicGuards,cinematicGuards2660)){page(g.rva);std::memcpy(image+g.rva,g.bytes.data(),32);}
    // Independent plugins already own these code entries. The dispatch pointer
    // is unchanged: forwarding through it must still reach their existing code.
    page(0x4839060);image[0x4839060]=0xCC;
    page(p.sceneRender);unsigned char jump[]{0xff,0x25,0,0,0,0};
    std::memcpy(image+p.sceneRender,jump,6);auto target=reinterpret_cast<void*>(ExistingSceneHook);
    std::memcpy(image+(p.sceneRender+6),&target,8);
    page(p.sceneSlot);auto slot=reinterpret_cast<void**>(image+p.sceneSlot);
    *slot=image+(scenario=="slot-conflict"?(p.sceneRender+1):p.sceneRender);
    auto before=*slot;DWORD old{};need(VirtualProtect(slot,8,PAGE_READONLY,&old));
    if(scenario=="guard-conflict")image[p.frame]=0xCC;
    std::wstring prefix=L"Local\\MuoniumBedrock-fixture-"+std::to_wstring(GetCurrentProcessId());
    need(mp::OpenCinematicBridge(prefix,L"artifacts/camera-continuity",false));
    bool accepted=mp::EnableCinematicBridge(true,reinterpret_cast<std::uintptr_t>(image));
    if(scenario=="occupied"){
        if(!accepted)throw std::runtime_error("Camera must admit occupied ContainerPreview/Extra code entries via independent scene dispatch");
        need(*slot!=before);
        auto scene=reinterpret_cast<std::uintptr_t(*)(void*,void*)>(*slot);
        need(scene(image+32,image+64)==792&&forwarded==1&&passedScene==image+32&&passedContext==image+64);
        mp::EnableCinematicBridge(false,0);
        need(scene(image+32,image+64)==792&&forwarded==2);
    }else{
        need(!accepted&&*slot==before);
        need(image[p.render]==0x55); // Failed install must not leave other code patched.
    }
    need(image[0x4839060]==0xCC&&image[p.sceneRender]==0xff&&image[(p.sceneRender+1)]==0x25);
    MEMORY_BASIC_INFORMATION memory{};need(VirtualQuery(slot,&memory,sizeof memory)==sizeof memory&&memory.Protect==PAGE_READONLY);
    mp::CloseCinematicBridge(); // Native forwarders deliberately stay resident.
    std::cout<<"Cinematic install "<<scenario<<" passed\n";
}
void child(std::wstring const& scenario){
    wchar_t path[32768]{};need(GetModuleFileNameW(nullptr,path,32768)>0);
    std::wstring command=L"\""+std::wstring(path)+L"\" "+scenario;
    STARTUPINFOW start{sizeof start};PROCESS_INFORMATION process{};
    need(CreateProcessW(path,command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,nullptr,&start,&process));
    need(WaitForSingleObject(process.hProcess,15000)==WAIT_OBJECT_0);DWORD code{};
    need(GetExitCodeProcess(process.hProcess,&code));CloseHandle(process.hThread);CloseHandle(process.hProcess);need(code==0);
}
}
int run(int argc,char** argv){
    if(argc==2){installFixture(argv[1]);return 0;}
    std::wstring prefix=L"Local\\MuoniumBedrock-cc123456789012345678901234567890";
    need(mp::OpenCinematicBridge(prefix,L".",true));
    auto map=OpenFileMappingW(FILE_MAP_READ|FILE_MAP_WRITE,FALSE,(prefix+L"-cinematic").c_str());need(map!=nullptr);
    auto bytes=static_cast<unsigned char*>(MapViewOfFile(map,FILE_MAP_READ|FILE_MAP_WRITE,0,0,200));need(bytes);
    auto command=reinterpret_cast<mp::CinematicCommand*>(bytes);
    auto state=reinterpret_cast<mp::CinematicState*>(bytes+88);
    need(command->magic==0x4350434d&&state->magic==0x5350434d&&state->eligible==0&&state->applied==0);
    auto request=OpenEventW(EVENT_MODIFY_STATE|SYNCHRONIZE,FALSE,(prefix+L"-cinematic-active").c_str());need(request);
    need(WaitForSingleObject(request,0)==WAIT_TIMEOUT);
    need(!mp::EnableCinematicBridge(true,0)); // Offline must never detour the fixture.
    need(!mp::OpenCinematicBridge(prefix,L".",true));
    command->active=1;mp::CloseCinematicBridge();need(command->active==0&&state->eligible==0);
    UnmapViewOfFile(bytes);CloseHandle(map);CloseHandle(request);
    need(mp::OpenCinematicBridge(prefix,L".",true));mp::CloseCinematicBridge();
    commandContinuityFixture();
    std::cout<<"Cinematic private mapping, offline refusal and close/reopen invalidation passed\n";
    child(L"occupied");child(L"slot-conflict");child(L"guard-conflict");
    child(L"occupied-2645");child(L"slot-conflict-2645");child(L"guard-conflict-2645");
    child(L"occupied-2651");child(L"slot-conflict-2651");child(L"guard-conflict-2651");
    child(L"occupied-2660");child(L"slot-conflict-2660");child(L"guard-conflict-2660");
    std::cout<<"Cinematic hook coexistence, forwarding, guard refusal and read-only dispatch fixtures passed\n";
    return 0;
}
int main(int argc,char** argv){try{return run(argc,argv);}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
namespace mp {bool CinematicHud(void*) noexcept{return false;}}
