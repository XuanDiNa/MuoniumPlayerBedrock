#include "ModMenu/Settings.h"
#include "DefaultPlayerConfig.h"
#include "json.hpp"
#include <Windows.h>
#include <fstream>
#include <iostream>
#include <stdexcept>
int main(int argc,char** argv){try{
    if(argc!=2)throw std::runtime_error("Project root required");namespace fs=std::filesystem;
    auto root=fs::temp_directory_path()/(L"MuoniumScaleSettings-"+std::to_wstring(GetCurrentProcessId()));
    auto folder=root/"MuoniumPlayerBedrock";fs::create_directories(folder);
    for(auto file:{"manifest.json","modmenu.settings.json"})fs::copy_file(fs::path(argv[1])/file,folder/file);
    auto config=nlohmann::json::parse(mp::DefaultPlayerConfig);config["stageScalePercent"]=117;config["customKeep"]=true;
    {std::ofstream out(folder/"MuoniumPlayerBedrock.json");out<<config.dump(2);}
    auto draft=menu::LoadSettings(root,"MuoniumPlayerBedrock");
    if(!draft.valid)throw std::runtime_error(draft.message);
    auto saved=menu::ApplySettings(root,draft);if(!saved.saved)throw std::runtime_error(saved.message);
    std::ifstream input(folder/"MuoniumPlayerBedrock.json");nlohmann::json after;input>>after;
    if(after.at("stageScalePercent")!=117||after.at("customKeep")!=true)throw std::runtime_error("HUD scale/custom values changed");
    // The disposable fixture is the only cleanup target.
    input.close();fs::remove(folder/"MuoniumPlayerBedrock.json");fs::remove(folder/"manifest.json");fs::remove(folder/"modmenu.settings.json");
    for(auto const& e:fs::directory_iterator(folder))fs::remove(e.path());fs::remove(folder);fs::remove(root);
    std::cout<<"Real ModMenu parser loaded and saved HUD scale 117 without changing user values passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
