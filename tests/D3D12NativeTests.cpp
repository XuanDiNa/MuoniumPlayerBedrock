// Exercise the production composition bridge on an owned real swapchain.
#define main display_fixture_main
#include "D3D12DisplayFixture.cpp"
#undef main
#include "CompositionBridge.h"
#include <vector>
#include <cstring>
#include <thread>
#include <atomic>
#pragma comment(lib,"dwmapi.lib")
namespace {
std::atomic<bool> delayStatus{};
HANDLE statusEntered{},statusRelease{};
}
namespace mp {void Log(std::filesystem::path const&,std::string const& text)noexcept{
    std::cerr<<text<<std::endl;
    if(delayStatus.load()&&text.starts_with("D3D12 STATUS")){
        SetEvent(statusEntered);WaitForSingleObject(statusRelease,5000);
    }
}}
static unsigned checks;
static void Require(bool value,char const* message){++checks;if(!value)throw std::runtime_error(message);}
static std::vector<unsigned char> ReadPixels(Fixture& f,UINT index){
    auto desc=f.buffers[index]->GetDesc();D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};UINT64 bytes{};
    f.device->GetCopyableFootprints(&desc,0,1,0,&footprint,nullptr,nullptr,&bytes);
    D3D12_HEAP_PROPERTIES heap{};heap.Type=D3D12_HEAP_TYPE_READBACK;
    D3D12_RESOURCE_DESC rd{};rd.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;rd.Width=bytes;rd.Height=1;rd.DepthOrArraySize=rd.MipLevels=1;rd.SampleDesc.Count=1;rd.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
    ComPtr<ID3D12Resource> read;Check(f.device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&rd,D3D12_RESOURCE_STATE_COPY_DEST,nullptr,IID_PPV_ARGS(&read)),"readback resource");
    Check(f.allocator->Reset(),"read allocator");Check(f.list->Reset(f.allocator.Get(),nullptr),"read reset");
    D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;barrier.Transition.pResource=f.buffers[index].Get();barrier.Transition.Subresource=D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    barrier.Transition.StateBefore=D3D12_RESOURCE_STATE_PRESENT;barrier.Transition.StateAfter=D3D12_RESOURCE_STATE_COPY_SOURCE;f.list->ResourceBarrier(1,&barrier);
    D3D12_TEXTURE_COPY_LOCATION src{};src.pResource=f.buffers[index].Get();src.Type=D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    D3D12_TEXTURE_COPY_LOCATION dst{};dst.pResource=read.Get();dst.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;dst.PlacedFootprint=footprint;f.list->CopyTextureRegion(&dst,0,0,0,&src,nullptr);
    std::swap(barrier.Transition.StateBefore,barrier.Transition.StateAfter);f.list->ResourceBarrier(1,&barrier);Check(f.list->Close(),"read close");
    ID3D12CommandList* lists[]{f.list.Get()};f.queue->ExecuteCommandLists(1,lists);f.wait();
    void* data{};D3D12_RANGE range{0,static_cast<SIZE_T>(bytes)};Check(read->Map(0,&range,&data),"read map");
    std::vector<unsigned char> result(f.width*f.height*4);for(UINT y=0;y<f.height;++y)std::memcpy(result.data()+y*f.width*4,static_cast<char*>(data)+footprint.Offset+y*footprint.Footprint.RowPitch,f.width*4);
    D3D12_RANGE noWrite{};read->Unmap(0,&noWrite);return result;
}
#ifndef MP_NATIVE_FIXTURE_ONLY
int main(int argc,char** argv){try{
    bool warp=false,serve=false,triple=false;for(int i=1;i<argc;i++){warp|=std::string(argv[i])=="--warp";serve|=std::string(argv[i])=="--serve";triple|=std::string(argv[i])=="--triple";}
    Fixture f(warp,triple?3:2);
    std::wstring prefix=L"Local\\MuoniumBedrock-0123456789abcdef0123456789abcdef";
    Require(mp::OpenCompositionBridge(prefix,{},f.window,false),"open private bridge");mp::EnableCompositionBridge(true,0);
    auto mapping=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,(prefix+L"-composition").c_str());Require(mapping!=nullptr,"open fixture writer");
    auto shared=static_cast<std::byte*>(MapViewOfFile(mapping,FILE_MAP_ALL_ACCESS,0,0,mp::CompositionBytes));Require(shared!=nullptr,"map writer");
    if(serve){
        std::cout<<"READY "<<reinterpret_cast<UINT_PTR>(f.window)<<" Local\\MuoniumBedrock-0123456789abcdef0123456789abcdef"<<std::endl;
        std::string line;while(std::getline(std::cin,line)){
            MSG msg{};while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
            if(line=="STOP")break;
            if(line=="META"){auto const& h=*reinterpret_cast<mp::CompositionHeader*>(shared);std::cout<<"META "<<h.version<<" "<<h.width<<" "<<h.height<<std::endl;continue;}
            if(line.starts_with("RESIZE ")){std::istringstream input(line.substr(7));UINT w{},h{};input>>w>>h;f.resize(w,h);std::cout<<"OK"<<std::endl;continue;}
            auto index=f.swap->GetCurrentBackBufferIndex();f.draw();auto pixels=ReadPixels(f,index);auto p=pixels.data()+(16*f.width+12)*4;
            if(line.starts_with("SAMPLE ")){
                std::istringstream input(line.substr(7));unsigned x{},y{};std::cout<<"SAMPLES";
                while(input>>x>>y){Require(x<f.width&&y<f.height,"sample inside fixture");auto p=pixels.data()+(y*f.width+x)*4;
                    for(unsigned c=0;c<4;c++)std::cout<<" "<<unsigned(p[c]);}
                std::cout<<std::endl;continue;
            }
            auto bottom=pixels.data()+((f.height-24)*f.width+12)*4;
            std::cout<<"PIXELS "<<unsigned(p[0])<<" "<<unsigned(p[1])<<" "<<unsigned(p[2])<<" "<<unsigned(bottom[0])<<" "<<unsigned(bottom[1])<<" "<<unsigned(bottom[2])<<std::endl;
        }
        mp::CloseCompositionBridge();UnmapViewOfFile(shared);CloseHandle(mapping);return 0;
    }
    UINT64 sequence{};
    auto publish=[&](bool probe,bool visible=true){mp::CompositionHeader h;h.sequence=++sequence;h.at=GetTickCount64();h.active=visible;
        h.viewportWidth=f.width;h.viewportHeight=f.height;h.x=4;h.y=8;h.width=16;h.height=16;h.reserved=probe?mp::CompositionProbeOnly:0;
        std::memcpy(shared,&h,sizeof h);for(unsigned i=0;i<256;i++)reinterpret_cast<unsigned*>(shared+4096)[i]=0x80800000;
        InterlockedExchange(reinterpret_cast<LONG*>(shared+3076),1);};
    f.draw();Require(*reinterpret_cast<LONG*>(shared+3072)==0,"owner/device alone must not guess a queue");
    ComPtr<ID3D12CommandQueue> unrelated;D3D12_COMMAND_QUEUE_DESC qd{};qd.Type=D3D12_COMMAND_LIST_TYPE_DIRECT;
    Check(f.device->CreateCommandQueue(&qd,IID_PPV_ARGS(&unrelated)),"unrelated queue");
    Check(f.allocator->Reset(),"empty reset");Check(f.list->Reset(f.allocator.Get(),nullptr),"empty list reset");Check(f.list->Close(),"empty close");
    ID3D12CommandList* empty[]{f.list.Get()};unrelated->ExecuteCommandLists(1,empty);
    Check(unrelated->Signal(f.fence.Get(),++f.serial),"empty-list retirement");Check(f.fence->SetEventOnCompletion(f.serial,f.event),"empty-list event");Require(WaitForSingleObject(f.event,5000)==WAIT_OBJECT_0,"unrelated allocator retired before reuse");
    Require(*reinterpret_cast<LONG*>(shared+3072)==0,"unrelated direct queue rejected");
    for(unsigned i=0;i<8;++i){publish(true);f.draw();}
    Require(*reinterpret_cast<LONG*>(shared+3072)==1,"production D3D12 route must activate on verified queue");
    Require(*reinterpret_cast<LONG64*>(shared+3080)>0,"real Present must acknowledge a probe");
    auto receipt=*reinterpret_cast<LONG64*>(shared+3080);mp::EnableCompositionBridge(true,0);Require(*reinterpret_cast<LONG64*>(shared+3080)==receipt,"repeated enable preserves presentation lease");
    for(unsigned i=0;i<40;i++){
        f.present1=i%2!=0;
        publish(false);auto index=f.swap->GetCurrentBackBufferIndex();f.draw();auto pixels=ReadPixels(f,index);
        auto p=pixels.data()+(10*f.width+10)*4;Require(p[0]>=127&&p[0]<=129&&p[1]==0&&p[2]>=126&&p[2]<=128,"premultiplied red over blue exactly once");
        auto outside=pixels.data()+(2*f.width+2)*4;Require(outside[0]==0&&outside[1]==0&&outside[2]==255,"game pixels outside crop preserved");
    }
    // A slow periodic log write must not hide one presented frame. Submit the
    // game's work first so logging races Present, as with threaded renderers.
    for(bool usePresent1:{false,true}){
        f.present1=usePresent1;publish(false);auto index=f.swap->GetCurrentBackBufferIndex();f.submit();f.wait();
        statusEntered=CreateEventW(nullptr,TRUE,FALSE,nullptr);statusRelease=CreateEventW(nullptr,TRUE,FALSE,nullptr);
        Require(statusEntered&&statusRelease,"status test events");delayStatus=true;
        std::jthread logger([]{mp::LogCompositionBridge();});
        bool entered=WaitForSingleObject(statusEntered,2000)==WAIT_OBJECT_0;
        auto start=GetTickCount64();f.present();auto elapsed=GetTickCount64()-start;
        SetEvent(statusRelease);logger.join();delayStatus=false;CloseHandle(statusEntered);CloseHandle(statusRelease);
        Require(entered,"periodic logger reached controlled slow sink");
        Require(elapsed<1000,"slow log I/O cannot stall the game's Present");
        auto loggedPixels=ReadPixels(f,index);auto p=loggedPixels.data()+(10*f.width+10)*4;
        Require(p[0]>=127&&p[0]<=129&&p[2]>=126&&p[2]<=128,"slow log I/O cannot skip overlay composition");
    }
    f.resize(512,384);for(unsigned i=0;i<5;i++){publish(true);f.draw();}
    publish(false);auto index=f.swap->GetCurrentBackBufferIndex();f.draw();auto pixels=ReadPixels(f,index);
    Require(pixels[(10*f.width+10)*4]>=127,"native drawing recovers after real ResizeBuffers");
    publish(false,false);index=f.swap->GetCurrentBackBufferIndex();f.draw();pixels=ReadPixels(f,index);
    Require(pixels[(10*f.width+10)*4]==0,"hidden player does not draw stale pixels");
    publish(false);reinterpret_cast<mp::CompositionHeader*>(shared)->at=GetTickCount64()-1000;index=f.swap->GetCurrentBackBufferIndex();f.draw();pixels=ReadPixels(f,index);
    Require(pixels[(10*f.width+10)*4]==0,"expired mailbox cannot draw");
    publish(false);reinterpret_cast<mp::CompositionHeader*>(shared)->viewportWidth=f.width-1;index=f.swap->GetCurrentBackBufferIndex();f.draw();pixels=ReadPixels(f,index);
    Require(pixels[(10*f.width+10)*4]==0,"wrong viewport cannot draw");
    mp::EnableCompositionBridge(false,0);Require(*reinterpret_cast<LONG*>(shared+3072)==0,"disable withdraws native lease");
    mp::CloseCompositionBridge();UnmapViewOfFile(shared);CloseHandle(mapping);
    std::cout<<"NATIVE_D3D12 checks="<<checks<<" passed driver="<<(warp?"WARP":"hardware")<<" buffers="<<f.bufferCount<<" present1=true"<<std::endl;return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<std::endl;return 1;}}

#endif
