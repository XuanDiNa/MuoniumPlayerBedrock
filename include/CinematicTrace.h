#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <cmath>
#include <algorithm>
namespace mp {
enum class CinematicTracePhase : unsigned {Frame,Render,Saved};
struct CinematicTraceSample {
    CinematicTracePhase phase{};
    std::uint64_t at{},epoch{},input{},commandAt{},command{};
    bool eligible{},valid{},applied{};
    float baseFov{},offset{},renderFov{},aspect{},yaw{},pitch{},roll{};
    double position[3]{};
};
struct CinematicTraceEvent {
    CinematicTracePhase phase{};std::uint32_t reasons{};
    std::uint64_t at{},command{},age{};
    bool eligible{},valid{},applied{};
    float base{},offset{},output{},aspect{},baseDelta{},offsetDelta{},outputDelta{},aspectDelta{},positionDelta{},rotationDelta{};
};
struct CinematicTraceWindow {
    std::uint64_t samples{},applied{},saved{},losses{},resumes{},resets{},invalid{},skipped{},droppedEvents{},maxAge{},maxGap{};
    float maxBaseDelta{},maxOffsetDelta{},maxOutputDelta{},maxAspectDelta{},maxPositionDelta{},maxRotationDelta{};
    unsigned events{};std::array<CinematicTraceEvent,8> event{};
};
// Diagnostics own only copied scalar values, never game objects. Camera calls
// try once and skip capture on contention; logging copies a bounded window and
// releases this independent lock before formatting or touching a file.
class CinematicTrace {
    std::mutex mutex_;std::atomic<bool> stopped_{};std::atomic<std::uint64_t> skipped_{};
    std::array<CinematicTraceSample,3> previous_{};std::array<bool,3> seen_{};
    CinematicTraceWindow window_{};unsigned windows_{};bool started_{};
public:
    enum Reason : std::uint32_t {BaseStep=1,OffsetStep=2,AspectStep=4,PositionStep=8,RotationStep=16,ApplicationLost=32,OutputMismatch=64};
    static constexpr unsigned MaxWindows=120;
    void observe(CinematicTraceSample const& s) noexcept {
        if(stopped_.load(std::memory_order_relaxed))return;
        std::unique_lock lock(mutex_,std::try_to_lock);
        if(!lock.owns_lock()){++skipped_;return;}
        if(stopped_.load(std::memory_order_relaxed))return;
        auto phase=static_cast<unsigned>(s.phase);if(phase>=previous_.size())return;
        started_=true;++window_.samples;
        if(s.applied){
            ++window_.applied;if(s.phase==CinematicTracePhase::Saved)++window_.saved;
            if(!std::isfinite(s.baseFov)||!std::isfinite(s.offset)||!std::isfinite(s.renderFov)||!std::isfinite(s.aspect)
                ||!std::isfinite(s.yaw)||!std::isfinite(s.pitch)||!std::isfinite(s.roll)
                ||!std::isfinite(s.position[0])||!std::isfinite(s.position[1])||!std::isfinite(s.position[2])){
                ++window_.invalid;seen_[phase]=false;return;
            }
            if(s.commandAt&&s.commandAt<=s.at)window_.maxAge=(std::max)(window_.maxAge,s.at-s.commandAt);
        }
        auto const& before=previous_[phase];
        bool comparable=seen_[phase]&&s.at>=before.at&&s.at-before.at<=250&&s.epoch==before.epoch&&s.input==before.input;
        CinematicTraceEvent event;event.phase=s.phase;event.at=s.at;event.command=s.command;
        event.age=s.commandAt&&s.at>=s.commandAt?s.at-s.commandAt:0;
        event.eligible=s.eligible;event.valid=s.valid;event.applied=s.applied;
        event.base=s.baseFov;event.offset=s.offset;event.output=s.renderFov;event.aspect=s.aspect;
        if(seen_[phase]&&!comparable)++window_.resets;
        if(comparable){
            window_.maxGap=(std::max)(window_.maxGap,s.at-before.at);
            if(before.applied&&!s.applied){++window_.losses;event.reasons|=ApplicationLost;}
            if(!before.applied&&s.applied)++window_.resumes;
            if(before.applied&&s.applied){
                event.baseDelta=std::abs(s.baseFov-before.baseFov);event.offsetDelta=std::abs(s.offset-before.offset);
                event.outputDelta=std::abs(s.renderFov-before.renderFov);event.aspectDelta=std::abs(s.aspect-before.aspect);
                double distance{};for(int i=0;i<3;i++){auto d=s.position[i]-before.position[i];distance+=d*d;}
                event.positionDelta=static_cast<float>(std::sqrt(distance));
                event.rotationDelta=(std::max)({std::abs(std::remainder(s.yaw-before.yaw,360.f)),std::abs(s.pitch-before.pitch),std::abs(s.roll-before.roll)});
                window_.maxBaseDelta=(std::max)(window_.maxBaseDelta,event.baseDelta);window_.maxOffsetDelta=(std::max)(window_.maxOffsetDelta,event.offsetDelta);
                window_.maxOutputDelta=(std::max)(window_.maxOutputDelta,event.outputDelta);window_.maxAspectDelta=(std::max)(window_.maxAspectDelta,event.aspectDelta);
                window_.maxPositionDelta=(std::max)(window_.maxPositionDelta,event.positionDelta);window_.maxRotationDelta=(std::max)(window_.maxRotationDelta,event.rotationDelta);
                if(event.baseDelta>=.5f)event.reasons|=BaseStep;
                if(event.offsetDelta>=2.f)event.reasons|=OffsetStep;
                if(event.aspectDelta>=.01f)event.reasons|=AspectStep;
                if(event.positionDelta>=.5f)event.reasons|=PositionStep;
                if(event.rotationDelta>=10.f)event.reasons|=RotationStep;
            }
        }
        if(s.applied&&std::abs(s.renderFov-(s.baseFov+s.offset))>.02f)event.reasons|=OutputMismatch;
        if(event.reasons){
            if(window_.events<window_.event.size())window_.event[window_.events++]=event;else ++window_.droppedEvents;
        }
        previous_[phase]=s;seen_[phase]=true;
    }
    CinematicTraceWindow take(){
        std::lock_guard lock(mutex_);auto result=window_;window_={};result.skipped=skipped_.exchange(0);
        if(started_&&++windows_>=MaxWindows)stopped_.store(true,std::memory_order_relaxed);
        return result;
    }
};
}
