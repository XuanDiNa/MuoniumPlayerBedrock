#include "D3D12Composition.h"
#include "SystemGraphicsLibrary.h"
#include <wrl/client.h>
#include <d3dcompiler.h>
#include <cstring>
#include <stdexcept>
#include <array>
namespace mp {using Microsoft::WRL::ComPtr;namespace {
void Check(HRESULT hr){if(FAILED(hr))throw std::runtime_error("D3D12 resource/command failure");}
constexpr char shader[]=R"(
cbuffer Crop:register(b0){float4 clipRect;float4 texelRect;};
struct V {float4 p:SV_Position;float2 uv:TEXCOORD0;};
V vs(uint id:SV_VertexID){float2 a[6]={float2(0,0),float2(1,0),float2(0,1),float2(0,1),float2(1,0),float2(1,1)};
 V v;float2 t=a[id];v.p=float4(clipRect.xy+t*clipRect.zw,0,1);v.uv=texelRect.xy+t*texelRect.zw;return v;}
Texture2D<float4> pixels:register(t0);SamplerState linearClamp:register(s0);
float4 ps(V v):SV_Target{return pixels.Load(int3(int2(v.uv),0));}
)";
ComPtr<ID3DBlob> Compile(char const* entry,char const* profile){
    static auto dll=LoadSystemGraphicsLibrary(L"d3dcompiler_47.dll");
    using Fn=decltype(&D3DCompile);auto fn=dll?reinterpret_cast<Fn>(GetProcAddress(dll,"D3DCompile")):nullptr;
    if(!fn)throw std::runtime_error("D3DCompile unavailable");ComPtr<ID3DBlob> code,error;
    Check(fn(shader,sizeof shader-1,"muonium-d3d12",nullptr,nullptr,entry,profile,D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&error));return code;
}
D3D12_HEAP_PROPERTIES Heap(D3D12_HEAP_TYPE type){D3D12_HEAP_PROPERTIES h{};h.Type=type;h.CreationNodeMask=h.VisibleNodeMask=1;return h;}
D3D12_RESOURCE_BARRIER Barrier(ID3D12Resource* r,D3D12_RESOURCE_STATES before,D3D12_RESOURCE_STATES after){
    D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;b.Transition.pResource=r;
    b.Transition.Subresource=D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;b.Transition.StateBefore=before;b.Transition.StateAfter=after;return b;
}
}
struct D3D12Composition::State {
    D3D12TransferStats stats;
    struct Slot {
        ComPtr<ID3D12CommandAllocator> allocator;ComPtr<ID3D12Resource> texture,upload;
        UINT width{},height{},contentWidth{},contentHeight{};UINT64 serial{},sequence{},uploadBytes{};
    };
    ComPtr<ID3D12Device> device;ComPtr<ID3D12CommandQueue> queue;ComPtr<IDXGISwapChain3> swap;
    ComPtr<ID3D12GraphicsCommandList> list;ComPtr<ID3D12RootSignature> root;ComPtr<ID3D12PipelineState> pso;
    ComPtr<ID3D12DescriptorHeap> rtv,srv;ComPtr<ID3D12Fence> fence;
    std::array<Slot,8> slots;std::array<ComPtr<ID3D12Resource>,8> buffers;
    UINT count{},slotCount{},cursor{},rtvStride{},srvStride{};UINT64 serial{};DXGI_FORMAT format{};bool removed{};
    void initialize(IDXGISwapChain3* s,ID3D12CommandQueue* q){
        Check(s->GetDevice(IID_PPV_ARGS(&device)));queue=q;swap=s;
        ComPtr<ID3D12Device> qd;Check(q->GetDevice(IID_PPV_ARGS(&qd)));if(qd.Get()!=device.Get()||q->GetDesc().Type!=D3D12_COMMAND_LIST_TYPE_DIRECT)throw std::runtime_error("queue device/type");
        DXGI_SWAP_CHAIN_DESC1 desc{};Check(s->GetDesc1(&desc));count=desc.BufferCount;format=desc.Format;
        if(count<2||count>slots.size()||desc.SampleDesc.Count!=1||
            (format!=DXGI_FORMAT_R8G8B8A8_UNORM&&format!=DXGI_FORMAT_B8G8R8A8_UNORM))throw std::runtime_error("unsupported swapchain format/count");
        slotCount=(std::min)(UINT(slots.size()),count+2);
        auto vs=Compile("vs","vs_5_0"),ps=Compile("ps","ps_5_0");
        D3D12_DESCRIPTOR_RANGE range{};range.RangeType=D3D12_DESCRIPTOR_RANGE_TYPE_SRV;range.NumDescriptors=1;
        D3D12_ROOT_PARAMETER params[2]{};params[0].ParameterType=D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;params[0].Constants.Num32BitValues=8;params[0].ShaderVisibility=D3D12_SHADER_VISIBILITY_VERTEX;
        params[1].ParameterType=D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;params[1].DescriptorTable.NumDescriptorRanges=1;params[1].DescriptorTable.pDescriptorRanges=&range;params[1].ShaderVisibility=D3D12_SHADER_VISIBILITY_PIXEL;
        D3D12_STATIC_SAMPLER_DESC sampler{};sampler.Filter=D3D12_FILTER_MIN_MAG_MIP_LINEAR;sampler.AddressU=sampler.AddressV=sampler.AddressW=D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
        sampler.ComparisonFunc=D3D12_COMPARISON_FUNC_ALWAYS;sampler.MaxLOD=D3D12_FLOAT32_MAX;sampler.ShaderVisibility=D3D12_SHADER_VISIBILITY_PIXEL;
        D3D12_ROOT_SIGNATURE_DESC rd{};rd.NumParameters=2;rd.pParameters=params;rd.NumStaticSamplers=1;rd.pStaticSamplers=&sampler;rd.Flags=D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;
        static auto dll=LoadSystemGraphicsLibrary(L"d3d12.dll");
        using Serialize=decltype(&D3D12SerializeRootSignature);auto serialize=dll?reinterpret_cast<Serialize>(GetProcAddress(dll,"D3D12SerializeRootSignature")):nullptr;
        if(!serialize)throw std::runtime_error("root serializer");ComPtr<ID3DBlob> blob,error;Check(serialize(&rd,D3D_ROOT_SIGNATURE_VERSION_1,&blob,&error));
        Check(device->CreateRootSignature(0,blob->GetBufferPointer(),blob->GetBufferSize(),IID_PPV_ARGS(&root)));
        D3D12_GRAPHICS_PIPELINE_STATE_DESC pd{};pd.pRootSignature=root.Get();pd.VS={vs->GetBufferPointer(),vs->GetBufferSize()};pd.PS={ps->GetBufferPointer(),ps->GetBufferSize()};
        pd.BlendState.RenderTarget[0]={TRUE,FALSE,D3D12_BLEND_ONE,D3D12_BLEND_INV_SRC_ALPHA,D3D12_BLEND_OP_ADD,D3D12_BLEND_ONE,D3D12_BLEND_INV_SRC_ALPHA,D3D12_BLEND_OP_ADD,D3D12_LOGIC_OP_NOOP,D3D12_COLOR_WRITE_ENABLE_ALL};
        pd.SampleMask=UINT_MAX;pd.RasterizerState.FillMode=D3D12_FILL_MODE_SOLID;pd.RasterizerState.CullMode=D3D12_CULL_MODE_NONE;pd.RasterizerState.DepthClipEnable=TRUE;
        pd.DepthStencilState.DepthEnable=FALSE;pd.DepthStencilState.StencilEnable=FALSE;pd.PrimitiveTopologyType=D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
        pd.NumRenderTargets=1;pd.RTVFormats[0]=format;pd.SampleDesc.Count=1;Check(device->CreateGraphicsPipelineState(&pd,IID_PPV_ARGS(&pso)));
        D3D12_DESCRIPTOR_HEAP_DESC hd{};hd.Type=D3D12_DESCRIPTOR_HEAP_TYPE_RTV;hd.NumDescriptors=count;Check(device->CreateDescriptorHeap(&hd,IID_PPV_ARGS(&rtv)));
        hd.Type=D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;hd.NumDescriptors=slotCount;hd.Flags=D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;Check(device->CreateDescriptorHeap(&hd,IID_PPV_ARGS(&srv)));
        rtvStride=device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);srvStride=device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
        auto handle=rtv->GetCPUDescriptorHandleForHeapStart();for(UINT i=0;i<count;i++){
            Check(s->GetBuffer(i,IID_PPV_ARGS(&buffers[i])));device->CreateRenderTargetView(buffers[i].Get(),nullptr,handle);handle.ptr+=rtvStride;}
        for(UINT i=0;i<slotCount;i++)Check(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&slots[i].allocator)));
        Check(device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,slots[0].allocator.Get(),pso.Get(),IID_PPV_ARGS(&list)));Check(list->Close());
        Check(device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&fence)));
    }
    bool drain(){
        if(!fence||!serial||removed)return true;
        if(fence->GetCompletedValue()==UINT64_MAX||FAILED(device->GetDeviceRemovedReason())){removed=true;return true;}
        if(fence->GetCompletedValue()>=serial)return true;
        auto event=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!event)return false;
        auto hr=fence->SetEventOnCompletion(serial,event);auto wait=SUCCEEDED(hr)?WaitForSingleObject(event,2000):WAIT_FAILED;CloseHandle(event);return wait==WAIT_OBJECT_0;
    }
    D3D12Draw draw(CompositionFrame const& frame,UINT64 now){
        auto const& h=frame.header;if(!ValidComposition(h,now)||!h.active)return D3D12Draw::Empty;
        DXGI_SWAP_CHAIN_DESC1 desc{};Check(swap->GetDesc1(&desc));
        if(desc.Width!=h.viewportWidth||desc.Height!=h.viewportHeight||h.planeCount)return D3D12Draw::Empty;
        if(h.reserved==CompositionProbeOnly)return D3D12Draw::Probe;
        if(!h.width||!h.height)return D3D12Draw::Probe;
        if(frame.screen.size()!=SIZE_T(h.width)*h.height*4)throw std::runtime_error("mailbox pixel size");
        UINT bufferIndex=swap->GetCurrentBackBufferIndex();if(bufferIndex>=count)throw std::runtime_error("buffer index");
        auto completed=fence->GetCompletedValue();if(completed==UINT64_MAX)throw std::runtime_error("device removed");
        UINT i=slotCount;for(UINT offset=0;offset<slotCount;offset++){UINT candidate=(cursor+offset)%slotCount;if(completed>=slots[candidate].serial){i=candidate;cursor=(i+1)%slotCount;break;}}
        if(i==slotCount)return D3D12Draw::Busy;auto& slot=slots[i];
        Check(slot.allocator->Reset());Check(list->Reset(slot.allocator.Get(),pso.Get()));
        bool fresh=slot.sequence!=h.sequence||slot.contentWidth!=h.width||slot.contentHeight!=h.height;
        auto area=std::uint64_t(h.width)*h.height;
        bool reusable=slot.texture&&slot.upload&&h.width<=slot.width&&h.height<=slot.height
            &&std::uint64_t(slot.width)*slot.height<=(std::max)(65536ull,area*4);
        if(!reusable){
            ++stats.allocations;
            // Derive each new capacity from this request, never independent
            // historical maxima. Retire only the fence-completed slot above.
            UINT w=(h.width+63u)&~63u,ht=(h.height+31u)&~31u;
            if(std::uint64_t(w)*ht>16777216){w=h.width;ht=h.height;}
            slot.texture.Reset();slot.upload.Reset();slot.width=w;slot.height=ht;slot.sequence=0;fresh=true;
            D3D12_RESOURCE_DESC td{};td.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;td.Width=w;td.Height=ht;td.DepthOrArraySize=td.MipLevels=1;td.Format=DXGI_FORMAT_B8G8R8A8_UNORM;td.SampleDesc.Count=1;
            auto heap=Heap(D3D12_HEAP_TYPE_DEFAULT);Check(device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&td,D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,nullptr,IID_PPV_ARGS(&slot.texture)));
            D3D12_SHADER_RESOURCE_VIEW_DESC sd{};sd.Format=td.Format;sd.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;sd.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;sd.Texture2D.MipLevels=1;
            auto handle=srv->GetCPUDescriptorHandleForHeapStart();handle.ptr+=i*srvStride;device->CreateShaderResourceView(slot.texture.Get(),&sd,handle);
            device->GetCopyableFootprints(&td,0,1,0,nullptr,nullptr,nullptr,&slot.uploadBytes);heap=Heap(D3D12_HEAP_TYPE_UPLOAD);
            td={};td.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;td.Width=slot.uploadBytes;td.Height=1;td.DepthOrArraySize=td.MipLevels=1;td.SampleDesc.Count=1;td.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
            Check(device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&td,D3D12_RESOURCE_STATE_GENERIC_READ,nullptr,IID_PPV_ARGS(&slot.upload)));
        }
        if(fresh){
            stats.uploadBytes+=std::uint64_t(h.width)*h.height*4;
            auto td=slot.texture->GetDesc();D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};device->GetCopyableFootprints(&td,0,1,0,&footprint,nullptr,nullptr,nullptr);
            void* dest{};D3D12_RANGE read{};Check(slot.upload->Map(0,&read,&dest));for(UINT y=0;y<h.height;y++)std::memcpy(static_cast<char*>(dest)+footprint.Offset+y*footprint.Footprint.RowPitch,frame.screen.data()+SIZE_T(y)*h.width*4,SIZE_T(h.width)*4);
            slot.upload->Unmap(0,nullptr);auto b=Barrier(slot.texture.Get(),D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COPY_DEST);list->ResourceBarrier(1,&b);
            D3D12_TEXTURE_COPY_LOCATION from{};from.pResource=slot.upload.Get();from.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;from.PlacedFootprint=footprint;
            D3D12_TEXTURE_COPY_LOCATION to{};to.pResource=slot.texture.Get();to.Type=D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;D3D12_BOX active{0,0,0,h.width,h.height,1};list->CopyTextureRegion(&to,0,0,0,&from,&active);
            std::swap(b.Transition.StateBefore,b.Transition.StateAfter);list->ResourceBarrier(1,&b);slot.sequence=h.sequence;slot.contentWidth=h.width;slot.contentHeight=h.height;
        }
        auto b=Barrier(buffers[bufferIndex].Get(),D3D12_RESOURCE_STATE_PRESENT,D3D12_RESOURCE_STATE_RENDER_TARGET);list->ResourceBarrier(1,&b);
        auto rt=rtv->GetCPUDescriptorHandleForHeapStart();rt.ptr+=bufferIndex*rtvStride;list->OMSetRenderTargets(1,&rt,FALSE,nullptr);
        D3D12_VIEWPORT vp{0,0,float(h.viewportWidth),float(h.viewportHeight),0,1};list->RSSetViewports(1,&vp);
        list->SetGraphicsRootSignature(root.Get());ID3D12DescriptorHeap* heaps[]{srv.Get()};list->SetDescriptorHeaps(1,heaps);
        auto handle=srv->GetGPUDescriptorHandleForHeapStart();handle.ptr+=i*srvStride;list->SetGraphicsRootDescriptorTable(1,handle);
        list->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
        unsigned bands=h.version==2?h.bandCount:1;
        for(unsigned j=0;j<bands;j++){
            auto band=h.version==2?h.bands[j]:CompositionBand{h.x,h.y,h.width,h.height,0};
            D3D12_RECT sc{LONG(band.x),LONG(band.y),LONG(band.x+band.width),LONG(band.y+band.height)};list->RSSetScissorRects(1,&sc);
            // One source texel per destination pixel; unused capacity/padding
            // can never bleed through a linear sampler at a band edge.
            float crop[]{float(band.x)/h.viewportWidth*2-1,1-float(band.y)/h.viewportHeight*2,
                float(band.width)/h.viewportWidth*2,-float(band.height)/h.viewportHeight*2,
                0,float(band.offsetY),float(band.width),float(band.height)};
            list->SetGraphicsRoot32BitConstants(0,8,crop,0);list->DrawInstanced(6,1,0,0);++stats.draws;
        }
        std::swap(b.Transition.StateBefore,b.Transition.StateAfter);list->ResourceBarrier(1,&b);Check(list->Close());
        ID3D12CommandList* commands[]{list.Get()};queue->ExecuteCommandLists(1,commands);slot.serial=++serial;Check(queue->Signal(fence.Get(),serial));return D3D12Draw::Drawn;
    }
};
D3D12Composition::D3D12Composition()=default;
D3D12TransferStats D3D12Composition::transferStats() const noexcept {return state?state->stats:D3D12TransferStats{};}
D3D12Composition::~D3D12Composition(){if(state&&!state->drain())state.release();/* Retain in-flight resources until process exit on a wedged GPU. */}
bool D3D12Composition::reset(){if(state&&!state->drain())return false;state.reset();return true;}
D3D12Draw D3D12Composition::present(IDXGISwapChain3* s,ID3D12CommandQueue* q,CompositionFrame const& frame,std::uint64_t now){
    try{if(!state){state=std::make_unique<State>();state->initialize(s,q);}if(state->swap.Get()!=s||state->queue.Get()!=q)return D3D12Draw::Failed;return state->draw(frame,now);}
    catch(...){return D3D12Draw::Failed;}
}
}
