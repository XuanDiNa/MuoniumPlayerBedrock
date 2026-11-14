#include "Jobs.h"
#include <memory>
namespace {std::unique_ptr<mp::Jobs> jobs;}
extern "C" __declspec(dllexport) void FixtureStart(HWND window,wchar_t const* folder){jobs=std::make_unique<mp::Jobs>(window,folder);}
extern "C" __declspec(dllexport) void FixtureEnabled(bool value){jobs->Enabled(value);}
extern "C" __declspec(dllexport) void FixtureStop(){jobs.reset();}
