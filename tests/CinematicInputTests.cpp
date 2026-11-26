#include "CinematicInput.h"
#include <iostream>
#include <stdexcept>
void need(bool v,char const* why){if(!v)throw std::runtime_error(why);}
int main(){try{
    WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"Muonium.OwnedInputFixture";
    need(RegisterClassW(&wc)!=0,"register owned fixture");
    auto window=CreateWindowExW(0,wc.lpszClassName,L"",0,0,0,1,1,HWND_MESSAGE,nullptr,wc.hInstance,nullptr);
    need(window!=nullptr,"owned message window");
    mp::CinematicInput observer;need(observer.start(window,wc.hInstance),"owner queue observer");
    auto serial=observer.serial();
    auto pump=[&]{MSG msg{};while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}};
    for(unsigned i=0;i<100;i++){PostMessageW(window,WM_MOUSEMOVE,0,MAKELPARAM(640,360));pump();}
    need(observer.serial()==serial,"programmatic cursor recenter must not count as physical input");
    PostMessageW(window,WM_KEYDOWN,'W',0);pump();need(observer.serial()>serial,"actual keyboard queue event revokes idle");serial=observer.serial();
    PostMessageW(window,WM_LBUTTONDOWN,MK_LBUTTON,0);pump();need(observer.serial()>serial,"mouse button revokes idle");serial=observer.serial();
    PostMessageW(window,WM_MOUSEWHEEL,MAKEWPARAM(0,WHEEL_DELTA),0);pump();need(observer.serial()>serial,"wheel revokes idle");
    auto other=CreateWindowExW(0,wc.lpszClassName,L"",0,0,0,1,1,HWND_MESSAGE,nullptr,wc.hInstance,nullptr);
    serial=observer.serial();PostMessageW(other,WM_KEYDOWN,'W',0);pump();need(observer.serial()==serial,"unrelated owner cannot reset idle");
    observer.stop();need(!observer.available(),"stop releases observer");serial=observer.serial();
    PostMessageW(window,WM_KEYDOWN,'W',0);pump();need(observer.serial()==serial,"dormant observer forwards without reading");
    need(!observer.start(nullptr,wc.hInstance),"missing owner fails closed");
    DestroyWindow(other);DestroyWindow(window);
    std::cout<<"Owned queue physical-input boundary, recenter rejection, focus owner and stop passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
