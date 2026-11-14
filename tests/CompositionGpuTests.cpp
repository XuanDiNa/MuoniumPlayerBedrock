#include "CompositionGpu.h"
#include <wrl/client.h>
#include <d3dcompiler.h>
#include <iostream>
#include <stdexcept>
using Microsoft::WRL::ComPtr;
static unsigned checks;
static void Check(bool v,char const* msg){++checks;if(!v)throw std::runtime_error(msg);}
int main(int argc,char**){try{
    auto dll=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto create=reinterpret_cast<decltype(&D3D11CreateDevice)>(GetProcAddress(dll,"D3D11CreateDevice"));
    ComPtr<ID3D11Device>d;ComPtr<ID3D11DeviceContext>c;
    auto driver=argc>1?D3D_DRIVER_TYPE_HARDWARE:D3D_DRIVER_TYPE_WARP;
    UINT flags=argc>2?D3D11_CREATE_DEVICE_SINGLETHREADED:0;
    Check(create&&SUCCEEDED(create(nullptr,driver,nullptr,flags,nullptr,0,D3D11_SDK_VERSION,&d,nullptr,&c)),"owned D3D11 device");
    std::cout<<"GPU fixture driver="<<(argc>1?"hardware":"WARP")<<" flags="<<d->GetCreationFlags()<<" featureLevel="<<std::hex<<d->GetFeatureLevel()<<std::dec<<'\n';
    D3D11_TEXTURE2D_DESC td{};td.Width=td.Height=64;td.MipLevels=td.ArraySize=td.SampleDesc.Count=1;td.Format=DXGI_FORMAT_R8G8B8A8_UNORM;td.BindFlags=D3D11_BIND_RENDER_TARGET;
    ComPtr<ID3D11Texture2D>color;ComPtr<ID3D11RenderTargetView>rt;
    Check(SUCCEEDED(d->CreateTexture2D(&td,nullptr,&color))&&SUCCEEDED(d->CreateRenderTargetView(color.Get(),nullptr,&rt)),"owned backbuffer");
    float blue[]{0,0,1,1};c->ClearRenderTargetView(rt.Get(),blue);
    mp::CompositionFrame f;auto& h=f.header;h.sequence=1;h.at=1000;h.active=1;h.viewportWidth=h.viewportHeight=64;h.x=4;h.y=8;h.width=h.height=16;
    f.screen.resize(16*16*4);for(unsigned n=0;n<f.screen.size();n+=4){f.screen[n+2]=128;f.screen[n+3]=128;}
    mp::CompositionGpu gpu;
    {mp::CompositionFrame packed;auto& v=packed.header;v.version=2;v.sequence=1;v.at=1000;v.active=1;
        v.viewportWidth=v.viewportHeight=64;v.width=v.height=1;v.bandCount=1;v.bands[0]={0,0,1,1,0};packed.screen.assign(4,0);
        Check(mp::ValidComposition(v,1000)&&!gpu.upload(c.Get(),packed),"legacy D3D11 must explicitly refuse packed v2");}
    Check(gpu.upload(c.Get(),f),"upload premultiplied crop");
    D3D11_VIEWPORT vp{3,4,50,51,.1f,.9f};c->RSSetViewports(1,&vp);
    c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
    ComPtr<ID3D11DeviceContext1> c1;c.As(&c1);D3D11_BUFFER_DESC originalDesc{};originalDesc.ByteWidth=1024;originalDesc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    ComPtr<ID3D11Buffer> originalCb;d->CreateBuffer(&originalDesc,nullptr,&originalCb);auto originalRaw=originalCb.Get();UINT firstConstant=16,constantCount=16;
    c1->VSSetConstantBuffers1(1,1,&originalRaw,&firstConstant,&constantCount);
    ComPtr<ID3D11Predicate> predicate;D3D11_QUERY_DESC predicateDesc{D3D11_QUERY_OCCLUSION_PREDICATE,0};d->CreatePredicate(&predicateDesc,&predicate);
    c->Begin(predicate.Get());c->End(predicate.Get());c->SetPredication(predicate.Get(),FALSE);
    Check(gpu.present(c.Get(),rt.Get(),64,64,1001),"native composition");
    D3D11_VIEWPORT restored{};UINT n=1;c->RSGetViewports(&n,&restored);Check(restored.TopLeftX==3&&restored.MinDepth==.1f,"viewport restored");
    D3D11_PRIMITIVE_TOPOLOGY topology;c->IAGetPrimitiveTopology(&topology);Check(topology==D3D11_PRIMITIVE_TOPOLOGY_LINELIST,"topology restored");
    ComPtr<ID3D11Buffer> restoredCb;UINT restoredFirst{},restoredCount{};c1->VSGetConstantBuffers1(1,1,&restoredCb,&restoredFirst,&restoredCount);
    Check(restoredCb==originalCb&&restoredFirst==16&&restoredCount==16,"ranged camera constant buffer restored");
    ComPtr<ID3D11Predicate> restoredPredicate;BOOL restoredValue{};c->GetPredication(&restoredPredicate,&restoredValue);
    Check(restoredPredicate==predicate&&restoredValue==FALSE,"game predication restored");c->SetPredication(nullptr,FALSE);
    td.Usage=D3D11_USAGE_STAGING;td.BindFlags=0;td.CPUAccessFlags=D3D11_CPU_ACCESS_READ;ComPtr<ID3D11Texture2D>readback;
    Check(SUCCEEDED(d->CreateTexture2D(&td,nullptr,&readback)),"fixture readback");
    c->CopyResource(readback.Get(),color.Get());D3D11_MAPPED_SUBRESOURCE map{};Check(SUCCEEDED(c->Map(readback.Get(),0,D3D11_MAP_READ,0,&map)),"fixture map");
    auto p=static_cast<unsigned char*>(map.pData)+10*map.RowPitch+10*4;
    Check(p[0]==128&&p[1]==0&&p[2]>=126&&p[2]<=128,"ONE alpha blend and top-left placement");
    p=static_cast<unsigned char*>(map.pData)+40*map.RowPitch+40*4;Check(p[0]==0&&p[2]==255,"outside crop untouched");c->Unmap(readback.Get(),0);
    // An empty occlusion query must not suppress the independent player UI.
    // This result is false; TRUE predication deliberately skips game draws.
    c->SetPredication(predicate.Get(),TRUE);c->ClearRenderTargetView(rt.Get(),blue);
    Check(gpu.present(c.Get(),rt.Get(),64,64,1001),"composition under rejecting game predication");
    c->GetPredication(&restoredPredicate,&restoredValue);
    Check(restoredPredicate==predicate&&restoredValue==TRUE,"rejecting game predicate restored");
    c->SetPredication(nullptr,FALSE);c->CopyResource(readback.Get(),color.Get());
    Check(SUCCEEDED(c->Map(readback.Get(),0,D3D11_MAP_READ,0,&map)),"predicate readback");
    p=static_cast<unsigned char*>(map.pData)+10*map.RowPitch+10*4;
    bool predicateVisible=p[0]==128&&p[1]==0&&p[2]>=126&&p[2]<=128;c->Unmap(readback.Get(),0);
    Check(predicateVisible,"game occlusion predicate cannot hide composition");
    Check(!gpu.present(c.Get(),rt.Get(),64,64,1800),"stale producer not displayed");
    auto nativeCrop=f;nativeCrop.header.reserved=mp::CompositionProbeOnly;
    Check(gpu.upload(c.Get(),nativeCrop),"probe can initialize renderer without output ownership");
    c->ClearRenderTargetView(rt.Get(),blue);
    Check(!gpu.present(c.Get(),rt.Get(),64,64,1001),"probe cannot overlay an external copy");
    // Stationary lyric texture, moving opaque foreground, native current camera.
    auto pixel=[&](unsigned x,unsigned y){c->CopyResource(readback.Get(),color.Get());D3D11_MAPPED_SUBRESOURCE m{};
        Check(SUCCEEDED(c->Map(readback.Get(),0,D3D11_MAP_READ,0,&m)),"test read");
        auto bytes=static_cast<unsigned char*>(m.pData)+y*m.RowPitch+x*4;std::array<unsigned char,4> out{};std::copy(bytes,bytes+4,out.begin());c->Unmap(readback.Get(),0);return out;};
    h.width=h.height=0;h.planeCount=1;h.atlasHeight=64;h.epoch=7;h.occlusion=1;
    auto& plane=h.planes[0];plane.position[2]=-2;plane.right[0]=.02f;plane.down[1]=-.02f;
    plane.bounds[0]=plane.bounds[1]=-50;plane.bounds[2]=plane.bounds[3]=100;plane.tile[2]=plane.tile[3]=64;
    f.atlas.assign(2048*64*4,0);for(unsigned y=0;y<64;++y)for(unsigned x=0;x<64;++x){auto at=(y*2048+x)*4;f.atlas[at+2]=255;f.atlas[at+3]=255;}
    Check(gpu.upload(c.Get(),f),"world atlas upload");
    Check(!gpu.present(c.Get(),rt.Get(),64,64,1002),"missing native depth cannot claim world lyrics displayed");
    mp::WorldPacket world;world.valid=1;world.epoch=7;world.at=1000;world.view[0]=world.view[5]=world.view[10]=world.view[15]=1;
    world.projection[0]=world.projection[5]=1;world.projection[10]=-1.01f;world.projection[11]=-1;world.projection[14]=-.2f;
    float constants[120]{};std::copy(world.projection,world.projection+16,constants);std::copy(world.view,world.view+16,constants+16);
    D3D11_BUFFER_DESC bd{};bd.ByteWidth=sizeof constants;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;D3D11_SUBRESOURCE_DATA initial{constants,0,0};ComPtr<ID3D11Buffer>cb;
    Check(SUCCEEDED(d->CreateBuffer(&bd,&initial,&cb)),"owned world constants");
    for(unsigned move=0;move<8;++move){
        D3D11_TEXTURE2D_DESC depthDesc{};depthDesc.Width=depthDesc.Height=64;depthDesc.MipLevels=depthDesc.ArraySize=depthDesc.SampleDesc.Count=1;depthDesc.Format=DXGI_FORMAT_D32_FLOAT;depthDesc.BindFlags=D3D11_BIND_DEPTH_STENCIL;
        std::vector<float> z(64*64,1);unsigned edge=26+move;for(unsigned y=0;y<64;++y)for(unsigned x=0;x<edge;++x)z[y*64+x]=.25f;
        D3D11_SUBRESOURCE_DATA data{z.data(),64*sizeof(float),0};ComPtr<ID3D11Texture2D>depth;ComPtr<ID3D11DepthStencilView>dsv;
        Check(SUCCEEDED(d->CreateTexture2D(&depthDesc,&data,&depth))&&SUCCEEDED(d->CreateDepthStencilView(depth.Get(),nullptr,&dsv)),"moving entity depth");
        Check(gpu.capture(c.Get(),dsv.Get(),cb.Get(),0,world,world.view),"current world capture");
        c->ClearDepthStencilView(dsv.Get(),D3D11_CLEAR_DEPTH,1,0);c->ClearRenderTargetView(rt.Get(),blue);
        Check(gpu.present(c.Get(),rt.Get(),64,64,1002),"world composition");
        Check(pixel(edge-1,32)[2]==255,"foreground silhouette keeps game blue");
        Check(pixel(edge+1,32)[0]==255,"unoccluded plane keeps red");
    }
    auto compiler=LoadLibraryExW(L"d3dcompiler_47.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto compile=reinterpret_cast<decltype(&D3DCompile)>(GetProcAddress(compiler,"D3DCompile"));
    char const source[]="float4 main(uint n:SV_VertexID):SV_Position{float2 p[3]={float2(-1,-1),float2(-1,3),float2(3,-1)};return float4(p[n],.25,1);}";
    ComPtr<ID3DBlob> code,error;Check(SUCCEEDED(compile(source,sizeof source-1,nullptr,nullptr,nullptr,"main","vs_5_0",0,0,&code,&error)),"foreground fixture compile");
    ComPtr<ID3D11VertexShader>foreground;Check(SUCCEEDED(d->CreateVertexShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,&foreground)),"foreground fixture shader");
    for(auto format:{DXGI_FORMAT_D32_FLOAT,DXGI_FORMAT_D24_UNORM_S8_UINT})for(UINT samples:{1u,2u,4u}){
        D3D11_TEXTURE2D_DESC dd{};dd.Width=dd.Height=64;dd.MipLevels=dd.ArraySize=1;dd.SampleDesc.Count=samples;dd.Format=format;dd.BindFlags=D3D11_BIND_DEPTH_STENCIL;
        ComPtr<ID3D11Texture2D>depth;ComPtr<ID3D11DepthStencilView>view;
        Check(SUCCEEDED(d->CreateTexture2D(&dd,nullptr,&depth))&&SUCCEEDED(d->CreateDepthStencilView(depth.Get(),nullptr,&view)),"D24/D32 MSAA fixture");
        c->ClearDepthStencilView(view.Get(),D3D11_CLEAR_DEPTH,1,0);c->OMSetRenderTargets(0,nullptr,view.Get());
        D3D11_RASTERIZER_DESC rd{};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_NONE;rd.DepthClipEnable=rd.ScissorEnable=rd.MultisampleEnable=TRUE;
        ComPtr<ID3D11RasterizerState>raster;d->CreateRasterizerState(&rd,&raster);c->RSSetState(raster.Get());D3D11_RECT rect{0,0,32,64};c->RSSetScissorRects(1,&rect);
        D3D11_VIEWPORT full{0,0,64,64,0,1};c->RSSetViewports(1,&full);c->VSSetShader(foreground.Get(),nullptr,0);c->PSSetShader(nullptr,nullptr,0);c->IASetInputLayout(nullptr);c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);c->Draw(3,0);
        Check(gpu.captureVerified(c.Get(),view.Get(),world,world.view),"verified same-frame GPU depth, D24/D32 MSAA");
        c->ClearDepthStencilView(view.Get(),D3D11_CLEAR_DEPTH,1,0);c->ClearRenderTargetView(rt.Get(),blue);
        Check(gpu.present(c.Get(),rt.Get(),64,64,1002),"native D24/D32 MSAA composition");
        Check(pixel(26,32)[2]==255&&pixel(38,32)[0]==255,"MSAA silhouette does not hide whole row");
    }
    std::cout<<"Native composition GPU "<<checks<<" checks passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<'\n';return 1;}}
