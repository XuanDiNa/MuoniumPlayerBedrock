#include "JvmHost.h"
#include <shellapi.h>
#include <iostream>
#include <vector>
int main() {
    std::vector<std::wstring> cases{L"",L"simple",L"D:\\Music folder\\",L"引號\" & $(music)",L"C:\\a\\\\\"b",L"Local\\session",
        L"-XX:ErrorFile=D:\\播放器 folder\\logs\\jvm-fatal-%p.log"};
    for(auto const& input:cases) {
        auto command=L"java.exe "+mp::QuoteJavaArg(input); int count=0;
        auto args=CommandLineToArgvW(command.c_str(),&count);
        bool ok=args&&count==2&&input==args[1]; if(args)LocalFree(args);
        if(!ok) {std::cerr<<"Java argument roundtrip failed\n";return 1;}
    }
    std::cout<<"Java command-line argument roundtrips passed: "<<cases.size()<<"\n";
}
