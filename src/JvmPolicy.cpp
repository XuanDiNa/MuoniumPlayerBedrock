#include "JvmHost.h"
namespace mp {
std::wstring QuoteJavaArg(std::wstring_view value) {
    std::wstring out=L"\"";
    std::size_t slashes=0;
    for(auto ch:value) {
        if(ch==L'\\') { ++slashes; continue; }
        if(ch==L'\"') { out.append(slashes*2+1,L'\\'); out+=ch; }
        else { out.append(slashes,L'\\'); out+=ch; }
        slashes=0;
    }
    out.append(slashes*2,L'\\'); out+=L'\"'; return out;
}
}
