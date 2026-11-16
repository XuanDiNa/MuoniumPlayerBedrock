#pragma once
#include "Runtime.h"
#include <optional>
namespace mp {
// local3's existing Apply/Cancel transaction carries a one-shot screen request.
// It is consumed by the integration worker, never by the render callback.
class PassiveScreenRequest {
    struct Stamp {
        DWORD volume{},high{},low{},sizeHigh{},sizeLow{};FILETIME write{};
        bool operator==(Stamp const& other)const {
            return volume==other.volume&&high==other.high&&low==other.low&&sizeHigh==other.sizeHigh&&sizeLow==other.sizeLow
                &&write.dwHighDateTime==other.write.dwHighDateTime&&write.dwLowDateTime==other.write.dwLowDateTime;
        }
    };
    std::filesystem::path folder_;
    std::optional<Stamp> previous_;
    bool primed_{};
    std::optional<Stamp> Current()const;
public:
    explicit PassiveScreenRequest(std::filesystem::path folder):folder_(std::move(folder)){}
    unsigned Poll(bool enabled)noexcept;
};
}
