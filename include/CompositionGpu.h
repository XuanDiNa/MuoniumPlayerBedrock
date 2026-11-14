#pragma once
#include "Composition.h"
#include <d3d11_1.h>
#include <memory>
namespace mp {
struct CompositionGpuStatus {UINT deviceFlags{},featureLevel{};HRESULT result{S_OK};char const* stage{"idle"};bool ready{};};
class CompositionGpu {
    struct State;std::unique_ptr<State> s;
public:
    CompositionGpu();~CompositionGpu();
    bool upload(ID3D11DeviceContext*,CompositionFrame const&);
    bool capture(ID3D11DeviceContext*,ID3D11DepthStencilView*,ID3D11Buffer*,UINT first,WorldPacket const&,float const* pairedView);
    bool captureVerified(ID3D11DeviceContext*,ID3D11DepthStencilView*,WorldPacket const&,float const* pairedView);
    bool present(ID3D11DeviceContext*,ID3D11RenderTargetView*,UINT width,UINT height,std::uint64_t now);
    void reset();
    CompositionGpuStatus status() const noexcept;
};
}
