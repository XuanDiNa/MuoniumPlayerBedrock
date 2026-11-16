#pragma once
#include "Runtime.h"
#include "sdk/ModMenuApi.h"
#include "Local3SettingsRoute.h"
#include "sdk/ModMenuWorkspace.h"
#include "WorkspaceHandoff.h"
#include <atomic>
namespace mp {
class MenuBEBridge {
    struct Request {std::atomic<bool> alive{true},enabled{},pending{};};
    Request* request_;
    HMODULE module_{};ModMenuApi api_{};bool registered_{};
    std::filesystem::path folder_;
    Local3SettingsRoute route_;
    bool settingsRequested_{},routed_{};
    ModMenuWorkspaceApi workspace_{};bool workspaceExport_{};
    std::uint64_t session_{},revision_{};bool lastEnabled_{};
    WorkspaceChannel& channel_;
    void WorkspaceStep(bool);
    static void Open(void*) noexcept;
public:
    explicit MenuBEBridge(std::filesystem::path,WorkspaceChannel&);
    ~MenuBEBridge();
    MenuBEBridge(MenuBEBridge const&)=delete;
    void Step(HWND,bool);
    void OpenSettings(){settingsRequested_=true;}
};
}
