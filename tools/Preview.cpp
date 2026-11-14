#include "Runtime.h"
#include <iostream>
int wmain(int argc,wchar_t** argv){
    auto folder=std::filesystem::current_path();
    if(argc>2&&std::wstring_view(argv[1])==L"--host"){bool admitted=mp::VerifyHost(argv[2]);std::cout<<(admitted?"Exact host admitted":"Host refused")<<"\n";return admitted?0:1;}
    return mp::RunPlayer(GetModuleHandleW(nullptr),nullptr,folder,true,argc>1&&std::wstring_view(argv[1])==L"--hidden-smoke");
}
