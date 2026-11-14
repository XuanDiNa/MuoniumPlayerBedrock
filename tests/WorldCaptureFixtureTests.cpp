// Exercise the real native camera capture against separately owned layouts.
#include "../src/WorldBridge.cpp"
#include <vector>
#include <iostream>
#include <stdexcept>
namespace {
void* fixtureLevel{};void* fixtureSource{};void* fixtureScene{};bool fixtureHud=true;
void* Level(void*){return fixtureLevel;}
void* Source(void*){return fixtureSource;}
bool Hud(void*){return fixtureHud;}
void* Dim(void*,int* out){*out=0;return out;}
void* Top(void*){return fixtureScene;}
bool Match(void*,void const*){return true;}
void Need(bool value,char const* text){if(!value)throw std::runtime_error(text);}
template<class T>void Put(void* p,std::size_t off,T value){std::memcpy(static_cast<std::byte*>(p)+off,&value,sizeof value);}
void Run(mp::NativeContract const& p){
    auto image=static_cast<unsigned char*>(VirtualAlloc(nullptr,0x1383B000,MEM_RESERVE,PAGE_NOACCESS));Need(image,"reserve fixture image");
    mp::native=&p;mp::base=reinterpret_cast<std::uintptr_t>(image);
    mp::textLo=mp::dataLo=1;mp::textHi=mp::dataHi=UINTPTR_MAX;
    auto function=[&](std::uint32_t at,auto target){
        Need(VirtualAlloc(image+(at&~0xfffu),4096,MEM_COMMIT,PAGE_EXECUTE_READWRITE)!=nullptr,"commit fixture code");
        unsigned char jump[]{0x48,0xB8,0,0,0,0,0,0,0,0,0xFF,0xE0};
        auto pointer=reinterpret_cast<std::uintptr_t>(target);std::memcpy(jump+2,&pointer,8);std::memcpy(image+at,jump,sizeof jump);
        FlushInstructionCache(GetCurrentProcess(),image+at,sizeof jump);
    };
    function(p.getLevel,Level);function(p.getRegion,Source);function(p.hudPredicate,Hud);function(p.sourceDimension,Dim);
    auto method=p.clientTable+p.hudVirtual;
    Need(VirtualAlloc(image+(method&~0xfffu),4096,MEM_COMMIT,PAGE_READWRITE)!=nullptr,"commit fixture virtual dispatch");
    Put(image,method,mp::base+p.hudPredicate);
    std::vector<unsigned char> renderer(0x1000),levelRenderer(0x800),client(0x800),source(256),level(64),dimension(64),stack(64),scene(64);
    fixtureLevel=level.data();fixtureSource=source.data();fixtureScene=scene.data();
    Put(level.data(),0,mp::base+p.levelTable);Put(source.data(),0,mp::base+p.sourceTable);
    Put(source.data(),32,fixtureLevel);Put(source.data(),48,static_cast<void*>(dimension.data()));
    Put(client.data(),0,mp::base+p.clientTable);Put(client.data(),p.clientRenderer,static_cast<void*>(levelRenderer.data()));
    Put(renderer.data(),p.rendererOwner,static_cast<void*>(levelRenderer.data()));
    Put(levelRenderer.data(),p.rendererClient,static_cast<void*>(client.data()));Put(levelRenderer.data(),p.rendererPlayer,static_cast<void*>(renderer.data()));
    std::uintptr_t dimTable[3]{0,0,reinterpret_cast<std::uintptr_t>(&Dim)};Put(dimension.data(),0,dimTable);
    std::vector<std::uintptr_t> stackTable(64),sceneTable(64);stackTable[53]=reinterpret_cast<std::uintptr_t>(&Top);sceneTable[57]=reinterpret_cast<std::uintptr_t>(&Match);
    Put(stack.data(),0,stackTable.data());Put(scene.data(),0,sceneTable.data());unsigned char valid=1;Put(stack.data(),8,&valid);Put(client.data(),p.screenStack,static_cast<void*>(stack.data()));
    float matrix[16]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    float projection[16]{1,0,0,0,0,1,0,0,0,0,-1,-1,0,0,-.2f,0};
    std::uintptr_t map[2]{reinterpret_cast<std::uintptr_t>(matrix),0},projectionMap[2]{reinterpret_cast<std::uintptr_t>(projection),0};
    auto camera=renderer.data()+p.camera;
    for(auto at:{camera,camera+0x80}){Put(at,8,map);Put<std::size_t>(at,16,2);Put<std::size_t>(at,24,0);Put<std::size_t>(at,32,1);}
    Put(camera+0x80,8,projectionMap);
    Put<float>(camera,0x124,1);Put<float>(camera,0x128,65);Put<float>(camera,0x12c,2);Put<float>(camera,0x120,-1);
    mp::WorldPacket packet;std::uintptr_t levelId{},owner{};bool primary{};void* region{};void* owningClient{};
    fixtureHud=true;
    Need(mp::Capture(renderer.data(),packet,levelId,owner,primary,region,owningClient),"each native camera capture must use its own tables, owner fields and HUD predicate");
    Need(packet.eye[1]==65&&packet.dimension==0&&region==source.data()&&owningClient==client.data(),"owned camera snapshot");
    fixtureHud=false;Need(!mp::Capture(renderer.data(),packet,levelId,owner,primary,region,owningClient),"menu must revoke camera capture");
    VirtualFree(image,0,MEM_RELEASE);
}
}
int main(){try{Run(mp::Contract2645);Run(mp::Contract2651);Run(mp::Contract2652);Run(mp::Contract2660);std::cout<<"Four native camera layouts and menu revocation passed\n";return 0;}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
