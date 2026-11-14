// Owned test DLL only. Never shipped or installed beside the game.
#include <Windows.h>
static unsigned calls;
extern "C" __declspec(dllexport) HRESULT WINAPI D3D12CreateDevice(void*,int,GUID const&,void**){++calls;return E_FAIL;}
extern "C" __declspec(dllexport) HRESULT WINAPI D3D12SerializeRootSignature(void const*,int,void**,void**){++calls;return E_FAIL;}
extern "C" __declspec(dllexport) unsigned WINAPI FixtureCalls(){return calls;}
