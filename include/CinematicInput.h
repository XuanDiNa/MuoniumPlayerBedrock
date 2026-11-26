#pragma once
#include <windows.h>
#include <atomic>
#include <cstdint>
namespace mp {
// Observe the owner's existing input queue. Never register a raw-input target:
// registration would replace the game's process-wide target for that device.
class CinematicInput {
    inline static std::atomic<CinematicInput*> observer_{};
    std::atomic<HWND> owner_{};
    std::atomic<std::uint64_t> serial_{1},raw_{},keys_{},buttons_{};
    HHOOK hook_{};
    std::atomic<bool> absoluteKnown_{};std::atomic<LONG> absoluteX_{},absoluteY_{};
    static LRESULT CALLBACK Observe(int code,WPARAM removed,LPARAM data) noexcept {
        if(code>=0&&removed==PM_REMOVE)if(auto self=observer_.load())
            self->message(*reinterpret_cast<MSG const*>(data));
        return CallNextHookEx(nullptr,code,removed,data);
    }
    void message(MSG const& msg) noexcept {
        auto owner=owner_.load();
        if(!owner||(msg.hwnd!=owner&&!IsChild(owner,msg.hwnd)))return;
        if(msg.message==WM_INPUT) {
            RAWINPUT value{};UINT bytes=sizeof value;
            if(GetRawInputData(reinterpret_cast<HRAWINPUT>(msg.lParam),RID_INPUT,&value,&bytes,sizeof(RAWINPUTHEADER))==UINT(-1))return;
            if(value.header.dwType==RIM_TYPEMOUSE)mouse(value.data.mouse);
            else if(value.header.dwType==RIM_TYPEKEYBOARD){++keys_;++serial_;}
        }else if(msg.message==WM_KEYDOWN||msg.message==WM_KEYUP||msg.message==WM_SYSKEYDOWN||msg.message==WM_SYSKEYUP) {
            ++keys_;++serial_;
        }else if((msg.message>=WM_LBUTTONDOWN&&msg.message<=WM_XBUTTONDBLCLK)||msg.message==WM_MOUSEHWHEEL) {
            ++buttons_;++serial_;
        }
        // WM_MOUSEMOVE also reports cursor warps/recentering. Raw relative
        // motion and the independent actor-motion guard own that boundary.
    }
    void mouse(RAWMOUSE const& value) noexcept {
        bool moved;
        if(value.usFlags&MOUSE_MOVE_ABSOLUTE) {
            moved=!absoluteKnown_.load()||absoluteX_.load()!=value.lLastX||absoluteY_.load()!=value.lLastY;
            absoluteKnown_=true;absoluteX_=value.lLastX;absoluteY_=value.lLastY;
        }else moved=value.lLastX!=0||value.lLastY!=0;
        if(moved||value.usButtonFlags){++raw_;++serial_;}
    }
public:
    ~CinematicInput(){stop();}
    bool start(HWND owner,HINSTANCE module) noexcept {
        stop();DWORD process{};auto thread=GetWindowThreadProcessId(owner,&process);
        if(!thread||process!=GetCurrentProcessId())return false;
        owner_=owner;absoluteKnown_=false;observer_=this;
        hook_=SetWindowsHookExW(WH_GETMESSAGE,Observe,module,thread);
        if(!hook_){owner_=nullptr;observer_=nullptr;return false;}
        ++serial_;return true;
    }
    void stop() noexcept {
        owner_=nullptr;auto expected=this;observer_.compare_exchange_strong(expected,nullptr);
        if(hook_)UnhookWindowsHookEx(hook_);hook_=nullptr;++serial_;
    }
    bool available()const noexcept{return owner_.load()!=nullptr;}
    std::uint64_t serial()const noexcept{return serial_.load();}
    std::uint64_t rawEvents()const noexcept{return raw_.load();}
    std::uint64_t keyEvents()const noexcept{return keys_.load();}
    std::uint64_t buttonEvents()const noexcept{return buttons_.load();}
};
}
