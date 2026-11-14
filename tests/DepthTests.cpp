#include "DepthGpu.h"
#include "DepthWrites.h"
#include <iostream>
#include <cmath>
#include <stdexcept>
#include <wrl/client.h>
#include <d3dcompiler.h>
using Microsoft::WRL::ComPtr;
static int checks{};
static void Check(bool v,char const* msg){++checks;if(!v)throw std::runtime_error(msg);}
static void GpuCheck(){
    auto library=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto create=reinterpret_cast<decltype(&D3D11CreateDevice)>(GetProcAddress(library,"D3D11CreateDevice"));
    ComPtr<ID3D11Device> d;ComPtr<ID3D11DeviceContext> c;
    Check(create&&SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&d,nullptr,&c)),"real WARP device");
    auto compiler=LoadLibraryExW(L"d3dcompiler_47.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto compile=reinterpret_cast<decltype(&D3DCompile)>(GetProcAddress(compiler,"D3DCompile"));
    char const source[]="float4 main(uint n:SV_VertexID):SV_Position{float2 p[3]={float2(-1,-1),float2(0,-1),float2(-1,1)};return float4(p[n],.25,1);}";
    ComPtr<ID3DBlob> bytes,error;Check(compile&&SUCCEEDED(compile(source,sizeof(source)-1,nullptr,nullptr,nullptr,"main","vs_5_0",0,0,&bytes,&error)),"fixture VS");
    ComPtr<ID3D11VertexShader> vs;Check(SUCCEEDED(d->CreateVertexShader(bytes->GetBufferPointer(),bytes->GetBufferSize(),nullptr,&vs)),"fixture shader");
    mp::WorldPacket camera;camera.at=1000;camera.sequence=1;camera.valid=1;camera.epoch=7;camera.projection[0]=1.1f;camera.projection[5]=1.7f;camera.projection[10]=-1.01f;camera.projection[11]=-1;camera.projection[14]=-.2f;
    camera.view[0]=camera.view[5]=camera.view[10]=camera.view[15]=1;
    camera.eye[0]=100;camera.eye[1]=70;camera.eye[2]=-20;
    camera.view[12]=-100;camera.view[13]=-70;camera.view[14]=20;
    float renderView[16]{};renderView[0]=renderView[5]=renderView[10]=renderView[15]=1;
    float constants[120]{};std::copy(camera.projection,camera.projection+16,constants);std::copy(renderView,renderView+16,constants+16);
    D3D11_BUFFER_DESC bd{};bd.ByteWidth=sizeof constants;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    D3D11_SUBRESOURCE_DATA initial{constants,0,0};ComPtr<ID3D11Buffer> cb;Check(SUCCEEDED(d->CreateBuffer(&bd,&initial,&cb)),"camera constants");
    for(auto format:{DXGI_FORMAT_D32_FLOAT,DXGI_FORMAT_D24_UNORM_S8_UINT})for(UINT samples:{1u,2u,4u}){
        UINT quality{};Check(SUCCEEDED(d->CheckMultisampleQualityLevels(format,samples,&quality))&&quality>0,"fixture sample support");
        D3D11_TEXTURE2D_DESC td{};td.Width=td.Height=64;td.MipLevels=td.ArraySize=1;td.Format=format;td.SampleDesc.Count=samples;td.BindFlags=D3D11_BIND_DEPTH_STENCIL;
        ComPtr<ID3D11Texture2D> depth;ComPtr<ID3D11DepthStencilView> view;
        Check(SUCCEEDED(d->CreateTexture2D(&td,nullptr,&depth))&&SUCCEEDED(d->CreateDepthStencilView(depth.Get(),nullptr,&view)),"real depth target");
        D3D11_VIEWPORT vp{0,0,64,64,0,1};c->RSSetViewports(1,&vp);c->OMSetRenderTargets(0,nullptr,view.Get());
        c->ClearDepthStencilView(view.Get(),D3D11_CLEAR_DEPTH,1,0);c->VSSetShader(vs.Get(),nullptr,0);c->PSSetShader(nullptr,nullptr,0);
        c->IASetInputLayout(nullptr);c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        D3D11_RASTERIZER_DESC rd{};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_NONE;rd.DepthClipEnable=TRUE;rd.MultisampleEnable=TRUE;
        ComPtr<ID3D11RasterizerState> raster;Check(SUCCEEDED(d->CreateRasterizerState(&rd,&raster)),"fixture raster state");c->RSSetState(raster.Get());c->Draw(3,0);
        mp::DepthGpu gpu;gpu.camera(camera,renderView);Check(gpu.observe(c.Get(),view.Get(),cb.Get(),0),"snapshot draw-owned constants");
        mp::DepthRequest request;request.at=1000;request.epoch=7;request.active=1;request.viewportWidth=request.viewportHeight=64;request.x=4;request.y=8;request.width=request.height=48;
        // Keep an original compute constant binding to prove restoration.
        auto original=cb.Get();c->CSSetConstantBuffers(0,1,&original);
        Check(gpu.beforeClear(c.Get(),view.Get(),request,1001),"asynchronous crop submit");
        c->ClearDepthStencilView(view.Get(),D3D11_CLEAR_DEPTH,1,0);
        ComPtr<ID3D11Buffer> restored;c->CSGetConstantBuffers(0,1,&restored);Check(restored==cb,"compute binding restored");
        ComPtr<ID3D11DepthStencilView> stillBound;c->OMGetRenderTargets(0,nullptr,&stillBound);Check(stillBound==view,"game depth target unchanged");
        // Only the test fixture flushes. Production uses natural game submits.
        c->Flush();mp::DepthResult result;bool ready{};
        for(unsigned i=0;i<200&&!ready;++i){ready=gpu.poll(c.Get(),result,1002);if(!ready)Sleep(1);}
        Check(ready,"completed real GPU readback");Check(result.rgba.size()==48*48*4&&result.x==4&&result.y==8,"only requested crop copied");
        Check(result.camera.eye[0]==100&&result.camera.view[12]==-100&&result.camera.view[13]==-70,
              "published world camera retains full translation; relative GPU view must not replace it");
        auto sample=[&](unsigned x,unsigned y){auto at=((y-8)*48+(x-4))*4;return (unsigned(result.rgba[at])<<16)|(unsigned(result.rgba[at+1])<<8)|result.rgba[at+2];};
        Check(std::abs(static_cast<int>(sample(8,48))-4194304)<=2,"foreground silhouette has true raster depth");
        Check(sample(48,24)==16777215,"clear background stays visible");
        Check(!gpu.beforeClear(c.Get(),view.Get(),request,1003),"one capture per observed clear interval");
        Check(gpu.observe(c.Get(),view.Get(),cb.Get(),0),"observe another draw");
        Check(!gpu.beforeClear(c.Get(),view.Get(),request,1004),"capture rate bounded independently of game FPS");
        // A completed but foreign camera needs an explicit reason, not published=0 alone.
        constants[0]=9;c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
        Check(gpu.observe(c.Get(),view.Get(),cb.Get(),0),"foreign draw observed");
        Check(gpu.beforeClear(c.Get(),view.Get(),request,1020),"foreign draw submitted for guarded comparison");c->Flush();
        for(unsigned i=0;i<200&&gpu.diagnostics().cameraMiss==0;++i){gpu.poll(c.Get(),result,1021);Sleep(1);}
        Check(gpu.diagnostics().cameraMiss==1&&gpu.diagnostics().completed==2,"completed capture has camera-mismatch diagnostic");
        Check(gpu.diagnostics().projectionError>1,"mismatch diagnostic reports a bounded scalar delta");
        constants[0]=camera.projection[0];c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
        c->OMSetRenderTargets(0,nullptr,nullptr);gpu.reset();
    }
    // A real over-budget, odd-sized ROI must keep the entire visible region.
    // Min reduction must preserve even a one-pixel foreground silhouette.
    D3D11_TEXTURE2D_DESC td{};td.Width=1100;td.Height=1000;td.MipLevels=td.ArraySize=1;
    td.Format=DXGI_FORMAT_D32_FLOAT;td.SampleDesc.Count=1;td.BindFlags=D3D11_BIND_DEPTH_STENCIL;
    ComPtr<ID3D11Texture2D> largeDepth;ComPtr<ID3D11DepthStencilView> largeView;
    Check(SUCCEEDED(d->CreateTexture2D(&td,nullptr,&largeDepth))&&SUCCEEDED(d->CreateDepthStencilView(largeDepth.Get(),nullptr,&largeView)),"large real depth fixture");
    D3D11_VIEWPORT vp{0,0,1100,1000,0,1};c->RSSetViewports(1,&vp);c->OMSetRenderTargets(0,nullptr,largeView.Get());
    c->ClearDepthStencilView(largeView.Get(),D3D11_CLEAR_DEPTH,1,0);
    D3D11_RASTERIZER_DESC rd{};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_NONE;rd.DepthClipEnable=TRUE;rd.ScissorEnable=TRUE;
    ComPtr<ID3D11RasterizerState> raster;Check(SUCCEEDED(d->CreateRasterizerState(&rd,&raster)),"one pixel raster state");
    D3D11_RECT scissor{100,800,101,801};c->RSSetState(raster.Get());c->RSSetScissorRects(1,&scissor);c->Draw(3,0);
    mp::DepthGpu gpu;gpu.camera(camera,renderView);Check(gpu.observe(c.Get(),largeView.Get(),cb.Get(),0),"large crop draw observed");
    mp::DepthRequest request;request.at=1000;request.epoch=7;request.active=1;request.viewportWidth=1100;request.viewportHeight=1000;
    request.x=4;request.y=8;request.width=1095;request.height=991;
    Check(gpu.beforeClear(c.Get(),largeView.Get(),request,1001),"over-budget crop remains active with reduced depth resolution");
    c->ClearDepthStencilView(largeView.Get(),D3D11_CLEAR_DEPTH,1,0);c->Flush();
    mp::DepthResult result;bool ready{};
    for(unsigned i=0;i<300&&!ready;++i){ready=gpu.poll(c.Get(),result,1002);if(!ready)Sleep(1);}
    Check(ready&&result.sampleStep==2&&result.width==1095&&result.height==991,"logical ROI and sample spacing retained");
    Check(result.rgba.size()==548*496*4&&result.rgba.size()<=mp::DepthMaxPixels*4,"odd edge sample dimensions bounded");
    auto sample=[&](unsigned x,unsigned y){auto at=(y*548+x)*4;return (unsigned(result.rgba[at])<<16)|(unsigned(result.rgba[at+1])<<8)|result.rgba[at+2];};
    Check(std::abs(static_cast<int>(sample(48,396))-4194304)<=2,"min reduction preserves a single foreground pixel");
    Check(sample(49,396)==16777215&&sample(547,495)==16777215,"neighbor and odd crop edge stay clear");
    // A foreign first binding on a shared target must not hide a later valid
    // world binding. One depth copy remains sufficient for this clear interval.
    constants[0]=9;ComPtr<ID3D11Buffer> foreignCb;
    Check(SUCCEEDED(d->CreateBuffer(&bd,&initial,&foreignCb)),"foreign first-binding fixture");
    mp::DepthGpu mixed;mixed.camera(camera,renderView);
    Check(mixed.observe(c.Get(),largeView.Get(),foreignCb.Get(),0),"foreign first binding observed");
    Check(mixed.observe(c.Get(),largeView.Get(),cb.Get(),0),"later world binding observed on the same depth target");
    Check(mixed.beforeClear(c.Get(),largeView.Get(),request,1001),"mixed-bindings capture submitted once");c->Flush();
    mp::DepthResult mixedResult;ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=mixed.poll(c.Get(),mixedResult,1002);if(!ready)Sleep(1);}
    Check(ready,"a later valid world binding must survive a foreign first binding");
    Check(mixed.diagnostics().alternativeBindingMatches==1,"successful alternative has an explicit diagnostic");
    // Dynamic passes can rewrite the SAME CB identity. The first snapshot is
    // foreign while the final observed binding contents own the world camera.
    mp::DepthGpu rewritten;rewritten.camera(camera,renderView);
    constants[0]=9;c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    Check(rewritten.observe(c.Get(),largeView.Get(),cb.Get(),0),"same-buffer foreign first snapshot");
    constants[0]=camera.projection[0];c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    Check(rewritten.observe(c.Get(),largeView.Get(),cb.Get(),0),"same-buffer world update observed");
    Check(rewritten.beforeClear(c.Get(),largeView.Get(),request,1001),"same-buffer final world capture submitted");c->Flush();
    mp::DepthResult rewrittenResult;ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=rewritten.poll(c.Get(),rewrittenResult,1002);if(!ready)Sleep(1);}
    Check(ready,"rewritten constant-buffer identity must not discard later world depth");
    Check(rewritten.diagnostics().intermediateMatches==1,"draw-owned intermediate world snapshot has its own diagnostic");
    // Preserve the first owned snapshot if a later pass changes it to foreign.
    mp::DepthGpu reverse;reverse.camera(camera,renderView);
    Check(reverse.observe(c.Get(),largeView.Get(),cb.Get(),0),"first valid snapshot retained");
    constants[0]=9;c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    Check(reverse.observe(c.Get(),largeView.Get(),cb.Get(),0),"final foreign rewrite observed");
    Check(reverse.beforeClear(c.Get(),largeView.Get(),request,1001),"reverse rewrite submitted");c->Flush();
    mp::DepthResult reverseResult;ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=reverse.poll(c.Get(),reverseResult,1002);if(!ready)Sleep(1);}
    Check(ready,"later foreign rewrite cannot remove the first matching snapshot");
    // The world camera may appear only between two foreign writes to one CB.
    mp::DepthWrites writes;
    mp::DepthGpu intermediate;intermediate.camera(camera,renderView);
    Check(intermediate.observe(c.Get(),largeView.Get(),cb.Get(),0,writes.observe(cb.Get())),"foreign start of intermediate rewrite");
    for(unsigned i=0;i<1000;++i)intermediate.observe(c.Get(),largeView.Get(),cb.Get(),0,writes.observe(cb.Get()));
    Check(intermediate.diagnostics().snapshotCopies==1,"unchanged thousands of draws must not copy camera buffers again");
    constants[0]=camera.projection[0];c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    writes.written(cb.Get());
    Check(intermediate.observe(c.Get(),largeView.Get(),cb.Get(),0,writes.observe(cb.Get())),"intermediate world draw observed");
    constants[0]=8;c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    writes.written(cb.Get());
    Check(intermediate.observe(c.Get(),largeView.Get(),cb.Get(),0,writes.observe(cb.Get())),"foreign end of intermediate rewrite");
    Check(intermediate.beforeClear(c.Get(),largeView.Get(),request,1001),"intermediate rewrite submitted");c->Flush();
    mp::DepthResult intermediateResult;ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=intermediate.poll(c.Get(),intermediateResult,1002);if(!ready)Sleep(1);}
    Check(ready,"world camera between foreign first and final writes must not be lost");
    Check(intermediate.diagnostics().snapshotCopies==3&&intermediate.diagnostics().intermediateMatches==1,"exactly the three writes were snapshotted");
    // The snapshot budget is hard. Overflow never labels a foreign camera as
    // world, and a matching early snapshot survives arbitrarily many rewrites.
    constants[0]=camera.projection[0];c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    mp::DepthGpu bounded;bounded.camera(camera,renderView);
    for(unsigned i=0;i<100;++i){
        if(i){constants[0]=8+float(i);c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);}
        writes.written(cb.Get());bounded.observe(c.Get(),largeView.Get(),cb.Get(),0,writes.observe(cb.Get()));
    }
    Check(bounded.diagnostics().snapshotCopies==mp::DepthDrawSnapshots&&bounded.diagnostics().snapshotOverflow==100-mp::DepthDrawSnapshots,"write burst obeys snapshot capacity");
    Check(bounded.beforeClear(c.Get(),largeView.Get(),request,1001),"bounded burst capture");c->Flush();ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=bounded.poll(c.Get(),intermediateResult,1002);if(!ready)Sleep(1);}
    Check(ready,"overflow retains an owned matching snapshot without weakening camera guards");
    // Exercise maximum 64 KiB CBs and non-zero ranged bindings. Partial copies
    // of constant buffers are illegal in D3D11.0; preserve the complete source.
    std::array<float,16384> rangedData{};constexpr unsigned rangeOffset=128;
    constants[0]=camera.projection[0];std::copy(constants,constants+120,rangedData.begin()+rangeOffset);
    auto rangedDesc=bd;rangedDesc.ByteWidth=65536;D3D11_SUBRESOURCE_DATA rangedInitial{rangedData.data(),0,0};
    ComPtr<ID3D11Buffer> rangedCb;Check(SUCCEEDED(d->CreateBuffer(&rangedDesc,&rangedInitial,&rangedCb)),"maximum sized CB fixture");
    mp::DepthGpu ranged;ranged.camera(camera,renderView);
    Check(ranged.observe(c.Get(),largeView.Get(),rangedCb.Get(),rangeOffset/4),"nonzero 64 KiB CB range");
    Check(ranged.beforeClear(c.Get(),largeView.Get(),request,1001),"ranged camera submitted");c->Flush();ready=false;
    for(unsigned i=0;i<300&&!ready;++i){ready=ranged.poll(c.Get(),intermediateResult,1002);if(!ready)Sleep(1);}
    Check(ready,"full resource copies preserve camera at the nonzero offset");
    constants[0]=camera.projection[0];c->UpdateSubresource(cb.Get(),0,nullptr,constants,0,0);
    c->OMSetRenderTargets(0,nullptr,nullptr);
}
int main(){try{
    {mp::DepthWrites writes;int resources[6]{};
     Check(writes.observe(nullptr)==0,"null writer history stays unknown");
     auto a=writes.observe(resources),b=writes.observe(resources+1);
     writes.written(resources+5);
     Check(writes.observe(resources)==a&&writes.observe(resources+1)==b,"unrelated resource writes do not invalidate watched cameras");
     writes.written(resources);auto changed=writes.observe(resources);
     Check(changed!=a&&writes.observe(resources+1)==b,"only written resource revision changes");
     for(unsigned i=2;i<6;++i)writes.observe(resources+i);
     Check(writes.observe(resources)!=changed,"evicted watch cannot reuse its earlier revision");
     auto before=writes.observe(resources);writes.reset();
     Check(writes.observe(resources)!=before,"reset never reuses a live camera revision");}
    mp::DepthRequest r;r.at=1000;r.epoch=7;r.active=1;r.viewportWidth=1920;r.viewportHeight=1080;r.x=100;r.y=100;r.width=800;r.height=200;
    Check(mp::ValidDepthRequest(r,1001),"valid bounded lyric ROI");
    {auto large=r;large.x=large.y=0;large.width=1920;large.height=1080;
     Check(mp::ValidDepthRequest(large,1001),"large visible ROI admitted with bounded downsampled transport");}
    for(int i=0;i<9;++i){auto bad=r;switch(i){
      case 0:bad.at=1002;break;case 1:bad.at=1;break;case 2:bad.epoch=0;break;
      case 3:bad.active=0;break;case 4:bad.x=1900;break;case 5:bad.width=0;break;
      case 6:bad.height=1081;bad.width=1920;bad.x=bad.y=0;break;
      case 7:bad.viewportHeight=UINT_MAX;break;case 8:bad.magic=0;break;}
      Check(!mp::ValidDepthRequest(bad,1001),"invalid request refused");}
    mp::WorldPacket p;p.valid=1;p.epoch=7;p.projection[0]=1.1f;p.projection[5]=1.7f;p.projection[10]=-1.01f;p.projection[11]=-1;p.projection[14]=-.2f;
    p.view[0]=p.view[5]=p.view[10]=p.view[15]=1;p.view[12]=-100;p.view[13]=-70;p.view[14]=20;
    float c[120]{};std::copy(p.projection,p.projection+16,c);std::copy(p.view,p.view+16,c+16);
    // Native GPU Z conversion is allowed; XY and view ownership must match.
    c[10]=-.999f;c[14]=-.1f;
    Check(mp::DepthCameraMatches(c,p),"native depth convention with same camera");
    for(int i=0;i<4;++i){auto copy=std::vector<float>(c,c+120);copy[i==0?0:i==1?16:i==2?28:5]=i==3?NAN:999;
      Check(!mp::DepthCameraMatches(copy.data(),p),"foreign camera cannot label depth");}
    float relative[16]{};relative[0]=relative[5]=relative[10]=relative[15]=1;
    std::copy(relative,relative+16,c+16);
    Check(!mp::DepthCameraMatches(c,p),"relative GPU view differs from world camera");
    Check(mp::DepthCameraMatches(c,p,relative),"paired render view admits an origin-relative GPU camera");
    c[28]=1;Check(!mp::DepthCameraMatches(c,p,relative),"foreign relative translation still refused");c[28]=0;
    relative[0]=.5;Check(!mp::DepthCameraMatches(c,p,relative),"pair must retain world camera basis");relative[0]=1;
    Check(!mp::DepthCameraMatches(c,p,nullptr),"missing paired render view refused");
    GpuCheck();std::cout<<"Depth policy and real GPU "<<checks<<" checks passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
