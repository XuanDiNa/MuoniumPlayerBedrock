#include "CompositionGpu.h"
#include <wrl/client.h>
#include <d3dcompiler.h>
#include <cstring>
#include <algorithm>
namespace mp {using Microsoft::WRL::ComPtr;namespace {
constexpr char shader[]=R"(
cbuffer Camera:register(b0){column_major float4x4 projection;column_major float4x4 nativeView;};
cbuffer Layer:register(b1){column_major float4x4 rotation;column_major float4x4 expectedView;column_major float4x4 expectedProjection;
 float4 origin;float4 right;float4 down;float4 bounds;float4 tile;float4 screen;uint4 options;};
struct Vertex{float4 position:SV_Position;float2 uv:TEXCOORD0;};
Vertex flat(uint id:SV_VertexID){float2 a[6]={float2(0,0),float2(1,0),float2(0,1),float2(0,1),float2(1,0),float2(1,1)};
 Vertex o;float2 uv=a[id];float2 pixel=screen.xy+uv*screen.zw;o.position=float4(pixel.x/options.x*2-1,1-pixel.y/options.y*2,0,1);o.uv=uv;return o;}
Vertex world(uint id:SV_VertexID){Vertex o=flat(id);float2 uv=o.uv;float2 local=bounds.xy+uv*bounds.zw;
 float3 p=origin.xyz+right.xyz*local.x+down.xyz*local.y;o.position=mul(projection,mul(rotation,float4(p,1)));
 bool valid=true;[unroll]for(int col=0;col<4;col++)[unroll]for(int row=0;row<4;row++){
 if(abs(nativeView[row][col]-expectedView[row][col])>(col==3&&row<3?.01:.0001))valid=false;
 if(!(col==2&&row==2)&&!(col==3&&row==2)&&abs(projection[row][col]-expectedProjection[row][col])>.0005)valid=false;}
 if(!valid||abs(projection[3][2]+1)>.001||projection[2][3]>=0)o.position=float4(0,0,-1,1);
 o.uv=(tile.xy+uv*tile.zw)/float2(2048,options.w);return o;}
Texture2D<float4> pixels:register(t0);SamplerState linearClamp:register(s0);
#ifdef MS
Texture2DMS<float> depth:register(t1);
#else
Texture2D<float> depth:register(t1);
#endif
float4 plain(Vertex i):SV_Target{return pixels.Sample(linearClamp,i.uv);}
float4 masked(Vertex i):SV_Target{
 if(options.z){float z=1;
#ifdef MS
 uint w,h,count;depth.GetDimensions(w,h,count);for(uint s=0;s<count;s++)z=min(z,depth.Load(int2(i.position.xy),s));
#else
 z=depth.Load(int3(i.position.xy,0));
#endif
 if(i.position.z>z+.000002)discard;}
 return pixels.Sample(linearClamp,i.uv);}
)";
using Compile=HRESULT(WINAPI*)(LPCVOID,SIZE_T,LPCSTR,D3D_SHADER_MACRO const*,ID3DInclude*,LPCSTR,LPCSTR,UINT,UINT,ID3DBlob**,ID3DBlob**);
bool Bytecode(char const* entry,char const* target,bool ms,ComPtr<ID3DBlob>& code){
    static auto dll=LoadLibraryExW(L"d3dcompiler_47.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto compile=dll?reinterpret_cast<Compile>(GetProcAddress(dll,"D3DCompile")):nullptr;
    D3D_SHADER_MACRO defs[]{{"MS","1"},{nullptr,nullptr}};ComPtr<ID3DBlob> error;
    return compile&&SUCCEEDED(compile(shader,sizeof shader-1,"owned-composition",ms?defs:nullptr,nullptr,entry,target,D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&error));
}
struct Constants {float rotation[16],view[16],projection[16],origin[4],right[4],down[4],bounds[4],tile[4],screen[4];UINT options[4];};
}
struct CompositionGpu::State {
    CompositionGpuStatus diagnostic;
    ComPtr<ID3D11Device> device;ComPtr<ID3D11Device1> device1;ComPtr<ID3D11DeviceContext1> context;
    ComPtr<ID3DDeviceContextState> state;ComPtr<ID3D11VertexShader> flat,world;
    ComPtr<ID3D11PixelShader> plain,masked,maskedMs;ComPtr<ID3D11SamplerState> sampler;
    ComPtr<ID3D11BlendState> blend;ComPtr<ID3D11RasterizerState> raster;ComPtr<ID3D11DepthStencilState> noDepth;
    ComPtr<ID3D11Buffer> constants,camera,verifiedCamera;ComPtr<ID3D11Texture2D> screen,atlas,depth;
    ComPtr<ID3D11ShaderResourceView> screenSrv,atlasSrv,depthSrv;
    CompositionHeader header;WorldPacket worldCamera;float pairedView[16]{};bool depthReady{},initialized{};UINT samples{1};
    bool select(ID3D11DeviceContext* c){
        if(context)return initialized&&context.Get()==c;if(!c||c->GetType()!=D3D11_DEVICE_CONTEXT_IMMEDIATE)return false;
        diagnostic.stage="interfaces";c->GetDevice(&device);diagnostic.deviceFlags=device->GetCreationFlags();
        if(FAILED(diagnostic.result=device.As(&device1))||FAILED(diagnostic.result=c->QueryInterface(IID_PPV_ARGS(&context))))return false;
        auto level=device->GetFeatureLevel();diagnostic.featureLevel=level;if(level<D3D_FEATURE_LEVEL_11_0)return false;
        D3D_FEATURE_LEVEL chosen;
        // The isolated context state must match the owning device's threading
        // contract. D3D11 rejects flags=0 for SINGLETHREADED devices.
        UINT stateFlags=(device->GetCreationFlags()&D3D11_CREATE_DEVICE_SINGLETHREADED)?D3D11_1_CREATE_DEVICE_CONTEXT_STATE_SINGLETHREADED:0;
        diagnostic.stage="context-state";
        if(FAILED(diagnostic.result=device1->CreateDeviceContextState(stateFlags,&level,1,D3D11_SDK_VERSION,__uuidof(ID3D11Device),&chosen,&state)))return false;
        diagnostic.stage="shader-flat";
        ComPtr<ID3DBlob> b;
        if(!Bytecode("flat","vs_5_0",false,b)||FAILED(device->CreateVertexShader(b->GetBufferPointer(),b->GetBufferSize(),nullptr,&flat)))return false;
        diagnostic.stage="shader-world";b.Reset();if(!Bytecode("world","vs_5_0",false,b)||FAILED(device->CreateVertexShader(b->GetBufferPointer(),b->GetBufferSize(),nullptr,&world)))return false;
        diagnostic.stage="shader-plain";b.Reset();if(!Bytecode("plain","ps_5_0",false,b)||FAILED(device->CreatePixelShader(b->GetBufferPointer(),b->GetBufferSize(),nullptr,&plain)))return false;
        diagnostic.stage="shader-masked";b.Reset();if(!Bytecode("masked","ps_5_0",false,b)||FAILED(device->CreatePixelShader(b->GetBufferPointer(),b->GetBufferSize(),nullptr,&masked)))return false;
        diagnostic.stage="shader-masked-ms";b.Reset();if(!Bytecode("masked","ps_5_0",true,b)||FAILED(device->CreatePixelShader(b->GetBufferPointer(),b->GetBufferSize(),nullptr,&maskedMs)))return false;
        diagnostic.stage="resources";
        D3D11_BUFFER_DESC bd{};bd.ByteWidth=sizeof(Constants);bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
        if(FAILED(device->CreateBuffer(&bd,nullptr,&constants)))return false;
        D3D11_SAMPLER_DESC sd{};sd.Filter=D3D11_FILTER_MIN_MAG_MIP_LINEAR;sd.AddressU=sd.AddressV=sd.AddressW=D3D11_TEXTURE_ADDRESS_CLAMP;sd.MaxLOD=D3D11_FLOAT32_MAX;
        if(FAILED(device->CreateSamplerState(&sd,&sampler)))return false;
        D3D11_BLEND_DESC bl{};auto& r=bl.RenderTarget[0];r.BlendEnable=TRUE;r.SrcBlend=r.SrcBlendAlpha=D3D11_BLEND_ONE;
        r.DestBlend=r.DestBlendAlpha=D3D11_BLEND_INV_SRC_ALPHA;r.BlendOp=r.BlendOpAlpha=D3D11_BLEND_OP_ADD;r.RenderTargetWriteMask=15;
        if(FAILED(device->CreateBlendState(&bl,&blend)))return false;
        D3D11_RASTERIZER_DESC rd{};rd.FillMode=D3D11_FILL_SOLID;rd.CullMode=D3D11_CULL_NONE;rd.DepthClipEnable=TRUE;
        if(FAILED(device->CreateRasterizerState(&rd,&raster)))return false;
        D3D11_DEPTH_STENCIL_DESC dd{};dd.DepthEnable=FALSE;
        initialized=SUCCEEDED(diagnostic.result=device->CreateDepthStencilState(&dd,&noDepth));
        diagnostic.ready=initialized;if(initialized)diagnostic.stage="ready";return initialized;
    }
    bool texture(ComPtr<ID3D11Texture2D>& t,ComPtr<ID3D11ShaderResourceView>& srv,UINT w,UINT h,void const* bytes){
        D3D11_TEXTURE2D_DESC td{};if(t)t->GetDesc(&td);
        if(!t||td.Width!=w||td.Height!=h){t.Reset();srv.Reset();td={};td.Width=w;td.Height=h;td.MipLevels=td.ArraySize=td.SampleDesc.Count=1;
            td.Format=DXGI_FORMAT_B8G8R8A8_UNORM;td.BindFlags=D3D11_BIND_SHADER_RESOURCE;
            if(FAILED(device->CreateTexture2D(&td,nullptr,&t))||FAILED(device->CreateShaderResourceView(t.Get(),nullptr,&srv)))return false;}
        context->UpdateSubresource(t.Get(),0,nullptr,bytes,w*4,0);return true;
    }
};
CompositionGpu::CompositionGpu():s(std::make_unique<State>()){}CompositionGpu::~CompositionGpu()=default;
void CompositionGpu::reset(){s=std::make_unique<State>();}
CompositionGpuStatus CompositionGpu::status() const noexcept{return s->diagnostic;}
bool CompositionGpu::upload(ID3D11DeviceContext* c,CompositionFrame const& f){
    auto const& h=f.header;if(h.version!=1||!ValidComposition(h,h.at)||!s->select(c))return false;
    if(h.width&&f.screen.size()!=std::size_t(h.width)*h.height*4||h.planeCount&&f.atlas.size()!=CompositionAtlas*std::size_t(h.atlasHeight)*4)return false;
    if(h.width&&!s->texture(s->screen,s->screenSrv,h.width,h.height,f.screen.data()))return false;
    if(h.planeCount&&!s->texture(s->atlas,s->atlasSrv,CompositionAtlas,h.atlasHeight,f.atlas.data()))return false;
    s->header=h;return true;
}
bool CompositionGpu::capture(ID3D11DeviceContext* c,ID3D11DepthStencilView* dsv,ID3D11Buffer* cb,UINT first,WorldPacket const& w,float const* paired){
    if(!w.valid||!w.epoch||!paired||!cb||!dsv||!s->select(c))return false;
    D3D11_DEPTH_STENCIL_VIEW_DESC vd{};dsv->GetDesc(&vd);if(vd.Flags||(vd.ViewDimension!=D3D11_DSV_DIMENSION_TEXTURE2D&&vd.ViewDimension!=D3D11_DSV_DIMENSION_TEXTURE2DMS))return false;
    ComPtr<ID3D11Resource> resource;dsv->GetResource(&resource);ComPtr<ID3D11Texture2D> texture;if(FAILED(resource.As(&texture)))return false;
    D3D11_TEXTURE2D_DESC td{};texture->GetDesc(&td);
    auto const& h=s->header;if(td.Width!=h.viewportWidth||td.Height!=h.viewportHeight||td.MipLevels!=1||td.ArraySize!=1||td.SampleDesc.Quality)return false;
    DXGI_FORMAT format{},sample{};
    if(vd.Format==DXGI_FORMAT_D32_FLOAT){format=DXGI_FORMAT_R32_TYPELESS;sample=DXGI_FORMAT_R32_FLOAT;}
    else if(vd.Format==DXGI_FORMAT_D24_UNORM_S8_UINT){format=DXGI_FORMAT_R24G8_TYPELESS;sample=DXGI_FORMAT_R24_UNORM_X8_TYPELESS;}else return false;
    D3D11_BUFFER_DESC bd{};cb->GetDesc(&bd);if(first>4096||std::uint64_t(first)*16+128>bd.ByteWidth||bd.ByteWidth>65536)return false;
    // D3D11.0 CB copies are whole-buffer only. Keep one bounded copy per clear,
    // with Context1 range binding for the original draw's offset.
    D3D11_BUFFER_DESC oldb{};if(s->camera)s->camera->GetDesc(&oldb);
    if(!s->camera||oldb.ByteWidth!=bd.ByteWidth){s->camera.Reset();bd.Usage=D3D11_USAGE_DEFAULT;bd.CPUAccessFlags=bd.MiscFlags=bd.StructureByteStride=0;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
        if(FAILED(s->device->CreateBuffer(&bd,nullptr,&s->camera)))return false;}
    if(first!=0)return false; // First candidate supports only the proven whole b0 layout.
    D3D11_TEXTURE2D_DESC old{};if(s->depth)s->depth->GetDesc(&old);
    if(!s->depth||td.Width!=old.Width||td.Height!=old.Height||old.Format!=format||td.SampleDesc.Count!=old.SampleDesc.Count){
        s->depth.Reset();s->depthSrv.Reset();auto cd=td;cd.Format=format;cd.Usage=D3D11_USAGE_DEFAULT;cd.BindFlags=D3D11_BIND_SHADER_RESOURCE;cd.CPUAccessFlags=cd.MiscFlags=0;
        if(FAILED(s->device->CreateTexture2D(&cd,nullptr,&s->depth)))return false;
        D3D11_SHADER_RESOURCE_VIEW_DESC sd{};sd.Format=sample;sd.ViewDimension=td.SampleDesc.Count==1?D3D11_SRV_DIMENSION_TEXTURE2D:D3D11_SRV_DIMENSION_TEXTURE2DMS;sd.Texture2D.MipLevels=1;
        if(FAILED(s->device->CreateShaderResourceView(s->depth.Get(),&sd,&s->depthSrv)))return false;}
    c->CopyResource(s->depth.Get(),texture.Get());c->CopyResource(s->camera.Get(),cb);
    s->worldCamera=w;std::copy(paired,paired+16,s->pairedView);s->samples=td.SampleDesc.Count;s->depthReady=true;return true;
}
bool CompositionGpu::captureVerified(ID3D11DeviceContext* c,ID3D11DepthStencilView* dsv,WorldPacket const& world,float const* view){
    if(!s->select(c)||!world.valid||!view)return false;
    float values[120]{};std::copy(world.projection,world.projection+16,values);std::copy(view,view+16,values+16);
    D3D11_BUFFER_DESC bd{};bd.ByteWidth=sizeof values;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    // Reused dedicated CPU-owned constant buffer, independent of game rewrites.
    if(!s->verifiedCamera&&FAILED(s->device->CreateBuffer(&bd,nullptr,&s->verifiedCamera)))return false;
    c->UpdateSubresource(s->verifiedCamera.Get(),0,nullptr,values,0,0);
    return capture(c,dsv,s->verifiedCamera.Get(),0,world,view);
}
bool CompositionGpu::present(ID3D11DeviceContext* c,ID3D11RenderTargetView* rt,UINT w,UINT h,std::uint64_t now){
    bool ready=s->depthReady;s->depthReady=false;auto const& head=s->header;
    if(!rt||!s->select(c)||!ValidComposition(head,now)||!head.active||head.reserved==CompositionProbeOnly||head.viewportWidth!=w||head.viewportHeight!=h)return false;
    auto const& world=s->worldCamera;
    // Flat initialization is not proof that the requested world image exists.
    // The caller must retain its visible fallback until the whole frame is ready.
    bool worldReady=ready&&world.valid&&world.at<=now&&now-world.at<=100&&head.epoch==world.epoch&&head.dimension==world.dimension;
    if(head.planeCount&&!worldReady)return false;
    ComPtr<ID3DDeviceContextState> previous;s->context->SwapDeviceContextState(s->state.Get(),&previous);
    struct Restore{ID3D11DeviceContext1* c;ID3DDeviceContextState* state;~Restore(){c->SwapDeviceContextState(state,nullptr);}} restore{s->context.Get(),previous.Get()};
    c->OMSetRenderTargets(1,&rt,nullptr);c->OMSetBlendState(s->blend.Get(),nullptr,UINT_MAX);c->OMSetDepthStencilState(s->noDepth.Get(),0);
    c->RSSetState(s->raster.Get());D3D11_VIEWPORT vp{0,0,float(w),float(h),0,1};c->RSSetViewports(1,&vp);
    c->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);c->IASetInputLayout(nullptr);
    auto constants=s->constants.Get();c->VSSetConstantBuffers(1,1,&constants);c->PSSetConstantBuffers(1,1,&constants);auto sampler=s->sampler.Get();c->PSSetSamplers(0,1,&sampler);
    Constants values{};values.options[0]=w;values.options[1]=h;values.options[3]=head.atlasHeight;
    if(head.planeCount&&worldReady){
        std::copy(world.view,world.view+16,values.rotation);values.rotation[12]=values.rotation[13]=values.rotation[14]=0;
        std::copy(world.projection,world.projection+16,values.projection);std::copy(s->pairedView,s->pairedView+16,values.view);
        values.options[2]=head.occlusion;auto camera=s->camera.Get();c->VSSetConstantBuffers(0,1,&camera);
        c->VSSetShader(s->world.Get(),nullptr,0);c->PSSetShader(s->samples==1?s->masked.Get():s->maskedMs.Get(),nullptr,0);
        ID3D11ShaderResourceView* srvs[]{s->atlasSrv.Get(),s->depthSrv.Get()};c->PSSetShaderResources(0,2,srvs);
        for(unsigned i=0;i<head.planeCount;++i){auto const& p=head.planes[i];for(int k=0;k<3;++k){values.origin[k]=float(p.position[k]-world.eye[k]);values.right[k]=p.right[k];values.down[k]=p.down[k];}
            std::copy(p.bounds,p.bounds+4,values.bounds);for(int k=0;k<4;++k)values.tile[k]=float(p.tile[k]);
            c->UpdateSubresource(constants,0,nullptr,&values,0,0);c->Draw(6,0);}
    }
    if(head.width){values.screen[0]=float(head.x);values.screen[1]=float(head.y);values.screen[2]=float(head.width);values.screen[3]=float(head.height);
        c->UpdateSubresource(constants,0,nullptr,&values,0,0);c->VSSetShader(s->flat.Get(),nullptr,0);c->PSSetShader(s->plain.Get(),nullptr,0);
        ID3D11ShaderResourceView* srvs[]{s->screenSrv.Get(),nullptr};c->PSSetShaderResources(0,2,srvs);c->Draw(6,0);}
    return true;
}
}
