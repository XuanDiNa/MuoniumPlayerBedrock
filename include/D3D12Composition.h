#pragma once
#include "Composition.h"
#include <d3d12.h>
#include <dxgi1_4.h>
#include <memory>
namespace mp {
enum class D3D12Draw { Failed, Busy, Empty, Probe, Drawn };
struct D3D12TransferStats {std::uint64_t allocations{},uploadBytes{},draws{};};
class D3D12Composition {
    struct State;std::unique_ptr<State> state;
public:
    D3D12Composition();~D3D12Composition();
    D3D12Draw present(IDXGISwapChain3*,ID3D12CommandQueue*,CompositionFrame const&,std::uint64_t now);
    bool reset();
    D3D12TransferStats transferStats() const noexcept;
};
}
