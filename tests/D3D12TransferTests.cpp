// Owned swapchain only: baseline allocation churn and exact transfer pixels.
#define MP_NATIVE_FIXTURE_ONLY
#include "D3D12NativeTests.cpp"
#undef MP_NATIVE_FIXTURE_ONLY
#include "D3D12Composition.h"
#include <chrono>
int main(int argc,char** argv){try{
    bool baseline=false,warp=false;for(int i=1;i<argc;i++){baseline|=std::string(argv[i])=="--baseline";warp|=std::string(argv[i])=="--warp";}
    Fixture f(warp);mp::D3D12Composition gpu;std::uint64_t cpu=0;
    for(unsigned i=0;i<96;i++){
        mp::CompositionFrame frame;auto& h=frame.header;h.sequence=i+1;h.at=GetTickCount64();h.active=1;
        h.viewportWidth=f.width;h.viewportHeight=f.height;h.x=5;h.y=9;h.width=109+i%19;h.height=57+i%13;
        frame.screen.resize(SIZE_T(h.width)*h.height*4);for(unsigned k=0;k<h.width*h.height;k++)reinterpret_cast<unsigned*>(frame.screen.data())[k]=0x80800000;
        auto index=f.swap->GetCurrentBackBufferIndex();f.submit();f.wait();
        auto start=std::chrono::steady_clock::now();auto result=gpu.present(f.swap.Get(),f.queue.Get(),frame,GetTickCount64());
        cpu+=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
        Require(result==mp::D3D12Draw::Drawn,"changing crops must draw");f.present();auto pixels=ReadPixels(f,index);
        for(auto [x,y]:{std::pair{h.x,h.y},std::pair{h.x+h.width-1,h.y+h.height-1}}){auto p=pixels.data()+(y*f.width+x)*4;
            Require(p[0]>=127&&p[0]<=129&&p[2]>=126&&p[2]<=128,"active edges never sample stale capacity padding");}
        auto p=pixels.data()+((h.y+h.height)*f.width+h.x+h.width)*4;Require(p[0]==0&&p[2]==255,"no capacity pixels escape crop");
    }
    auto stats=gpu.transferStats();std::cout<<"TRANSFER_BENCH baseline="<<baseline<<" warp="<<warp<<" frames=96 allocations="<<stats.allocations<<" bytes="<<stats.uploadBytes<<" cpuAvgUs="<<cpu/96000<<std::endl;
    Require(baseline?stats.allocations==96:stats.allocations<=8,"changing crops reuse bounded completed-slot storage");
    if(!baseline){
    // Four disjoint packed bands, changing positions, narrow padding, then
    // full/tiny alternation. All checks read the real D3D12 output.
    for(unsigned n=0;n<20;n++){
        mp::CompositionFrame frame;auto& h=frame.header;h.sequence=100+n;h.at=GetTickCount64();h.active=1;
        h.viewportWidth=f.width;h.viewportHeight=f.height;h.version=2;h.width=400;h.height=32;h.bandCount=4;
        h.bands[0]={3+n,5,17,11,0};h.bands[1]={13,76+n,27,13,11};h.bands[2]={37,190,9,7,24};h.bands[3]={0,299,400,1,31};
        frame.screen.resize(SIZE_T(h.width)*h.height*4);
        for(unsigned j=0;j<4;j++){auto const& b=h.bands[j];for(unsigned y=0;y<b.height;y++)for(unsigned x=0;x<b.width;x++)
            reinterpret_cast<unsigned*>(frame.screen.data())[(b.offsetY+y)*h.width+x]=j==2?0x01010000:0x80800000;}
        auto index=f.swap->GetCurrentBackBufferIndex();f.submit();f.wait();
        Require(gpu.present(f.swap.Get(),f.queue.Get(),frame,GetTickCount64())==mp::D3D12Draw::Drawn,"four packed bands draw");f.present();auto pixels=ReadPixels(f,index);
        for(unsigned j=0;j<4;j++){auto const& b=h.bands[j];for(auto [x,y]:{std::pair{b.x,b.y},std::pair{b.x+b.width-1,b.y+b.height-1}}){auto p=pixels.data()+(y*f.width+x)*4;
            Require(j==2?p[0]==1&&p[2]==254:p[0]>=127&&p[0]<=129&&p[2]>=126&&p[2]<=128,"packed band edges and alpha-one preserve exact pixels");}}
        auto gap=pixels.data()+(150*f.width+200)*4;Require(gap[0]==0&&gap[2]==255,"packed padding never draws in transparent gap");
    }
    for(unsigned n=0;n<16;n++){
        mp::CompositionFrame frame;auto& h=frame.header;h.sequence=200+n;h.at=GetTickCount64();h.active=1;
        h.viewportWidth=f.width;h.viewportHeight=f.height;h.width=n%8<4?f.width:1;h.height=n%8<4?f.height:1;
        frame.screen.assign(SIZE_T(h.width)*h.height*4,0);for(unsigned k=0;k<h.width*h.height;k++)reinterpret_cast<unsigned*>(frame.screen.data())[k]=0x80008000;
        auto index=f.swap->GetCurrentBackBufferIndex();f.submit();f.wait();
        Require(gpu.present(f.swap.Get(),f.queue.Get(),frame,GetTickCount64())==mp::D3D12Draw::Drawn,"grow/shrink storage draws");f.present();auto pixels=ReadPixels(f,index);
        Require(pixels[1]>=127&&pixels[1]<=129,"first texel survives capacity changes");
        auto edge=pixels.data()+((f.height-1)*f.width+f.width-1)*4;Require(h.width==1?edge[1]==0:edge[1]>=127,"shrink cannot retain full-size pixels");
    }
    }
    Require(gpu.reset(),"resources retire without device loss");
    std::cout<<"TRANSFER_PIXELS checks="<<checks<<" passed"<<std::endl;return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<std::endl;return 1;}}
