// Exercise the actual production callbacks, API slots and MinHook trampolines
// in this disposable WARP process. No game process, backend admission bypass,
// or test entry point is added to the production DLL.
#include "../src/DepthBridge.cpp"
#include <iostream>
#include <stdexcept>
namespace mp {void Log(std::filesystem::path const&,std::string const&) noexcept {}}
static unsigned checks{};
static void Check(bool value,char const* message){++checks;if(!value)throw std::runtime_error(message);}
int main(){try{
    auto module=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    auto create=reinterpret_cast<decltype(&D3D11CreateDevice)>(GetProcAddress(module,"D3D11CreateDevice"));
    Microsoft::WRL::ComPtr<ID3D11Device> device;Microsoft::WRL::ComPtr<ID3D11DeviceContext> context;
    Check(create&&SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&device,nullptr,&context)),"WARP creation");
    auto table=*reinterpret_cast<void***>(context.Get());
    void* targets[]{table[15],table[48],table[47],table[46]};
    void* callbacks[]{reinterpret_cast<void*>(&mp::Unmapped),reinterpret_cast<void*>(&mp::Updated),reinterpret_cast<void*>(&mp::Copied),reinterpret_cast<void*>(&mp::RegionCopied)};
    void** originals[]{reinterpret_cast<void**>(&mp::originalUnmap),reinterpret_cast<void**>(&mp::originalUpdate),reinterpret_cast<void**>(&mp::originalCopy),reinterpret_cast<void**>(&mp::originalCopyRegion)};
    Check(MH_Initialize()==MH_OK,"fixture hook init");
    for(unsigned i=0;i<4;++i){Check(mp::Pristine(targets[i]),"writer API has pristine system owner");
        Check(MH_CreateHook(targets[i],callbacks[i],originals[i])==MH_OK&&MH_EnableHook(targets[i])==MH_OK,"writer hook installation");}
    mp::active=true;mp::requestUntil=GetTickCount64()+30000;
    D3D11_BUFFER_DESC desc{};desc.ByteWidth=512;desc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    Microsoft::WRL::ComPtr<ID3D11Buffer> buffer,source;
    Check(SUCCEEDED(device->CreateBuffer(&desc,nullptr,&buffer))&&SUCCEEDED(device->CreateBuffer(&desc,nullptr,&source)),"copy/update CBs");
    auto token=mp::writes.observe(buffer.Get());auto sourceToken=mp::writes.observe(source.Get());
    std::array<float,128> values{};values[0]=42;
    context->UpdateSubresource(buffer.Get(),0,nullptr,values.data(),0,0);
    auto next=mp::writes.observe(buffer.Get());Check(next!=token,"real UpdateSubresource revises destination");token=next;
    Check(mp::writes.observe(source.Get())==sourceToken,"unrelated source unchanged");
    context->CopyResource(buffer.Get(),source.Get());next=mp::writes.observe(buffer.Get());
    Check(next!=token&&mp::writes.observe(source.Get())==sourceToken,"real CopyResource revises only destination");
    desc.BindFlags=D3D11_BIND_VERTEX_BUFFER;
    Microsoft::WRL::ComPtr<ID3D11Buffer> region;Check(SUCCEEDED(device->CreateBuffer(&desc,nullptr,&region)),"region destination");
    token=mp::writes.observe(region.Get());
    D3D11_BOX box{0,0,0,128,1,1};context->CopySubresourceRegion(region.Get(),0,0,0,0,source.Get(),0,&box);
    Check(mp::writes.observe(region.Get())!=token,"real CopySubresourceRegion revises destination");
    desc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;desc.Usage=D3D11_USAGE_DYNAMIC;desc.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;
    Microsoft::WRL::ComPtr<ID3D11Buffer> dynamic;Check(SUCCEEDED(device->CreateBuffer(&desc,nullptr,&dynamic)),"dynamic camera buffer");
    token=mp::writes.observe(dynamic.Get());D3D11_MAPPED_SUBRESOURCE mapped{};
    Check(SUCCEEDED(context->Map(dynamic.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)),"real camera write mapping");
    std::memcpy(mapped.pData,values.data(),512);context->Unmap(dynamic.Get(),0);
    Check(mp::writes.observe(dynamic.Get())!=token,"real Unmap revises camera after write");
    token=mp::writes.observe(buffer.Get());mp::inside=true;context->CopyResource(buffer.Get(),source.Get());mp::inside=false;
    Check(mp::writes.observe(buffer.Get())==token,"owned capture copies do not recursively invalidate cameras");
    mp::active=false;context->UpdateSubresource(buffer.Get(),0,nullptr,values.data(),0,0);
    Check(mp::writes.observe(buffer.Get())==token,"disabled bridge ignores writes");
    mp::active=true;mp::requestUntil=1;context->UpdateSubresource(buffer.Get(),0,nullptr,values.data(),0,0);
    Check(mp::writes.observe(buffer.Get())==token,"expired lyric request ignores writes");mp::active=false;
    for(auto target:targets)Check(MH_DisableHook(target)==MH_OK&&MH_RemoveHook(target)==MH_OK,"fixture removes only owned hooks");
    Check(MH_Uninitialize()==MH_OK,"fixture hook shutdown");
    std::cout<<"Depth production API callbacks and WARP "<<checks<<" checks passed\n";return 0;
}catch(std::exception const& error){std::cerr<<error.what()<<'\n';return 1;}}
