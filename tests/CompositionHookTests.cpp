// Production callbacks in a disposable process; production admission is intact.
#include "../src/CompositionBridge.cpp"
#include <iostream>
#include <stdexcept>
#include <dwmapi.h>
#pragma comment(lib,"gdi32.lib")
#pragma comment(lib,"dwmapi.lib")
namespace mp {void Log(std::filesystem::path const&,std::string const&)noexcept{}}
static unsigned checks;
static void Check(bool v,char const* m){++checks;if(!v)throw std::runtime_error(m);}
// Inspect exactly what is handed to real DXGI, not a direct Compose-only call.
static mp::Present realFixturePresent;static mp::Present1 realFixturePresent1;
static unsigned presentedPixels;static bool inspectPixels;static HRESULT forcedResult=S_OK;
static void InspectPresented(IDXGISwapChain* swap){
    if(!inspectPixels)return;
    using Microsoft::WRL::ComPtr;ComPtr<ID3D11Device>d;swap->GetDevice(IID_PPV_ARGS(&d));ComPtr<ID3D11DeviceContext>c;d->GetImmediateContext(&c);
    ComPtr<ID3D11Texture2D>buffer,read;swap->GetBuffer(0,IID_PPV_ARGS(&buffer));D3D11_TEXTURE2D_DESC td{};buffer->GetDesc(&td);
    td.Usage=D3D11_USAGE_STAGING;td.BindFlags=0;td.CPUAccessFlags=D3D11_CPU_ACCESS_READ;td.MiscFlags=0;
    Check(SUCCEEDED(d->CreateTexture2D(&td,nullptr,&read)),"present readback allocation");c->CopyResource(read.Get(),buffer.Get());D3D11_MAPPED_SUBRESOURCE map{};
    Check(SUCCEEDED(c->Map(read.Get(),0,D3D11_MAP_READ,0,&map)),"present readback map");
    auto p=static_cast<unsigned char*>(map.pData)+10*map.RowPitch+10*4;
    bool good=p[0]>=126&&p[0]<=128&&p[1]==0&&p[2]>=126&&p[2]<=128;c->Unmap(read.Get(),0);
    Check(good,"every presented crop blended once, preserving blue scene");++presentedPixels;
}
static HRESULT WINAPI ObservePresent(IDXGISwapChain* s,UINT interval,UINT flags){InspectPresented(s);return forcedResult!=S_OK?forcedResult:realFixturePresent(s,interval,flags);}
static HRESULT WINAPI ObservePresent1(IDXGISwapChain1* s,UINT interval,UINT flags,DXGI_PRESENT_PARAMETERS const* p){InspectPresented(s);return forcedResult!=S_OK?forcedResult:realFixturePresent1(s,interval,flags,p);}
int wmain(int argc,wchar_t** argv){try{
    bool desktop=argc==2&&std::wstring(argv[1])==L"--desktop";
    if(desktop)SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    using Microsoft::WRL::ComPtr;
    WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"CompositionFixture";RegisterClassW(&wc);
    auto owner=CreateWindowExW(0,wc.lpszClassName,L"",WS_POPUP,0,0,64,64,nullptr,nullptr,wc.hInstance,nullptr);Check(owner!=nullptr,"owned hidden window");
    std::wstring prefix=L"Local\\MuoniumBedrock-00000000000000000000000000000000";
    Check(mp::OpenCompositionBridge(prefix,{},owner,true),"private composition lifetime");
    // Exercise the old D3D11 hooks only; production D3D12 is tested separately.
    mp::offline=true;mp::EnableCompositionBridge(true,0);
    Check(!mp::active&&!mp::attempted&&*reinterpret_cast<LONG*>(mp::shared+3072)==0,
        "production release keeps unaccepted native presentation dormant");
    mp::offline=true;
    InterlockedExchange(reinterpret_cast<LONG*>(mp::shared+3072),1);
    InterlockedExchange(reinterpret_cast<LONG*>(mp::shared+3076),1);
    auto dll=LoadLibraryExW(L"d3d11.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);auto create=reinterpret_cast<decltype(&D3D11CreateDeviceAndSwapChain)>(GetProcAddress(dll,"D3D11CreateDeviceAndSwapChain"));
    DXGI_SWAP_CHAIN_DESC desc{};desc.BufferDesc.Width=desc.BufferDesc.Height=64;desc.BufferDesc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;desc.SampleDesc.Count=1;desc.BufferCount=2;
    desc.BufferUsage=DXGI_USAGE_RENDER_TARGET_OUTPUT;desc.OutputWindow=owner;desc.Windowed=TRUE;desc.SwapEffect=DXGI_SWAP_EFFECT_DISCARD;
    ComPtr<ID3D11Device>d;ComPtr<ID3D11DeviceContext>c;ComPtr<IDXGISwapChain>swap;Check(SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&desc,&swap,&d,nullptr,&c)),"owned WARP swapchain");
    ComPtr<IDXGISwapChain3>swap3;Check(SUCCEEDED(swap.As(&swap3)),"SwapChain3");
    auto api=*reinterpret_cast<void***>(swap3.Get());auto ctx=*reinterpret_cast<void***>(c.Get());
    void* targets[]{api[8],api[22],api[13],api[39],ctx[12],ctx[53],ctx[14],ctx[15]};
    void* callbacks[]{reinterpret_cast<void*>(mp::PresentHook),reinterpret_cast<void*>(mp::Present1Hook),reinterpret_cast<void*>(mp::ResizeHook),reinterpret_cast<void*>(mp::Resize1Hook),reinterpret_cast<void*>(mp::Indexed),reinterpret_cast<void*>(mp::Clearing),reinterpret_cast<void*>(mp::Mapped),reinterpret_cast<void*>(mp::Unmapped)};
    void** originals[]{reinterpret_cast<void**>(&mp::originalPresent),reinterpret_cast<void**>(&mp::originalPresent1),reinterpret_cast<void**>(&mp::originalResize),reinterpret_cast<void**>(&mp::originalResize1),reinterpret_cast<void**>(&mp::originalDraw),reinterpret_cast<void**>(&mp::originalClear),reinterpret_cast<void**>(&mp::originalMap),reinterpret_cast<void**>(&mp::originalUnmap)};
    Check(MH_Initialize()==MH_OK,"fixture MinHook");for(unsigned i=0;i<8;++i){Check(mp::Pristine(targets[i]),"pristine system API");Check(MH_CreateHook(targets[i],callbacks[i],originals[i])==MH_OK&&MH_EnableHook(targets[i])==MH_OK,"owned production callback");}
    mp::active=true;
    if(argc==3){
        std::wstring command=L"\""+std::wstring(argv[1])+L"\" -Dmuonium.bedrock.offlineTest=true -cp \""+argv[2]+L"\" com.muoniumplayer.bedrock.CompositionProducer "+prefix;
        SECURITY_ATTRIBUTES security{sizeof security,nullptr,TRUE};
        auto log=CreateFileW(L"artifacts/composition-producer.log",GENERIC_WRITE,FILE_SHARE_READ,&security,CREATE_ALWAYS,0,nullptr);
        auto input=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,nullptr);
        STARTUPINFOW startup{sizeof startup};startup.dwFlags=STARTF_USESTDHANDLES;startup.hStdOutput=startup.hStdError=log;startup.hStdInput=input;PROCESS_INFORMATION child{};
        Check(CreateProcessW(argv[1],command.data(),nullptr,nullptr,TRUE,CREATE_NO_WINDOW,nullptr,nullptr,&startup,&child),"separate Java producer");CloseHandle(log);CloseHandle(input);
        Check(WaitForSingleObject(child.hProcess,45000)==WAIT_OBJECT_0,"Java producer completion");DWORD exit{};GetExitCodeProcess(child.hProcess,&exit);CloseHandle(child.hThread);CloseHandle(child.hProcess);Check(exit==0,"real Java Skija render success");
        mp::CompositionHeader h;std::memcpy(&h,mp::shared,sizeof h);Check(mp::ValidComposition(h,GetTickCount64())&&h.planeCount>0,"real camera independent Skija plane");
        unsigned ink{};auto atlas=reinterpret_cast<unsigned char*>(mp::shared+4096+mp::CompositionScreenBytes);
        for(unsigned at=3;at<2048*h.atlasHeight*4;at+=4)if(atlas[at])++ink;
        Check(ink>20,"original Skija glyph pixels exported into world atlas");
        // Probe is tested separately from an admitted, visible composition.
        Check(h.reserved==mp::CompositionProbeOnly,"real Java negotiation is probe-only");
        h.planeCount=0;h.atlasHeight=0;h.reserved=0;std::memcpy(mp::shared,&h,sizeof h);
    }else{
        mp::CompositionHeader h;h.sequence=1;h.at=GetTickCount64();h.active=1;h.viewportWidth=h.viewportHeight=64;h.x=4;h.y=8;h.width=h.height=16;
        std::memcpy(mp::shared,&h,sizeof h);auto pixels=reinterpret_cast<unsigned*>(mp::shared+4096);for(unsigned i=0;i<16*16;++i)pixels[i]=0xFF00FF00;
    }
    Check(!mp::Compose(swap.Get(),DXGI_PRESENT_TEST)&&mp::presents==0,"test-present bypass");
    Check(mp::Compose(swap.Get(),0),"production owner-swapchain composition");
    ComPtr<ID3D11Texture2D>buffer;swap->GetBuffer(0,IID_PPV_ARGS(&buffer));D3D11_TEXTURE2D_DESC td{};buffer->GetDesc(&td);td.Usage=D3D11_USAGE_STAGING;td.BindFlags=0;td.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
    ComPtr<ID3D11Texture2D>staging;d->CreateTexture2D(&td,nullptr,&staging);c->CopyResource(staging.Get(),buffer.Get());D3D11_MAPPED_SUBRESOURCE mapped{};
    Check(SUCCEEDED(c->Map(staging.Get(),0,D3D11_MAP_READ,0,&mapped)),"read game-like backbuffer");auto pixel=static_cast<unsigned char*>(mapped.pData)+10*mapped.RowPitch+10*4;
    Check(pixel[1]==255&&pixel[0]==0&&pixel[2]==0,"Java Skija color composed in native backbuffer");c->Unmap(staging.Get(),0);buffer.Reset();staging.Reset();
    mp::WorldPacket world;world.valid=1;world.epoch=7;world.at=GetTickCount64();world.projection[0]=world.projection[5]=1;world.projection[10]=-1.01f;world.projection[11]=-1;world.projection[14]=-.2f;
    float view[16]{};view[0]=view[5]=view[10]=view[15]=1;mp::CompositionCamera(world,view);
    D3D11_BUFFER_DESC bd{};bd.ByteWidth=480;bd.Usage=D3D11_USAGE_DYNAMIC;bd.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;bd.BindFlags=D3D11_BIND_CONSTANT_BUFFER;ComPtr<ID3D11Buffer>cb;
    d->CreateBuffer(&bd,nullptr,&cb);mp::tracked[0].cb=cb;
    Check(SUCCEEDED(c->Map(cb.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)),"real native camera Map");std::memset(mapped.pData,0,480);std::memcpy(mapped.pData,world.projection,64);std::memcpy(static_cast<char*>(mapped.pData)+64,view,64);c->Unmap(cb.Get(),0);
    Check(mp::tracked[0].valid&&mp::cameraMatches==1,"production CPU camera captured without GPU readback");
    Check(SUCCEEDED(c->Map(cb.Get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)),"second owned camera mapping");
    // Simulate the render transport being busy during Unmap. The mapped pointer
    // must be revoked even when no owned-value capture can be made.
    {std::lock_guard lock(mp::mutex);c->Unmap(cb.Get(),0);}
    Check(std::all_of(mp::mappedValues.begin(),mp::mappedValues.end(),[](auto const& v){return !v.resource&&!v.pointer;}),"no mapped pointer survives busy Unmap");
    realFixturePresent=mp::originalPresent;realFixturePresent1=mp::originalPresent1;mp::originalPresent=ObservePresent;mp::originalPresent1=ObservePresent1;
    c->ClearState();
    // Multiple buffers, production callback nesting, dirty-rect Present1 and a
    // render target rebound by the game on each frame.
    for(auto effect:{DXGI_SWAP_EFFECT_DISCARD,DXGI_SWAP_EFFECT_FLIP_SEQUENTIAL,DXGI_SWAP_EFFECT_FLIP_DISCARD}){
        mp::Reset(swap.Get());swap3.Reset();swap.Reset();desc.SwapEffect=effect;
        Check(SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,D3D11_CREATE_DEVICE_SINGLETHREADED|D3D11_CREATE_DEVICE_BGRA_SUPPORT,nullptr,0,D3D11_SDK_VERSION,&desc,&swap,&d,nullptr,&c)),"threading-compatible real swap effect");
        Check(SUCCEEDED(swap.As(&swap3)),"real repeated Present1 interface");
        for(unsigned step=0;step<48;++step){
            mp::CompositionHeader next;next.sequence=step+1;next.at=GetTickCount64();next.active=1;next.viewportWidth=next.viewportHeight=64;next.x=4;next.y=8;next.width=next.height=16;
            std::memcpy(mp::shared,&next,sizeof next);auto pixels=reinterpret_cast<unsigned*>(mp::shared+4096);for(unsigned i=0;i<256;++i)pixels[i]=0x80800000;
            ComPtr<ID3D11Texture2D>back;ComPtr<ID3D11RenderTargetView>rt;swap->GetBuffer(0,IID_PPV_ARGS(&back));d->CreateRenderTargetView(back.Get(),nullptr,&rt);
            auto raw=rt.Get();c->OMSetRenderTargets(1,&raw,nullptr);float blue[]{0,0,1,1};c->ClearRenderTargetView(rt.Get(),blue);
            inspectPixels=true;HRESULT result;
            if(step%2){RECT dirty{0,0,2,2};DXGI_PRESENT_PARAMETERS params{1,&dirty,nullptr,nullptr};result=swap3->Present1(0,0,&params);}
            else result=swap->Present(0,0);
            inspectPixels=false;Check(SUCCEEDED(result),"real consecutive Present result");
            back.Reset();rt.Reset();
        }
        c->ClearState();Check(SUCCEEDED(swap->ResizeBuffers(2,64,64,DXGI_FORMAT_UNKNOWN,0)),"repeated Present releases resize references");
    }
    std::cout<<"Repeated actual Present/Present1 inspected="<<presentedPixels<<'\n';
    if(desktop){
        // Hardware + real DWM output, without a pre-Present staging readback.
        // The short-lived owned test window never takes foreground focus.
        c->ClearState();mp::Reset(swap.Get());swap3.Reset();swap.Reset();
        desc.BufferDesc.Width=320;desc.BufferDesc.Height=240;desc.SwapEffect=DXGI_SWAP_EFFECT_FLIP_DISCARD;
        D3D_FEATURE_LEVEL levels[]{D3D_FEATURE_LEVEL_11_1,D3D_FEATURE_LEVEL_11_0};
        Check(SUCCEEDED(create(nullptr,D3D_DRIVER_TYPE_HARDWARE,nullptr,D3D11_CREATE_DEVICE_SINGLETHREADED|D3D11_CREATE_DEVICE_BGRA_SUPPORT,levels,2,D3D11_SDK_VERSION,&desc,&swap,&d,nullptr,&c)),"hardware presentation device flags33");
        Check(SUCCEEDED(swap.As(&swap3)),"hardware Present1");
        SetWindowPos(owner,HWND_TOPMOST,40,40,320,240,SWP_NOACTIVATE|SWP_SHOWWINDOW);
        MSG message{};while(PeekMessageW(&message,nullptr,0,0,PM_REMOVE)){TranslateMessage(&message);DispatchMessageW(&message);}
        for(unsigned step=0;step<64;++step){
            mp::CompositionHeader next;next.sequence=200+step;next.at=GetTickCount64();next.active=1;next.viewportWidth=320;next.viewportHeight=240;
            next.x=4;next.y=8;next.width=next.height=16;std::memcpy(mp::shared,&next,sizeof next);
            auto pixels=reinterpret_cast<unsigned*>(mp::shared+4096);for(unsigned i=0;i<256;++i)pixels[i]=0x80800000;
            ComPtr<ID3D11Texture2D> back;ComPtr<ID3D11RenderTargetView> rt;swap->GetBuffer(0,IID_PPV_ARGS(&back));d->CreateRenderTargetView(back.Get(),nullptr,&rt);
            auto raw=rt.Get();c->OMSetRenderTargets(1,&raw,nullptr);float blue[]{0,0,1,1};c->ClearRenderTargetView(rt.Get(),blue);
            DXGI_PRESENT_PARAMETERS full{};Check(swap3->Present1(1,0,&full)==S_OK,"hardware asynchronous Present1");
            if(step%8==7){
                Check(SUCCEEDED(DwmFlush()),"display composition completed");
                POINT sample{10,10};ClientToScreen(owner,&sample);auto dc=GetDC(nullptr);auto value=GetPixel(dc,sample.x,sample.y);ReleaseDC(nullptr,dc);
                auto red=GetRValue(value),green=GetGValue(value),blueValue=GetBValue(value);
                auto status=mp::gpu.status();
                std::cout<<"DWM sample "<<step<<" RGB="<<unsigned(red)<<','<<unsigned(green)<<','<<unsigned(blueValue)<<" point="<<sample.x<<','<<sample.y<<" stage="<<status.stage<<" nativePresents="<<mp::presents<<" uploads="<<mp::uploads<<'\n';
                Check(red>=124&&red<=132&&green<=2&&blueValue>=124&&blueValue<=132,"final displayed crop contains composition");
            }
        }
        ShowWindow(owner,SW_HIDE);c->ClearState();mp::Reset(swap.Get());
        Check(SUCCEEDED(swap->ResizeBuffers(2,64,64,DXGI_FORMAT_UNKNOWN,0)),"restore hidden fixture size");
    }
    // Drawing into a backbuffer is not proof that DXGI displayed it.
    mp::CompositionHeader failed;failed.sequence=100;failed.at=GetTickCount64();failed.active=1;failed.viewportWidth=failed.viewportHeight=64;failed.width=failed.height=1;
    std::memcpy(mp::shared,&failed,sizeof failed);InterlockedExchange64(reinterpret_cast<LONG64*>(mp::shared+3080),0);
    forcedResult=DXGI_ERROR_WAS_STILL_DRAWING;Check(swap->Present(0,DXGI_PRESENT_DO_NOT_WAIT)==forcedResult,"non-blocking Present rejected by DXGI");forcedResult=S_OK;
    Check(*reinterpret_cast<LONG64*>(mp::shared+3080)==0,"rejected Present cannot acknowledge visible output");
    InterlockedExchange(reinterpret_cast<LONG*>(mp::shared+3076),0);
    Check(!mp::Compose(swap.Get(),0),"external ownership immediately revokes old native frame");
    mp::Presented(S_OK,true);
    Check(*reinterpret_cast<LONG64*>(mp::shared+3080)==0,"focus suspension cannot reacquire output through a late Present");
    InterlockedExchange(reinterpret_cast<LONG*>(mp::shared+3076),1);
    mp::originalPresent=realFixturePresent;mp::originalPresent1=realFixturePresent1;
    c->ClearState();Check(SUCCEEDED(swap->ResizeBuffers(2,80,48,DXGI_FORMAT_UNKNOWN,0)),"real ResizeBuffers releases backbuffer references");
    mp::CloseCompositionBridge();Check(!mp::active&&!mp::shared,"companion shutdown clears presentation");
    for(auto target:targets)Check(MH_DisableHook(target)==MH_OK&&MH_RemoveHook(target)==MH_OK,"fixture removes owned hooks");MH_Uninitialize();DestroyWindow(owner);
    std::cout<<"Production composition hooks and lifecycle "<<checks<<" checks passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<'\n';return 1;}}
