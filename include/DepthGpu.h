#pragma once
#include "WorldBridge.h"
#include <d3d11_1.h>
#include <memory>
#include <vector>
namespace mp {
inline constexpr unsigned DepthMaxPixels=1024*1024;
inline constexpr unsigned DepthDrawSnapshots=16;
struct DepthRequest {
    std::uint32_t magic{0x5153504d},version{2};
    std::uint64_t at{},epoch{};
    std::int32_t dimension{};
    std::uint32_t active{},viewportWidth{},viewportHeight{},x{},y{},width{},height{};
};
static_assert(sizeof(DepthRequest)==56);
struct DepthResult {
    WorldPacket camera;
    std::uint32_t viewportWidth{},viewportHeight{},x{},y{},width{},height{},sampleStep{1};
    std::uint64_t at{};
    std::vector<std::uint8_t> rgba;
};
bool ValidDepthRequest(DepthRequest const&,std::uint64_t now) noexcept;
unsigned DepthSampleStep(unsigned width,unsigned height) noexcept;
bool DepthCameraMatches(float const* constants,WorldPacket const&) noexcept;
bool DepthCameraMatches(float const* constants,WorldPacket const&,float const* pairedRenderView) noexcept;
// Public graphics interfaces only; no private game object layout here.
class DepthGpu {
    struct State;std::unique_ptr<State> s;
public:
    struct Diagnostics {
        std::uint64_t completed{},cameraMiss{},stale{},jitterInvalid{},older{},queryPendingPolls{},mapBusy{},published{},pairedCameras{},unpairedCameras{},alternativeBindingMatches{},finalBindingMatches{},snapshotCopies{},snapshotOverflow{},intermediateMatches{};
        std::uint32_t cameraSamples{};
        float projectionError{},basisError{},translationError{},clipW{};
    };
    DepthGpu();~DepthGpu();
    void camera(WorldPacket const&,float const* pairedRenderView);
    // Zero revision means unknown writer history: snapshot this observation.
    bool observe(ID3D11DeviceContext*,ID3D11DepthStencilView*,ID3D11Buffer*,UINT firstConstant,std::uint64_t revision=0);
    bool beforeClear(ID3D11DeviceContext*,ID3D11DepthStencilView*,DepthRequest const&,std::uint64_t now);
    bool poll(ID3D11DeviceContext*,DepthResult&,std::uint64_t now);
    Diagnostics diagnostics() const noexcept;
    void reset();
};
}
