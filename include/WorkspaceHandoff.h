#pragma once
#include <cstdint>
#include <mutex>
namespace mp {
enum class WorkspacePhase : std::uint32_t {None,Requested,Created,Failed,Accepted,Return,Cancelled};
struct WorkspaceRequest {
    std::uint64_t session{},request{},deadline{};
    std::uint32_t control{};
    WorkspacePhase phase{};
};
// A single explicit handoff, shared only by the menu worker and owned JVM host.
class WorkspaceChannel {
    std::mutex mutex_;WorkspaceRequest state_;
public:
    WorkspaceRequest Read(){std::lock_guard lock(mutex_);return state_;}
    void Offer(WorkspaceRequest r){std::lock_guard lock(mutex_);state_=r;}
    bool Transition(WorkspaceRequest const&r,WorkspacePhase from,WorkspacePhase to){
        std::lock_guard lock(mutex_);
        if(state_.session!=r.session||state_.request!=r.request||state_.phase!=from)return false;
        state_.phase=to;return true;
    }
};
}
