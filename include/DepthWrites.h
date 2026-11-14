#pragma once
#include <array>
#include <atomic>
#include <cstdint>

namespace mp {
// Public graphics callbacks only compare pointer identities. They never
// dereference a watched resource, allocate, or take the depth/render mutex.
// registration/reset have one owner; notifications may come from other threads.
class DepthWrites {
    struct Watch {
        std::atomic<void*> resource{};
        std::atomic<std::uint64_t> revision{};
    };
    std::array<Watch,4> watches;
    std::atomic<std::uint64_t> serial{1};
    unsigned next{};
public:
    std::uint64_t observe(void* resource) noexcept {
        if(!resource)return 0;
        for(auto& w:watches)if(w.resource.load(std::memory_order_acquire)==resource)
            return w.revision.load(std::memory_order_acquire);
        auto& w=watches[next++%watches.size()];
        w.resource.store(nullptr,std::memory_order_release);
        w.revision.store(serial.fetch_add(1,std::memory_order_relaxed),std::memory_order_release);
        w.resource.store(resource,std::memory_order_release);
        return w.revision.load(std::memory_order_acquire);
    }
    void written(void* resource) noexcept {
        if(!resource)return;
        for(auto& w:watches)if(w.resource.load(std::memory_order_acquire)==resource)
            w.revision.store(serial.fetch_add(1,std::memory_order_relaxed),std::memory_order_release);
    }
    void reset() noexcept {
        for(auto& w:watches)w.resource.store(nullptr,std::memory_order_release);
        next=0;
    }
};
}
