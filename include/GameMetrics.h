#pragma once
#include <cstdint>
#include <deque>
namespace mp {
// Same recent-interval FPS calculation as SodiumExtra, driven by the game.
class GameFrameCounter {
    struct Interval {double end,duration;};
    std::deque<Interval> samples_;
    bool started_{};
    double previous_{},refreshed_{};
    int fps_{-1};
public:
    int frame(double now);
    int value() const noexcept{return fps_;}
    void reset() noexcept;
};
struct MetricSnapshot {int ping{-1};std::uint32_t kind{};std::uint64_t at{},epoch{};};
class ConnectionMetrics {
    std::uintptr_t owner_{},peer_{};
    MetricSnapshot value_;
public:
    void observe(std::uintptr_t owner,std::uintptr_t peer,bool local,int ping,std::uint64_t now) noexcept;
    MetricSnapshot snapshot(std::uint64_t now,std::uintptr_t owner) const noexcept;
    void reset() noexcept;
};
// Owned scalar copies only. Native objects are borrowed on their update stack.
bool InstallNetworkMetrics(std::uintptr_t image);
void SelectNetworkMetrics(std::uintptr_t owner);
MetricSnapshot ReadNetworkMetrics(std::uint64_t now,std::uintptr_t owner);
struct MetricDiagnostics {std::uint64_t networkUpdates{},selectedUpdates{},peerUpdates{},snapshots{};};
MetricDiagnostics ReadMetricDiagnostics() noexcept;
}
