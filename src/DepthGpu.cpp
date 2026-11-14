#include "DepthGpu.h"
#include <wrl/client.h>
#include <d3dcompiler.h>
#include <array>
#include <algorithm>
#include <cmath>
#include <cstring>
namespace mp {
using Microsoft::WRL::ComPtr;
unsigned DepthSampleStep(unsigned w,unsigned h) noexcept {
    unsigned step=1;
    while(step<8&&((std::uint64_t(w)+step-1)/step)*((std::uint64_t(h)+step-1)/step)>DepthMaxPixels)step*=2;
    return step;
}
bool ValidDepthRequest(DepthRequest const& r,std::uint64_t now) noexcept {
    return r.magic==0x5153504d&&r.version==2&&r.active==1&&r.epoch>0&&r.at>0&&r.at<=now&&now-r.at<=250
        &&r.viewportWidth>0&&r.viewportWidth<=8192&&r.viewportHeight>0&&r.viewportHeight<=8192
        &&r.width>0&&r.height>0&&r.width<=r.viewportWidth&&r.height<=r.viewportHeight
        &&r.x<=r.viewportWidth-r.width&&r.y<=r.viewportHeight-r.height
        &&std::uint64_t(r.viewportWidth)*r.viewportHeight<=16777216;
}
namespace {
bool ValidRenderView(WorldPacket const& p,float const* view) noexcept {
    if(!view||!p.valid||!p.epoch)return false;
    for(unsigned i=0;i<16;++i){
        if(!std::isfinite(view[i])||!std::isfinite(p.view[i]))return false;
        if((i<12||i==15)&&std::abs(view[i]-p.view[i])>.0001f)return false;
    }
    return true;
}
}
bool DepthCameraMatches(float const* c,WorldPacket const& p) noexcept {return DepthCameraMatches(c,p,p.view);}
bool DepthCameraMatches(float const* c,WorldPacket const& p,float const* renderView) noexcept {
    if(!c||!ValidRenderView(p,renderView))return false;
    for(unsigned i=0;i<32;++i)if(!std::isfinite(c[i]))return false;
    // The real shader constants, not the pre-render matrix, own GPU clip Z.
    // Match the complete render-relative view copied from this pre-render's
    // ScreenContext. The paired world camera owns the absolute lyric origin.
    for(unsigned i=0;i<16;++i){
        if(i!=10&&i!=14&&std::abs(c[i]-p.projection[i])>0.0005f)return false;
        if(std::abs(c[16+i]-renderView[i])>(i>=12&&i<15?0.01f:0.0001f))return false;
    }
    return std::abs(c[11]+1)<.001f&&c[0]>.01f&&c[5]>.01f&&c[14]<0;
}
namespace {
constexpr char code[]=R"(
#ifdef MULTISAMPLE
Texture2DMS<float> source : register(t0);
#else
Texture2D<float> source : register(t0);
#endif
RWTexture2D<float4> target : register(u0);
cbuffer Crop : register(b0) { uint4 rect; uint sampleCount; uint sampleStep; uint2 padding; };
[numthreads(8,8,1)] void main(uint3 id:SV_DispatchThreadID) {
 uint2 cell=id.xy*sampleStep;
 if(cell.x>=rect.z||cell.y>=rect.w)return;
 float z=1;
 for(uint y=0;y<sampleStep&&cell.y+y<rect.w;++y)for(uint x=0;x<sampleStep&&cell.x+x<rect.z;++x){
 uint2 p=cell+uint2(x,y)+rect.xy;
#ifdef MULTISAMPLE
 for(uint s=0;s<sampleCount;++s)z=min(z,source.Load(p,s));
#else
 z=min(z,source.Load(int3(p,0)));
#endif
 }
 uint q=(uint)round(saturate(z)*16777215.0);
 target[id.xy]=float4((q>>16)&255,(q>>8)&255,q&255,255)/255.0;
})";
using Compile=HRESULT(WINAPI*)(LPCVOID,SIZE_T,LPCSTR,D3D_SHADER_MACRO const*,ID3DInclude*,LPCSTR,LPCSTR,UINT,UINT,ID3DBlob**,ID3DBlob**);
Compile Compiler(){static auto module=LoadLibraryExW(L"d3dcompiler_47.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    return module?reinterpret_cast<Compile>(GetProcAddress(module,"D3DCompile")):nullptr;}
bool Shader(ID3D11Device* d,bool ms,ComPtr<ID3D11ComputeShader>& out){
    auto compile=Compiler();if(!compile)return false;ComPtr<ID3DBlob> bytes,error;
    D3D_SHADER_MACRO defines[]{{"MULTISAMPLE","1"},{nullptr,nullptr}};
    return SUCCEEDED(compile(code,sizeof(code)-1,"owned-depth-crop",ms?defines:nullptr,nullptr,"main","cs_5_0",D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&bytes,&error))
        &&SUCCEEDED(d->CreateComputeShader(bytes->GetBufferPointer(),bytes->GetBufferSize(),nullptr,&out));
}
// Only the compute slots below change. Preserve ranged CB bindings and shader
// class instances. Hidden UAV counters use KEEP (-1) on restoration.
struct SavedCompute {
    ID3D11DeviceContext* c;ComPtr<ID3D11DeviceContext1> c1;
    ComPtr<ID3D11ComputeShader> shader;std::array<ID3D11ClassInstance*,256> classes{};UINT n{256};
    ComPtr<ID3D11Buffer> cb;ComPtr<ID3D11ShaderResourceView> srv;ComPtr<ID3D11UnorderedAccessView> uav;
    UINT first{},count{4096};
    explicit SavedCompute(ID3D11DeviceContext* context):c(context){
        c->CSGetShader(&shader,classes.data(),&n);c->CSGetShaderResources(0,1,&srv);c->CSGetUnorderedAccessViews(0,1,&uav);
        ComPtr<ID3D11Device> d;c->GetDevice(&d);D3D11_FEATURE_DATA_D3D11_OPTIONS options{};
        if(SUCCEEDED(d->CheckFeatureSupport(D3D11_FEATURE_D3D11_OPTIONS,&options,sizeof(options)))&&options.ConstantBufferOffsetting)c->QueryInterface(IID_PPV_ARGS(&c1));
        if(c1)c1->CSGetConstantBuffers1(0,1,&cb,&first,&count);else c->CSGetConstantBuffers(0,1,&cb);
    }
    ~SavedCompute(){
        ID3D11ShaderResourceView* noSrv{};ID3D11UnorderedAccessView* noUav{};UINT keep=UINT_MAX;
        c->CSSetShaderResources(0,1,&noSrv);c->CSSetUnorderedAccessViews(0,1,&noUav,&keep);
        auto rawCb=cb.Get();if(c1)c1->CSSetConstantBuffers1(0,1,&rawCb,&first,&count);else c->CSSetConstantBuffers(0,1,&rawCb);
        auto rawSrv=srv.Get();auto rawUav=uav.Get();c->CSSetShaderResources(0,1,&rawSrv);c->CSSetUnorderedAccessViews(0,1,&rawUav,&keep);
        c->CSSetShader(shader.Get(),classes.data(),n);for(UINT i=0;i<n;++i)if(classes[i])classes[i]->Release();
    }
};
}
struct DepthGpu::State {
    Diagnostics diagnostics;
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11ComputeShader> shader,msShader;ComPtr<ID3D11Texture2D> clone;ComPtr<ID3D11ShaderResourceView> depthSrv;
    ComPtr<ID3D11Buffer> crop;
    std::array<WorldPacket,32> cameras{};unsigned nextCamera{};
    std::array<std::array<float,16>,32> renderViews{};
    std::uint64_t submittedAt{};
    struct Binding {ComPtr<ID3D11Buffer> source;UINT offset{};std::uint64_t revision{};};
    struct Candidate {ComPtr<ID3D11DepthStencilView> view;std::array<Binding,2> bindings;
        std::array<ComPtr<ID3D11Buffer>,DepthDrawSnapshots> snapshots;std::array<UINT,DepthDrawSnapshots> offsets{};
        unsigned snapshotCount{};std::uint64_t epoch{};int dimension{};};
    std::array<Candidate,2> candidates;
    struct Slot {ComPtr<ID3D11Texture2D> output,staging;ComPtr<ID3D11UnorderedAccessView> uav;
        std::array<ComPtr<ID3D11Buffer>,DepthDrawSnapshots+2> constants;std::array<UINT,DepthDrawSnapshots+2> offsets{};
        unsigned bindingCount{},drawCount{};
        ComPtr<ID3D11Query> event;DepthRequest request;std::uint64_t at{};bool busy{},completionCounted{};};
    std::array<Slot,3> slots;
    bool select(ID3D11DeviceContext* c){
        if(!c||c->GetType()!=D3D11_DEVICE_CONTEXT_IMMEDIATE)return false;
        if(context)return context.Get()==c;
        c->GetDevice(&device);if(device->GetFeatureLevel()<D3D_FEATURE_LEVEL_11_0)return false;
        if(!Shader(device.Get(),false,shader)||!Shader(device.Get(),true,msShader))return false;
        D3D11_BUFFER_DESC b{};b.ByteWidth=32;b.Usage=D3D11_USAGE_DEFAULT;b.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
        if(FAILED(device->CreateBuffer(&b,nullptr,&crop)))return false;context=c;return true;
    }
};
DepthGpu::DepthGpu():s(std::make_unique<State>()){}DepthGpu::~DepthGpu()=default;
void DepthGpu::reset(){s=std::make_unique<State>();}
DepthGpu::Diagnostics DepthGpu::diagnostics() const noexcept{return s->diagnostics;}
void DepthGpu::camera(WorldPacket const& p,float const* renderView){
    auto index=s->nextCamera++%s->cameras.size();s->cameras[index]=p;
    if(ValidRenderView(p,renderView)){
        std::copy(renderView,renderView+16,s->renderViews[index].begin());++s->diagnostics.pairedCameras;
    }else{s->cameras[index].valid=0;++s->diagnostics.unpairedCameras;}
}
bool DepthGpu::observe(ID3D11DeviceContext* c,ID3D11DepthStencilView* view,ID3D11Buffer* cb,UINT first,std::uint64_t revision){
    if(!view||!cb||!s->select(c))return false;
    WorldPacket const* latest{};for(auto const& p:s->cameras)if(p.at&&(!latest||p.at>latest->at))latest=&p;
    if(!latest||!latest->valid)return false;
    auto it=std::find_if(s->candidates.begin(),s->candidates.end(),[&](auto const& v){return v.view.Get()==view;});
    if(it==s->candidates.end())it=std::find_if(s->candidates.begin(),s->candidates.end(),[](auto const& v){return !v.view;});
    if(it==s->candidates.end())return false;
    auto binding=std::find_if(it->bindings.begin(),it->bindings.end(),[&](auto const& b){return b.source.Get()==cb&&b.offset==first*16;});
    if(binding!=it->bindings.end()&&revision&&binding->revision==revision)return true;
    if(binding==it->bindings.end())binding=std::find_if(it->bindings.begin(),it->bindings.end(),[](auto const& b){return !b.source;});
    if(binding==it->bindings.end())return true;
    D3D11_BUFFER_DESC desc{};cb->GetDesc(&desc);
    if(desc.ByteWidth<480||desc.ByteWidth>65536||first>4096||(std::uint64_t(first)*16+480)>desc.ByteWidth)return false;
    binding->source=cb;binding->offset=first*16;binding->revision=revision;
    if(it->snapshotCount==DepthDrawSnapshots){++s->diagnostics.snapshotOverflow;return true;}
    desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=desc.CPUAccessFlags=desc.MiscFlags=desc.StructureByteStride=0;
    auto& snapshot=it->snapshots[it->snapshotCount];
    if(snapshot){D3D11_BUFFER_DESC old{};snapshot->GetDesc(&old);if(old.ByteWidth!=desc.ByteWidth)snapshot.Reset();}
    if(!snapshot&&FAILED(s->device->CreateBuffer(&desc,nullptr,&snapshot)))return false;
    // D3D11.0 forbids partial CB copies. Preserve the owned full resource and
    // later read only the 480-byte camera range; the source stays <= 64 KiB.
    c->CopyResource(snapshot.Get(),cb);it->offsets[it->snapshotCount]=first*16;++it->snapshotCount;++s->diagnostics.snapshotCopies;
    if(!it->view){it->view=view;it->epoch=latest->epoch;it->dimension=latest->dimension;}return true;
}
bool DepthGpu::beforeClear(ID3D11DeviceContext* c,ID3D11DepthStencilView* view,DepthRequest const& r,std::uint64_t now){
    if(!s->context||c!=s->context.Get())return false;
    auto it=std::find_if(s->candidates.begin(),s->candidates.end(),[&](auto const& v){return v.view.Get()==view;});if(it==s->candidates.end())return false;
    // Consume the candidate even on refusal; never label a later frame with it.
    auto candidate=std::move(*it);*it={};
    it->snapshots=candidate.snapshots;
    if(!ValidDepthRequest(r,now)||r.epoch!=candidate.epoch||r.dimension!=candidate.dimension)return false;
    auto step=DepthSampleStep(r.width,r.height),sampleWidth=(r.width+step-1)/step,sampleHeight=(r.height+step-1)/step;
    if(s->submittedAt&&now>=s->submittedAt&&now-s->submittedAt<16)return false;
    auto slot=std::find_if(s->slots.begin(),s->slots.end(),[](auto const& v){return !v.busy;});if(slot==s->slots.end())return false;
    D3D11_DEPTH_STENCIL_VIEW_DESC vd{};view->GetDesc(&vd);
    if(vd.Flags||(vd.ViewDimension!=D3D11_DSV_DIMENSION_TEXTURE2D&&vd.ViewDimension!=D3D11_DSV_DIMENSION_TEXTURE2DMS)
        ||(vd.ViewDimension==D3D11_DSV_DIMENSION_TEXTURE2D&&vd.Texture2D.MipSlice))return false;
    ComPtr<ID3D11Resource> resource;view->GetResource(&resource);ComPtr<ID3D11Texture2D> depth;
    if(FAILED(resource.As(&depth)))return false;D3D11_TEXTURE2D_DESC td{};depth->GetDesc(&td);
    if(td.Width!=r.viewportWidth||td.Height!=r.viewportHeight||td.MipLevels!=1||td.ArraySize!=1||td.SampleDesc.Quality
       ||(td.SampleDesc.Count!=1&&td.SampleDesc.Count!=2&&td.SampleDesc.Count!=4&&td.SampleDesc.Count!=8)
       ||std::uint64_t(td.Width)*td.Height>16777216)return false;
    DXGI_FORMAT format{},sampleFormat{};
    if(vd.Format==DXGI_FORMAT_D32_FLOAT){format=DXGI_FORMAT_R32_TYPELESS;sampleFormat=DXGI_FORMAT_R32_FLOAT;}
    else if(vd.Format==DXGI_FORMAT_D24_UNORM_S8_UINT){format=DXGI_FORMAT_R24G8_TYPELESS;sampleFormat=DXGI_FORMAT_R24_UNORM_X8_TYPELESS;}
    else return false;
    ComPtr<ID3D11Predicate> predicate;BOOL value{};c->GetPredication(&predicate,&value);if(predicate)return false;
    D3D11_TEXTURE2D_DESC old{};if(s->clone)s->clone->GetDesc(&old);
    if(!s->clone||!s->depthSrv||old.Width!=td.Width||old.Height!=td.Height||old.Format!=format||old.SampleDesc.Count!=td.SampleDesc.Count){
        auto cd=td;cd.Format=format;cd.Usage=D3D11_USAGE_DEFAULT;cd.BindFlags=D3D11_BIND_SHADER_RESOURCE;cd.CPUAccessFlags=cd.MiscFlags=0;
        s->depthSrv.Reset();s->clone.Reset();
        if(FAILED(s->device->CreateTexture2D(&cd,nullptr,&s->clone)))return false;
        D3D11_SHADER_RESOURCE_VIEW_DESC sd{};sd.Format=sampleFormat;sd.ViewDimension=td.SampleDesc.Count==1?D3D11_SRV_DIMENSION_TEXTURE2D:D3D11_SRV_DIMENSION_TEXTURE2DMS;
        if(td.SampleDesc.Count==1)sd.Texture2D.MipLevels=1;
        if(FAILED(s->device->CreateShaderResourceView(s->clone.Get(),&sd,&s->depthSrv)))return false;
    }
    D3D11_TEXTURE2D_DESC od{};if(slot->output)slot->output->GetDesc(&od);
    if(!slot->output||!slot->uav||!slot->staging||od.Width!=sampleWidth||od.Height!=sampleHeight){
        slot->uav.Reset();slot->output.Reset();slot->staging.Reset();
        od={};od.Width=sampleWidth;od.Height=sampleHeight;od.MipLevels=od.ArraySize=1;od.Format=DXGI_FORMAT_R8G8B8A8_UNORM;od.SampleDesc.Count=1;od.BindFlags=D3D11_BIND_UNORDERED_ACCESS;
        if(FAILED(s->device->CreateTexture2D(&od,nullptr,&slot->output))||FAILED(s->device->CreateUnorderedAccessView(slot->output.Get(),nullptr,&slot->uav)))return false;
        od.Usage=D3D11_USAGE_STAGING;od.BindFlags=0;od.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        if(FAILED(s->device->CreateTexture2D(&od,nullptr,&slot->staging)))return false;
    }
    unsigned bindingCount{};
    unsigned finalCount{};for(auto const& binding:candidate.bindings)if(binding.source)++finalCount;
    auto total=candidate.snapshotCount+finalCount;
    for(;bindingCount<total;++bindingCount){auto& constants=slot->constants[bindingCount];
        D3D11_BUFFER_DESC bd{},previous{};
        if(bindingCount<candidate.snapshotCount){candidate.snapshots[bindingCount]->GetDesc(&bd);slot->offsets[bindingCount]=candidate.offsets[bindingCount];}
        else {auto const& binding=candidate.bindings[bindingCount-candidate.snapshotCount];binding.source->GetDesc(&bd);slot->offsets[bindingCount]=binding.offset;}
        if(constants)constants->GetDesc(&previous);
        if(!constants||previous.ByteWidth!=bd.ByteWidth){constants.Reset();bd.Usage=D3D11_USAGE_STAGING;bd.BindFlags=0;bd.CPUAccessFlags=D3D11_CPU_ACCESS_READ;bd.MiscFlags=bd.StructureByteStride=0;
            if(FAILED(s->device->CreateBuffer(&bd,nullptr,&constants)))return false;}
    }
    if(!bindingCount)return false;
    if(!slot->event){D3D11_QUERY_DESC q{D3D11_QUERY_EVENT,0};if(FAILED(s->device->CreateQuery(&q,&slot->event)))return false;}
    c->CopyResource(s->clone.Get(),depth.Get());
    unsigned copied{};
    // Keep draw-owned versions, including intermediate rewrites, plus final
    // contents. At most 18 buffers (<=64 KiB each), 480 camera bytes read from
    // each; still one depth clone and pixel readback.
    for(unsigned i=0;i<candidate.snapshotCount;++i)c->CopyResource(slot->constants[copied++].Get(),candidate.snapshots[i].Get());
    for(auto const& binding:candidate.bindings){if(!binding.source)break;
        c->CopyResource(slot->constants[copied++].Get(),binding.source.Get());}
    UINT region[]{r.x,r.y,r.width,r.height,td.SampleDesc.Count,step,0,0};c->UpdateSubresource(s->crop.Get(),0,nullptr,region,0,0);
    {SavedCompute saved(c);auto srv=s->depthSrv.Get();auto uav=slot->uav.Get();auto cb=s->crop.Get();UINT keep=UINT_MAX;
        c->CSSetShader(td.SampleDesc.Count==1?s->shader.Get():s->msShader.Get(),nullptr,0);c->CSSetConstantBuffers(0,1,&cb);
        c->CSSetShaderResources(0,1,&srv);c->CSSetUnorderedAccessViews(0,1,&uav,&keep);c->Dispatch((sampleWidth+7)/8,(sampleHeight+7)/8,1);
    }
    c->CopyResource(slot->staging.Get(),slot->output.Get());c->End(slot->event.Get());
    slot->request=r;slot->at=now;slot->bindingCount=bindingCount;slot->drawCount=candidate.snapshotCount;slot->busy=true;slot->completionCounted=false;s->submittedAt=now;return true;
}
bool DepthGpu::poll(ID3D11DeviceContext* c,DepthResult& out,std::uint64_t now){
    if(!s->context||c!=s->context.Get())return false;bool published{};
    for(auto& slot:s->slots){
        if(!slot.busy)continue;
        auto& diag=s->diagnostics;
        if(c->GetData(slot.event.Get(),nullptr,0,D3D11_ASYNC_GETDATA_DONOTFLUSH)!=S_OK){++diag.queryPendingPolls;continue;}
        std::array<std::array<float,120>,DepthDrawSnapshots+2> snapshots{};bool mapped=true;
        for(unsigned i=0;i<slot.bindingCount;++i){D3D11_MAPPED_SUBRESOURCE cm{};
            if(FAILED(c->Map(slot.constants[i].Get(),0,D3D11_MAP_READ,D3D11_MAP_FLAG_DO_NOT_WAIT,&cm))){++diag.mapBusy;mapped=false;break;}
            std::memcpy(snapshots[i].data(),static_cast<std::byte*>(cm.pData)+slot.offsets[i],sizeof snapshots[i]);c->Unmap(slot.constants[i].Get(),0);
        }
        if(!mapped)continue;
        if(!slot.completionCounted){++diag.completed;slot.completionCounted=true;}
        if(slot.at>now||now-slot.at>150){++diag.stale;slot.busy=false;continue;}
        if(slot.at<out.at){++diag.older;slot.busy=false;continue;}
        WorldPacket const* camera{};unsigned selected{};bool validJitter{};
        for(unsigned b=0;b<slot.bindingCount;++b){auto constants=snapshots[b].data();
          if(!std::isfinite(constants[112])||!std::isfinite(constants[113]))continue;validJitter=true;
          for(unsigned i=0;i<s->cameras.size();++i){auto const& p=s->cameras[i];
            if(p.valid&&p.epoch==slot.request.epoch&&p.dimension==slot.request.dimension
            &&p.at<=slot.at&&slot.at-p.at<=250&&DepthCameraMatches(constants,p,s->renderViews[i].data())&&(!camera||p.at>camera->at)){camera=&p;selected=b;}
          }
        }
        if(!validJitter){++diag.jitterInvalid;slot.busy=false;continue;}
        auto constants=snapshots[selected].data();
        if(!camera){
            ++diag.cameraMiss;diag.cameraSamples=0;float closest=1e9f;
            diag.projectionError=diag.basisError=diag.translationError=1e6f;
            diag.clipW=std::isfinite(constants[11])?std::clamp(constants[11],-1000.f,1000.f):1000.f;
            for(unsigned sample=0;sample<s->cameras.size();++sample){auto const& p=s->cameras[sample];
              if(p.valid&&p.epoch==slot.request.epoch&&p.dimension==slot.request.dimension&&p.at<=slot.at&&slot.at-p.at<=250){
                ++diag.cameraSamples;float projection=0,basis=0,translation=0;
                for(unsigned i=0;i<16;++i){
                    if(i!=10&&i!=14)projection=(std::max)(projection,std::isfinite(constants[i])?std::abs(constants[i]-p.projection[i]):1e6f);
                    auto delta=std::isfinite(constants[i+16])?std::abs(constants[i+16]-s->renderViews[sample][i]):1e6f;
                    if(i>=12&&i<15)translation=(std::max)(translation,delta);else basis=(std::max)(basis,delta);
                }
                if(projection+basis+translation<closest){closest=projection+basis+translation;
                    diag.projectionError=(std::min)(projection,1e6f);diag.basisError=(std::min)(basis,1e6f);diag.translationError=(std::min)(translation,1e6f);}
            }}
            slot.busy=false;continue;
        }
        D3D11_MAPPED_SUBRESOURCE pixels{};if(FAILED(c->Map(slot.staging.Get(),0,D3D11_MAP_READ,D3D11_MAP_FLAG_DO_NOT_WAIT,&pixels))){++diag.mapBusy;continue;}
        auto const& r=slot.request;auto step=DepthSampleStep(r.width,r.height),sampleWidth=(r.width+step-1)/step,sampleHeight=(r.height+step-1)/step;
        out.rgba.resize(std::size_t(sampleWidth)*sampleHeight*4);
        for(UINT y=0;y<sampleHeight;++y)std::memcpy(out.rgba.data()+std::size_t(y)*sampleWidth*4,static_cast<std::byte*>(pixels.pData)+std::size_t(y)*pixels.RowPitch,std::size_t(sampleWidth)*4);
        c->Unmap(slot.staging.Get(),0);out.camera=*camera;
        // Host camera validation and lyric placement need world translation.
        // Never overwrite it with the GPU's origin-relative view translation.
        std::copy(constants,constants+16,out.camera.projection);
        out.camera.projection[8]+=constants[112];out.camera.projection[9]-=constants[113];
        out.viewportWidth=r.viewportWidth;out.viewportHeight=r.viewportHeight;out.x=r.x;out.y=r.y;out.width=r.width;out.height=r.height;out.sampleStep=step;out.at=slot.at;
        slot.busy=false;published=true;++diag.published;if(selected>0)++diag.alternativeBindingMatches;
        if(selected>=slot.drawCount)++diag.finalBindingMatches;
        else if(selected>0)++diag.intermediateMatches;
    }return published;
}
}
