#pragma once
#include <filesystem>
#include <string>
namespace mp {
struct HostEvidenceResult {
    bool saved{};
    bool nativeEquivalent{};
    std::string reason;
    std::string filename;
};
// Read-only evidence. Never selects a profile or changes native authorization.
HostEvidenceResult SaveHostEvidence(std::filesystem::path const& exe,
    std::filesystem::path const& folder,std::string const& expectedSha) noexcept;
}
