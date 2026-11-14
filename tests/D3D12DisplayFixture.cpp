// Owned D3D12 swapchain for cross-process overlay compatibility tests only.
// Never loads in Minecraft; no hooks or game graphics resources are touched.
#include <Windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <iostream>
#include <sstream>
#include <string>
#include <stdexcept>
using Microsoft::WRL::ComPtr;
static void Check(HRESULT result,char const* name){if(FAILED(result))throw std::runtime_error(name);}
struct Fixture {
    HWND window{};HANDLE event{};UINT width=400,height=300,bufferCount=2;UINT64 serial{};bool present1{};
    ComPtr<ID3D12Device> device;ComPtr<ID3D12CommandQueue> queue;
    ComPtr<IDXGISwapChain3> swap;ComPtr<ID3D12CommandAllocator> allocator;
    ComPtr<ID3D12GraphicsCommandList> list;ComPtr<ID3D12DescriptorHeap> heap;
    ComPtr<ID3D12Fence> fence;ComPtr<ID3D12Resource> buffers[8];UINT stride{};
    Fixture(bool warp,UINT count=2):bufferCount(count){
        WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"MuoniumD3D12Fixture";RegisterClassW(&wc);
        window=CreateWindowExW(WS_EX_NOACTIVATE|WS_EX_TOPMOST,wc.lpszClassName,L"Muonium owned D3D12 fixture",WS_POPUP,40,40,width,height,nullptr,nullptr,wc.hInstance,nullptr);
        if(!window)throw std::runtime_error("fixture window");
        ComPtr<IDXGIFactory4> factory;Check(CreateDXGIFactory1(IID_PPV_ARGS(&factory)),"DXGI factory");
        ComPtr<IDXGIAdapter> adapter;
        if(warp)Check(factory->EnumWarpAdapter(IID_PPV_ARGS(&adapter)),"WARP adapter");
        Check(D3D12CreateDevice(adapter.Get(),D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&device)),"D3D12 device");
        D3D12_COMMAND_QUEUE_DESC q{};q.Type=D3D12_COMMAND_LIST_TYPE_DIRECT;
        Check(device->CreateCommandQueue(&q,IID_PPV_ARGS(&queue)),"D3D12 queue");
        DXGI_SWAP_CHAIN_DESC1 desc{};desc.Width=width;desc.Height=height;desc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;
        desc.SampleDesc.Count=1;desc.BufferCount=bufferCount;desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;
        ComPtr<IDXGISwapChain1> first;Check(factory->CreateSwapChainForHwnd(queue.Get(),window,&desc,nullptr,nullptr,&first),"D3D12 swapchain");
        Check(first.As(&swap),"D3D12 current buffer");factory->MakeWindowAssociation(window,DXGI_MWA_NO_ALT_ENTER);
        D3D12_DESCRIPTOR_HEAP_DESC hd{};hd.Type=D3D12_DESCRIPTOR_HEAP_TYPE_RTV;hd.NumDescriptors=bufferCount;
        Check(device->CreateDescriptorHeap(&hd,IID_PPV_ARGS(&heap)),"RTV heap");stride=device->GetDescriptorHandleIncrementSize(hd.Type);
        Check(device->CreateCommandAllocator(q.Type,IID_PPV_ARGS(&allocator)),"D3D12 allocator");
        Check(device->CreateCommandList(0,q.Type,allocator.Get(),nullptr,IID_PPV_ARGS(&list)),"D3D12 list");Check(list->Close(),"initial close");
        Check(device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&fence)),"D3D12 fence");
        event=CreateEventW(nullptr,FALSE,FALSE,nullptr);if(!event)throw std::runtime_error("fixture fence event");
        targets();SetWindowPos(window,HWND_TOPMOST,40,40,width,height,SWP_NOACTIVATE|SWP_SHOWWINDOW);draw();
    }
    void targets(){auto handle=heap->GetCPUDescriptorHandleForHeapStart();for(UINT i=0;i<bufferCount;i++){
        Check(swap->GetBuffer(i,IID_PPV_ARGS(&buffers[i])),"D3D12 backbuffer");device->CreateRenderTargetView(buffers[i].Get(),nullptr,handle);handle.ptr+=stride;}}
    void wait(){Check(queue->Signal(fence.Get(),++serial),"fixture signal");if(fence->GetCompletedValue()<serial){
        Check(fence->SetEventOnCompletion(serial,event),"fixture wait");if(WaitForSingleObject(event,5000)!=WAIT_OBJECT_0)throw std::runtime_error("D3D12 fixture timeout");}}
    void submit(){
        Check(allocator->Reset(),"allocator reset");Check(list->Reset(allocator.Get(),nullptr),"list reset");UINT index=swap->GetCurrentBackBufferIndex();
        D3D12_RESOURCE_BARRIER barrier{};barrier.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;barrier.Transition.pResource=buffers[index].Get();
        barrier.Transition.Subresource=D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;barrier.Transition.StateBefore=D3D12_RESOURCE_STATE_PRESENT;barrier.Transition.StateAfter=D3D12_RESOURCE_STATE_RENDER_TARGET;
        list->ResourceBarrier(1,&barrier);auto handle=heap->GetCPUDescriptorHandleForHeapStart();handle.ptr+=index*stride;
        float blue[]{0,0,1,1};list->ClearRenderTargetView(handle,blue,0,nullptr);
        std::swap(barrier.Transition.StateBefore,barrier.Transition.StateAfter);list->ResourceBarrier(1,&barrier);Check(list->Close(),"list close");
        ID3D12CommandList* commands[]{list.Get()};queue->ExecuteCommandLists(1,commands);
    }
    void present(){DXGI_PRESENT_PARAMETERS full{};Check(present1?swap->Present1(1,0,&full):swap->Present(1,0),"D3D12 Present");wait();}
    void draw(){submit();present();}
    void resize(UINT w,UINT h){if(w<64||h<64||w>1024||h>1024)throw std::runtime_error("fixture size");
        wait();for(auto& buffer:buffers)buffer.Reset();Check(swap->ResizeBuffers(bufferCount,w,h,DXGI_FORMAT_UNKNOWN,0),"D3D12 resize");
        width=w;height=h;targets();SetWindowPos(window,nullptr,40,40,w,h,SWP_NOZORDER|SWP_NOACTIVATE);draw();}
    ~Fixture(){if(window)DestroyWindow(window);if(event)CloseHandle(event);}
};
int main(int argc,char** argv){try{
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    Fixture fixture(argc>1&&std::string(argv[1])=="--warp");
    std::cout<<"READY "<<reinterpret_cast<UINT_PTR>(fixture.window)<<std::endl;
    bool done=false;std::string pending;
    while(!done){MSG message{};while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
        DWORD available{};auto input=GetStdHandle(STD_INPUT_HANDLE);
        if(!PeekNamedPipe(input,nullptr,0,nullptr,&available,nullptr))break;
        if(!available){Sleep(2);continue;}
        char bytes[512];DWORD read{};if(!ReadFile(input,bytes,(std::min)(available,static_cast<DWORD>(sizeof bytes)),&read,nullptr)||!read)break;
        pending.append(bytes,read);size_t end;
        while((end=pending.find('\n'))!=std::string::npos){auto command=pending.substr(0,end);pending.erase(0,end+1);
            std::istringstream words(command);std::string op;words>>op;
            if(op=="STOP"){done=true;break;}
            if(op=="RESIZE"){UINT w{},h{};words>>w>>h;fixture.resize(w,h);}
            else if(op=="HIDE")ShowWindow(fixture.window,SW_HIDE);
            else if(op=="SHOW")SetWindowPos(fixture.window,HWND_TOPMOST,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE|SWP_SHOWWINDOW);
            else if(op=="RAISE"){
                if(!SetWindowPos(fixture.window,HWND_TOPMOST,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE|SWP_SHOWWINDOW))throw std::runtime_error("fixture raise");
            }
            else fixture.draw();
            std::cout<<"OK"<<std::endl;
        }
    }
    std::cout<<"D3D12_FIXTURE clean-exit=true"<<std::endl;return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<std::endl;return 1;}}
