#include "Runtime.h"
#include "NativeContracts.h"
#include <span>
#include "EnvironmentBridge.h"
#include "EnvironmentRead.h"
#include <algorithm>
#include <cmath>
#include <cstring>
namespace mp {
namespace {
NativeContract const* native{};
struct EnvironmentGuard {std::uint32_t rva;std::array<unsigned char,32> bytes;};
#include "EnvironmentGuards.inl"
#include "EnvironmentGuards2660.inl"
#include "EnvironmentGuards2645.inl"
#include "EnvironmentGuards2651.inl"
#include "EntityGuards.inl"
#include "EntityGuards2660.inl"
#include "EntityGuards2645.inl"
#include "EntityGuards2651.inl"
HANDLE mapping{},gate{},request{};EnvironmentPacket* shared{};
std::uintptr_t image{},textLow{},textHigh{},tableLow{},tableHigh{};bool verified{},active{};
std::filesystem::path folder;EnvironmentScan scan;
std::uint64_t sequence{},published{},faults{},unloaded{},busy{},lastComplete{};std::uint32_t lastKnown{};int lastShapeCount{};
double maxScanUs{};
bool entitiesVerified{},entitiesFaulted{};
EnvironmentEntityCounts lastEntities;
EnvironmentEntityStatus entityStatus{EnvironmentEntityStatus::Invalid};
std::uint64_t entitySamples{},entityRejected{};double entityMaxUs{};
template<class T>T Read(void const* p,std::size_t off=0){T n;std::memcpy(&n,static_cast<std::byte const*>(p)+off,sizeof n);return n;}
struct Pos {int x,y,z;};struct ChunkPos {int x,z;};
void Publish(EnvironmentPacket& p){
    if(!shared||!gate)return;
    auto status=WaitForSingleObject(gate,0);
    if(status!=WAIT_OBJECT_0&&status!=WAIT_ABANDONED){++busy;return;}
    p.sequence=++sequence;std::memcpy(shared,&p,sizeof p);ReleaseMutex(gate);
}
struct Reader {void* source;int minY,maxY,cx{INT_MIN},cz{INT_MIN};bool loaded{};
    struct Surface {void* blockType{};std::uint8_t kind{};};std::array<Surface,64> surfaces{};};
bool BlockName(void* legacy,char (&key)[64]) {
    auto length=Read<std::size_t>(legacy,0xA8),capacity=Read<std::size_t>(legacy,0xB0);
    if(length>capacity||capacity>=4096)return false;
    auto chars=capacity<16?static_cast<char const*>(legacy)+0x98:Read<char const*>(legacy,0x98);
    return CopyEnvironmentKey(key,chars,length);
}
std::uint8_t Material(void* block,Reader& r) {
    if(!block)return 255;
    auto legacy=Read<void*>(block,0x68);if(!legacy)return 255;
    auto material=native->inlineMaterial?static_cast<unsigned char*>(legacy)+0x28:Read<unsigned char*>(legacy,0x28);if(!material)return 255;
    // Exact 19F6F90 compares the one-byte type at +0; 41B6130 passes 5
    // for underwater music. No enum ordering is borrowed from another version.
    if(native->inlineMaterial){
        if(material[0]==5&&(material[1]&2))return 8;
        if((material[1]&1)||!(material[1]&4))return 0;
    }else{
        if(material[0]==5&&material[2]==1)return 8;
        if(material[1]||!material[3])return 0;
    }
    auto& cached=r.surfaces[(reinterpret_cast<std::uintptr_t>(legacy)>>4)%r.surfaces.size()];
    if(cached.blockType==legacy)return cached.kind;
    std::uint8_t kind=1;
    // Exact constructor moves NameInfo into +90; +98 is its owned path string.
    char key[64]{};if(BlockName(legacy,key))kind=EnvironmentSurface(key,std::strlen(key));
    cached={legacy,kind};return kind;
}
EnvironmentBounds Shape(void* block,Reader const& r,Pos const& pos) {
    EnvironmentBounds fallback{0,0,0,4096,4096,4096};
    auto legacy=Read<void*>(block,0x68);auto table=Read<std::uintptr_t>(legacy);
    if(table<tableLow||table+0x28>tableHigh)return fallback;
    auto function=Read<std::uintptr_t>(reinterpret_cast<void*>(table),0x20);
    if(function<textLow||function>=textHigh)return fallback;
    // Exact 3183000: hidden AABB result, block, region, position, optional actor.
    // Owned output only; no C++ object/vector allocated by the game crosses IPC.
    float box[6]{};
    reinterpret_cast<void*(*)(void*,float*,void*,void*,Pos const*,void*)>(function)(legacy,box,block,r.source,&pos,nullptr);
    for(int a=0;a<3;++a) {
        if(!std::isfinite(box[a])||!std::isfinite(box[a+3]))return fallback;
        if(box[a]>=box[a+3])return {};
        double base=a==0?pos.x:a==1?pos.y:pos.z;
        double lo=box[a]-base,hi=box[a+3]-base;
        if(lo< -1||hi>2)return fallback;
        // Each cell owns only its clipped local volume. Composite collision and
        // overhanging geometry remain bounded-box approximations in this ABI.
        fallback[a]=static_cast<short>(std::lround(std::clamp(lo,0.,1.)*4096));
        fallback[a+3]=static_cast<short>(std::lround(std::clamp(hi,0.,1.)*4096));
    }
    return fallback;
}
EnvironmentCell Cell(void* raw,int x,int y,int z) noexcept {
    auto& r=*static_cast<Reader*>(raw);
    __try {
        if(y<r.minY||y>=r.maxY)return {0};
        int cx=x>>4,cz=z>>4;
        if(cx!=r.cx||cz!=r.cz) {
            r.cx=cx;r.cz=cz;ChunkPos pos{cx,cz};
            // Existing-ready-chunk lookup only; never call a chunk creator.
            r.loaded=reinterpret_cast<void*(*)(void*,ChunkPos const*)>(image+native->readyChunk)(r.source,&pos)!=nullptr;
        }
        if(!r.loaded){++unloaded;return {};}
        Pos pos{x,y,z};
        auto block=reinterpret_cast<void*(*)(void*,Pos const*)>(image+native->getBlock)(r.source,&pos);
        auto kind=Material(block,r);
        if(kind==0) {
            auto extra=reinterpret_cast<void*(*)(void*,Pos const*)>(image+native->extraBlock)(r.source,&pos);
            if(Material(extra,r)==8)return {8};
        }
        return {kind,(kind>=1&&kind<=7)||kind==10?Shape(block,r,pos):EnvironmentBounds{}};
    }__except(EXCEPTION_EXECUTE_HANDLER){++faults;return {};}
}
std::uint64_t Clock(void*){LARGE_INTEGER t;QueryPerformanceCounter(&t);return t.QuadPart;}
bool CopyNative(void*,std::uintptr_t address,void* output,std::size_t size) {
    __try {if(address<65536||address>0x7fffffffffff-size)return false;std::memcpy(output,reinterpret_cast<void const*>(address),size);return true;}
    __except(EXCEPTION_EXECUTE_HANDLER){return false;}
}
void Entities(Reader const& r,void* actor,EnvironmentPacket& p) noexcept {
    p.enemies=p.animals=p.villagers=0;p.known&=~16u;lastEntities={};
    entityStatus=EnvironmentEntityStatus::Invalid;
    if(!entitiesVerified||entitiesFaulted||!actor)return;
    __try {
        if(Read<std::uintptr_t>(r.source)!=image+native->sourceTable)return;
        // This exact ClientInstance BlockSource belongs to the owning render
        // callback. Never query a server Level or traverse its registry list.
        auto body=Read<float const*>(actor,native->actorAabb);float box[6]{},query[6]{};
        if(!CopyNative(nullptr,reinterpret_cast<std::uintptr_t>(body),box,sizeof box)||!EnvironmentEntityBox(box,query))return;
        struct Borrowed {std::uint64_t count;std::uintptr_t entries;} result{};
        LARGE_INTEGER frequency;QueryPerformanceFrequency(&frequency);auto start=Clock(nullptr);
        reinterpret_cast<Borrowed*(*)(void*,Borrowed*,void const*,float const*,bool,bool)>(image+native->entitySpan)
            (r.source,&result,actor,query,false,false);
        // Span is count then pointer. The game's scratch storage is borrowed;
        // copy numeric counts immediately, never free it or retain actors.
        auto elapsed=Clock(nullptr)-start;auto budget=std::max<std::uint64_t>(1,frequency.QuadPart/1000);
        EnvironmentEntityCounts counts;
        if(elapsed>=budget)entityStatus=EnvironmentEntityStatus::Budget;
        else entityStatus=ReadEnvironmentEntities(result.entries,result.count,reinterpret_cast<std::uintptr_t>(actor),
            image+native->healthAttribute,CopyNative,nullptr,Clock,nullptr,budget-elapsed,counts,native->actorRemoved,native->healthStride);
        entityMaxUs=std::max(entityMaxUs,double(Clock(nullptr)-start)*1000000/frequency.QuadPart);
        if(entityStatus==EnvironmentEntityStatus::Known){p.enemies=counts.enemies;p.animals=counts.animals;p.villagers=counts.villagers;p.known|=16;lastEntities=counts;++entitySamples;}
        else ++entityRejected;
    }__except(EXCEPTION_EXECUTE_HANDLER){entitiesFaulted=true;++faults;++entityRejected;}
}
bool Metadata(Reader& r,void* client,WorldPacket const& world,EnvironmentPacket& p) noexcept {
    __try {
        std::copy_n(world.eye,3,p.eye);std::copy_n(world.look,3,p.look);
        Pos eye{int(std::floor(p.eye[0])),int(std::floor(p.eye[1])),int(std::floor(p.eye[2]))};
        if(Cell(&r,eye.x,eye.y,eye.z).material==255)return false;
        if(eye.y>=r.minY&&eye.y<r.maxY&&Cell(&r,eye.x,eye.y,eye.z).material!=255) {
            struct Light {unsigned char sky,block;} light{};
            reinterpret_cast<void*(*)(void*,Light*,Pos const*)>(image+native->light)(r.source,&light,&eye);
            int sky=reinterpret_cast<int(*)(void*,Pos const*)>(image+native->skyLight)(r.source,&eye);
            if(light.sky<=15&&light.block<=15&&sky>=0&&sky<=15){p.light=float(std::max(int(light.block),sky));p.sky=float(sky);p.known|=64;}
        }
        auto level=Read<void*>(r.source,0x20);
        if(level&&Read<std::uintptr_t>(level)==image+native->levelTable) {
            int time=reinterpret_cast<int(*)(void*)>(image+native->getTime)(level);
            p.time=EnvironmentTimeOfDay(time);p.known|=1;
        }
        if(client&&Read<std::uintptr_t>(client)==image+native->clientTable) {
            auto actor=reinterpret_cast<void*(*)(void*)>(image+native->getPlayer)(client);
            if(actor&&Read<std::uintptr_t>(actor)==image+native->playerTable){
                if(ReadEnvironmentHealth(reinterpret_cast<std::uintptr_t>(actor),image+native->healthAttribute,CopyNative,nullptr,p.health,native->healthStride))p.known|=8;
                Entities(r,actor,p);
            }
        }
        if(eye.y>=r.minY&&eye.y<r.maxY) {
            auto biome=reinterpret_cast<void*(*)(void*,Pos const*)>(image+native->biome)(r.source,&eye);
            if(biome&&Read<std::uintptr_t>(biome)==image+native->biomeTable) {
                auto length=Read<std::size_t>(biome,0x1A8),capacity=Read<std::size_t>(biome,0x1B0);
                auto chars=capacity<16?static_cast<char const*>(biome)+0x198:Read<char const*>(biome,0x198);
                if(length<=capacity&&capacity<4096&&CopyEnvironmentKey(p.biome,chars,length))p.known|=4;
            }
        }
        // Original palette/scan order, centered on this renderer's listener.
        // This is a block clue, not an authoritative generated-structure query.
        bool clueKnown=true,found=false;
        for(int dy:{-2,-1,1,2}){for(int dx=-1;dx<=1&&!found;dx++)for(int dz=-1;dz<=1&&!found;dz++) {
            Pos neighbor{eye.x+dx,eye.y+dy,eye.z+dz};
            if(Cell(&r,neighbor.x,neighbor.y,neighbor.z).material==255){clueKnown=false;continue;}
            if(neighbor.y<r.minY||neighbor.y>=r.maxY)continue;
            auto block=reinterpret_cast<void*(*)(void*,Pos const*)>(image+native->getBlock)(r.source,&neighbor);
            auto legacy=block?Read<void*>(block,0x68):nullptr;char key[64]{};
            if(!legacy||!BlockName(legacy,key)){clueKnown=false;continue;}
            auto size=Read<std::size_t>(legacy,0xD0),capacity=Read<std::size_t>(legacy,0xD8);
            if(size!=9||size>capacity||capacity>=4096)continue;
            auto ns=capacity<16?static_cast<char const*>(legacy)+0xC0:Read<char const*>(legacy,0xC0);
            if(ns&&std::memcmp(ns,"minecraft",9)==0){p.blockClue=EnvironmentBlockClue(key,std::strlen(key));found=p.blockClue!=0;}
        }if(found)break;}
        if(clueKnown||found)p.known|=256;
        // Renderer 6235C90 reads Dimension+1E8 Weather: previous/current
        // rain +34/+38 and thunder +40/+44. Command writes targets +3C/+48.
        auto dimension=Read<void*>(r.source,0x30);auto weather=dimension?Read<void*>(dimension,0x1E8):nullptr;
        if(weather){float rain=Read<float>(weather,0x38),thunder=Read<float>(weather,0x44);
            if(std::isfinite(rain)&&std::isfinite(thunder)&&rain>=0&&rain<=1&&thunder>=0&&thunder<=1){p.rain=rain;p.thunder=thunder;p.known|=2;}}
        if(Cell(&r,eye.x,eye.y,eye.z).material==8)p.water|=3;
        if(Cell(&r,eye.x,int(std::floor(p.eye[1]-1.5)),eye.z).material==8)p.water|=1;
        return true;
    }__except(EXCEPTION_EXECUTE_HANDLER){++faults;return false;}
}
}
bool OpenEnvironmentBridge(std::wstring const& prefix,std::filesystem::path const& logFolder){
    if(shared)return false;folder=logFolder;
    entitiesFaulted=false;entityStatus=EnvironmentEntityStatus::Invalid;lastEntities={};
    mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,sizeof(EnvironmentPacket),(prefix+L"-environment").c_str());
    if(!mapping||GetLastError()==ERROR_ALREADY_EXISTS){if(mapping)CloseHandle(mapping);mapping=nullptr;return false;}
    gate=CreateMutexW(nullptr,FALSE,(prefix+L"-environment-lock").c_str());
    if(!gate||GetLastError()==ERROR_ALREADY_EXISTS){CloseEnvironmentBridge();return false;}
    request=CreateEventW(nullptr,TRUE,FALSE,(prefix+L"-environment-active").c_str());
    if(!request||GetLastError()==ERROR_ALREADY_EXISTS){CloseEnvironmentBridge();return false;}
    shared=static_cast<EnvironmentPacket*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,sizeof(EnvironmentPacket)));
    if(!shared){CloseEnvironmentBridge();return false;}
    EnvironmentPacket initial;Publish(initial);return true;
}
bool VerifyEnvironmentNative(std::uintptr_t base) noexcept {
    __try {
        image=base;native=LoadedNativeContract(base);if(!native)return verified=false;
        auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(base);
        auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(base+dos->e_lfanew);auto section=IMAGE_FIRST_SECTION(nt);
        for(unsigned i=0;i<nt->FileHeader.NumberOfSections;++i) {
            if(!std::memcmp(section[i].Name,".text",5)){textLow=base+section[i].VirtualAddress;textHigh=textLow+section[i].Misc.VirtualSize;}
            if(!std::memcmp(section[i].Name,".rdata",6)){tableLow=base+section[i].VirtualAddress;tableHigh=tableLow+section[i].Misc.VirtualSize;}
        }
        if(!textLow||!tableLow)return verified=false;
        for(auto const& g:VersionGuards(native->version,environmentGuards2645,environmentGuards2651,environmentGuards,environmentGuards2660))if(std::memcmp(reinterpret_cast<void*>(base+g.rva),g.bytes.data(),g.bytes.size()))return verified=false;
        entitiesVerified=Read<std::uintptr_t>(reinterpret_cast<void*>(base+native->sourceTable),31*8)==base+native->entitySpan;
        for(auto const& g:VersionGuards(native->version,entityGuards2645,entityGuards2651,entityGuards,entityGuards2660))if(std::memcmp(reinterpret_cast<void*>(base+g.rva),g.bytes.data(),g.bytes.size()))entitiesVerified=false;
        return verified=true;
    }__except(EXCEPTION_EXECUTE_HANDLER){return verified=false;}
}
void ClearEnvironment(){
    if(!active)return;active=false;scan.clear();lastComplete=0;lastKnown=0;lastShapeCount=0;
    lastEntities={};entityStatus=EnvironmentEntityStatus::Invalid;
    EnvironmentPacket empty;Publish(empty);
}
void CaptureEnvironment(void* source,void* client,WorldPacket const& world,std::uint64_t epoch,std::uint64_t now){
    if(!shared||!request||!verified||!source||WaitForSingleObject(request,0)!=WAIT_OBJECT_0){ClearEnvironment();return;}
    active=true;
    Reader r{source};
    // Capture supplies this exact BlockSource during its owning callback only.
    __try {r.minY=Read<short>(source,0x3A);r.maxY=Read<short>(source,0x38);}
    __except(EXCEPTION_EXECUTE_HANDLER){ClearEnvironment();++faults;return;}
    if(r.minY>=r.maxY||r.minY<-4096||r.maxY>4096){ClearEnvironment();return;}
    if(scan.complete()&&now-lastComplete<150)return;
    if(scan.complete()||scan.restart(world,epoch,now))scan.start(world,epoch,now);
    LARGE_INTEGER frequency;QueryPerformanceFrequency(&frequency);auto start=Clock(nullptr);
    bool done=scan.step(Cell,&r,Clock,nullptr,2048,std::max<std::uint64_t>(1,frequency.QuadPart*350/1000000));
    if(done){auto& p=scan.packet();if(!Metadata(r,client,world,p)){ClearEnvironment();return;}p.valid=1;p.at=now;Publish(p);lastComplete=now;lastKnown=p.known;lastShapeCount=p.shapeCount;++published;}
    maxScanUs=std::max(maxScanUs,double(Clock(nullptr)-start)*1000000/frequency.QuadPart);
}
void LogEnvironment(){
    Log(folder,"ENVIRONMENT active="+std::to_string(active)+" verified="+std::to_string(verified)+" snapshots="+std::to_string(published)
        +" unknownCells="+std::to_string(unloaded)+" faults="+std::to_string(faults)+" transportBusy="+std::to_string(busy)
        +" scanMaxUs="+std::to_string(static_cast<unsigned>(maxScanUs)));
    Log(folder,"ENVIRONMENT metadataKnown="+std::to_string(lastKnown)+" shapes="+std::to_string(lastShapeCount)+" protocol=2 bounds=single-AABB");
    Log(folder,"ENTITIES verified="+std::to_string(entitiesVerified)+" disabledAfterFault="+std::to_string(entitiesFaulted)
        +" known="+std::to_string(active&&(lastKnown&16)!=0)+" enemies="+std::to_string(lastEntities.enemies)
        +" animals="+std::to_string(lastEntities.animals)+" villagers="+std::to_string(lastEntities.villagers)
        +" status="+std::to_string(int(entityStatus))+" samples="+std::to_string(entitySamples)+" rejected="+std::to_string(entityRejected)
        +" maxUs="+std::to_string(static_cast<unsigned>(entityMaxUs)));
}
void CloseEnvironmentBridge(){
    ClearEnvironment();if(shared)UnmapViewOfFile(shared);shared=nullptr;
    if(request)CloseHandle(request);if(gate)CloseHandle(gate);if(mapping)CloseHandle(mapping);
    request=gate=mapping=nullptr;verified=false;entitiesVerified=false;
}
}
