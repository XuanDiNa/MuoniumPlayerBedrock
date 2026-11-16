#include "Local3SettingsRoute.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
namespace fs=std::filesystem;
void Need(bool value,char const* why){if(!value)throw std::runtime_error(why);}
std::string Read(fs::path const& p){std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
int main(int argc,char** argv){try{
    Need(argc==2,"project root required");auto source=fs::path(argv[1])/"modmenu.settings.json";
    auto folder=fs::temp_directory_path()/(L"MuoniumLocal3Route-"+std::to_wstring(GetCurrentProcessId()));
    Need(fs::create_directory(folder),"disposable fixture");fs::create_directory(folder/"interface");
    fs::copy_file(source,folder/"interface/modmenu.settings.json");fs::copy_file(source,folder/"modmenu.settings.json");
    {std::ofstream f(folder/"MuoniumPlayerBedrock.json");f<<"{\"volume\":37,\"openKey\":\"CTRL+F6\",\"custom\":{\"keep\":true}}";}
    auto config=Read(folder/"MuoniumPlayerBedrock.json");mp::Local3SettingsRoute route(folder);
    Need(route.HideDescription(),"known description routes to owned Configure");
    Need(!fs::exists(folder/"modmenu.settings.json"),"no passive root interception");
    Need(route.RestoreDescription(),"ordinary settings restores exact local3 sidecar");
    Need(Read(folder/"modmenu.settings.json")==Read(source),"ordinary settings definitions stay exact");
    Need(!route.RestoreDescription(),"existing root never overwritten");
    route.Begin();Need(route.KeepEditing(false),"asynchronous menu open grace");Need(route.KeepEditing(true),"keep descriptor while editing");
    Need(!route.KeepEditing(false),"menu close restores direct route");Need(route.HideDescription(),"repeated routing");
    {std::ofstream f(folder/"modmenu.settings.json");f<<"{\"customDescriptor\":true}";}
    Need(!route.HideDescription(),"custom descriptor preserved");Need(Read(folder/"modmenu.settings.json")=="{\"customDescriptor\":true}","unknown bytes unchanged");
    fs::remove(folder/"modmenu.settings.json");Need(route.RestoreDescription(),"restore after custom fixture");
    Need(SetFileAttributesW((folder/"modmenu.settings.json").c_str(),FILE_ATTRIBUTE_READONLY)!=0,"readonly fixture");
    Need(!route.HideDescription(),"readonly descriptor preserved");
    SetFileAttributesW((folder/"modmenu.settings.json").c_str(),FILE_ATTRIBUTE_NORMAL);
    fs::create_hard_link(folder/"modmenu.settings.json",folder/"linked.json");Need(!route.HideDescription(),"multiply linked descriptor preserved");
    fs::remove(folder/"linked.json");Need(route.HideDescription(),"ordinary file eligible again");
    Need(Read(folder/"MuoniumPlayerBedrock.json")==config,"settings values never changed by routing");
    unsigned backups{};for(auto const& entry:fs::directory_iterator(folder/"interface"))if(entry.path().filename().wstring().starts_with(L"legacy-")){++backups;Need(Read(entry.path())==Read(source),"exact recoverable descriptor backup");}
    Need(backups==1,"repeated entry routing deduplicates descriptor backup");fs::remove_all(folder);
    std::cout<<"Local3 description routing, settings round trip, custom/readonly/hardlink preservation and exact backup passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
