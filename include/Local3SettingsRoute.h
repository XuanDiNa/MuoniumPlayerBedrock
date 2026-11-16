#pragma once
#include "Runtime.h"
namespace mp {
// Only the player's description is routed. The installed ModMenu and user config stay intact.
class Local3SettingsRoute {
    std::filesystem::path folder_;
    bool editing_{},seenOpen_{};
    ULONGLONG requestedAt_{};
public:
    explicit Local3SettingsRoute(std::filesystem::path folder):folder_(std::move(folder)){}
    bool HideDescription() noexcept;
    bool RestoreDescription() noexcept;
    void Begin() noexcept {editing_=true;seenOpen_=false;requestedAt_=GetTickCount64();}
    bool KeepEditing(bool menuOpen) noexcept {
        if(!editing_)return false;
        seenOpen_|=menuOpen;
        if((seenOpen_&&!menuOpen)||(!seenOpen_&&GetTickCount64()-requestedAt_>10000))editing_=false;
        return editing_;
    }
};
}
