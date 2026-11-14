#include "GameMetrics.h"
#include <cmath>
namespace mp {
void GameFrameCounter::reset() noexcept {samples_.clear();started_=false;previous_=refreshed_=0;fps_=-1;}
int GameFrameCounter::frame(double now){
    if(!std::isfinite(now)){reset();return fps_;}
    if(!started_){started_=true;previous_=refreshed_=now;return fps_;}
    double delta=now-previous_;
    if(delta<=0||delta>=2||samples_.size()>=65536){reset();started_=true;previous_=refreshed_=now;return fps_;}
    previous_=now;
    while(!samples_.empty() && now-samples_.front().end>.5+1e-9)samples_.pop_front();
    samples_.push_back({now,delta});
    if(now-refreshed_>=.5-1e-9){
        refreshed_=now;double duration=0;for(auto const& s:samples_)duration+=s.duration;
        double value=samples_.size()/duration;
        fps_=value>=0&&value<=999999?static_cast<int>(std::lround(value)):-1;
    }
    return fps_;
}
void ConnectionMetrics::reset() noexcept {owner_=peer_=0;auto epoch=value_.epoch+1;value_={};value_.epoch=epoch;}
void ConnectionMetrics::observe(std::uintptr_t owner,std::uintptr_t peer,bool local,int ping,std::uint64_t now) noexcept {
    if(!owner||!peer){reset();return;}
    if(owner!=owner_||peer!=peer_||value_.kind!=(local?1u:2u))reset();
    owner_=owner;peer_=peer;value_.kind=local?1:2;
    value_.at=now;value_.ping=local?0:ping>=0&&ping<65535?ping:-1;
}
MetricSnapshot ConnectionMetrics::snapshot(std::uint64_t now,std::uintptr_t owner) const noexcept {
    if(!owner||owner_!=owner||!value_.at||now<value_.at||now-value_.at>3000)return {};
    return value_;
}
}
