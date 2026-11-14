#include "JvmHost.h"
#include <iostream>
#include <thread>
int wmain(int argc,wchar_t** argv){
    if(argc!=2&&argc!=3)return 2;
    auto module=GetModuleHandleW(nullptr);
    WNDCLASSW wc{};wc.hInstance=module;wc.lpfnWndProc=DefWindowProcW;wc.lpszClassName=L"Muonium.JavaSmoke.Owner";
    RegisterClassW(&wc);
    auto owner=CreateWindowExW(0,wc.lpszClassName,L"Offline fixture only",WS_OVERLAPPEDWINDOW,0,0,1280,720,nullptr,nullptr,module,nullptr);
    if(!owner)return 3;
    if(argc==3){
        if(std::wstring_view(argv[2])!=L"--cancel-reopen")return 2;
        std::atomic<bool> cancel{};
        std::thread stopper([&]{Sleep(500);cancel=true;});
        int stopped=mp::RunJvmPlayer(module,owner,std::filesystem::absolute(argv[1]),true,&cancel);
        stopper.join();if(stopped)return stopped;
        std::cout<<"Companion cancellation completed; reopening in the same process\n";
    }
    int result=mp::RunJvmPlayer(module,owner,std::filesystem::absolute(argv[1]),true);
    DestroyWindow(owner);std::cout<<"Companion hidden lifecycle result="<<result<<"\n";return result;
}
