#include "Runtime.h"
#include "Player.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
void Need(bool value,char const* reason){if(!value)throw std::runtime_error(reason);}
std::string Read(std::filesystem::path const& p){std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
int main(){try{
    auto folder=std::filesystem::temp_directory_path()/(L"MuoniumConfigFixture-"+std::to_wstring(GetCurrentProcessId()));
    Need(std::filesystem::create_directory(folder),"owned empty fixture directory");auto path=folder/L"MuoniumPlayerBedrock.json";
    Need(mp::EnsurePlayerConfig(folder),"missing settings must be created without needing Java or a shortcut");
    auto c=mp::ParseConfig(Read(path));Need(c.enabled&&c.openKey=="NONE"&&c.speakerKey=="NONE","native defaults remain enabled and shortcuts unbound");
    {std::ofstream f(path,std::ios::binary);f<<"{\"enabled\":false,\"openKey\":\"CTRL+F6\",\"foreign\":123}";}
    auto original=Read(path);Need(mp::EnsurePlayerConfig(folder)&&Read(path)==original,"existing user settings must remain byte-identical");
    {std::ofstream f(path,std::ios::binary);f<<"{invalid settings";}
    original=Read(path);Need(mp::EnsurePlayerConfig(folder)&&Read(path)==original,"invalid existing settings must never be replaced with defaults");
    std::filesystem::remove(path);std::filesystem::create_directory(path);
    Need(!mp::EnsurePlayerConfig(folder),"directory at config path must refuse");std::filesystem::remove(path);std::filesystem::remove(folder);
    Need(!mp::EnsurePlayerConfig(folder/L"missing-parent"),"missing parent must not be created");
    std::cout<<"Missing-only config creation, native defaults, user/invalid byte preservation and unsafe path refusal passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
