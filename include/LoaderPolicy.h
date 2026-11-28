#pragma once
#include "NativeProfile.h"
#include <atomic>
namespace mp {
enum class LoaderKind {Preload,LeviLamina};
inline bool AdmitLoader(HostIdentity const* identity,LoaderKind kind,bool leviLoaded) noexcept{
    bool known=false;for(auto const& p:HostIdentities)known|=identity==&p;
    if(!known)return false;
    bool leviImage=identity==&HostIdentities[4]||identity==&HostIdentities[5]||identity==&HostIdentities[6]||identity==&HostIdentities[19]||identity==&HostIdentities[20];
    return kind==LoaderKind::LeviLamina?leviLoaded&&leviImage:!leviLoaded&&!leviImage;
}
class StartGate {
    std::atomic<bool> running{};
public:
    bool begin() noexcept{bool idle=false;return running.compare_exchange_strong(idle,true);}
    void finish() noexcept{running=false;}
};
}
