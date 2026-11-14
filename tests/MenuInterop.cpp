#include "Player.h"
#include "Jobs.h"
#include "Runtime.h"
#include "ModMenu/Settings.h"
#include "ModMenu/Workspace.h"
#include <fstream>
#include <iostream>
#include <stdexcept>
std::string Read(std::filesystem::path p){std::ifstream f(p);return {std::istreambuf_iterator<char>(f),{}};}
void Need(bool v){if(!v)throw std::runtime_error("Interop assertion failed");}
int main(int argc,char**argv){try{
    Need(argc==2);auto folder=std::filesystem::path(argv[1]);
    auto manifest=nlohmann::json::parse(Read(folder/"manifest.json"));
    auto version=manifest.at("version").get<std::string>();
    auto raw=Read(folder/"modmenu.settings.json");auto def=menu::ParseSettingsDescription(raw,"MuoniumPlayerBedrock","MuoniumPlayerBedrock.dll",version);Need(def.fields.size()==44&&def.hotReload);
    Need(def.version==5);unsigned bindings{};
    for(auto const& field:def.fields)if(field.type==menu::SettingType::Binding){
        ++bindings;Need(field.bindingModifiers==modmenu::binding::AllModifiers);
    }
    Need(bindings==8);
    auto config=mp::ParseConfig(Read(folder/"MuoniumPlayerBedrock.json"));Need(config.openKey=="NONE"&&config.playPauseKey=="NONE"&&config.nextKey=="NONE"&&config.previousKey=="NONE"&&config.speakerKey=="NONE"&&!config.speakerEnabled);
    auto workspace=menu::ParseWorkspace(mp::MenuDeclaration(),"MuoniumPlayerBedrock","MuoniumPlayerBedrock.dll",version);auto state=menu::ParseWorkspaceState(mp::MenuSnapshot(true,1),workspace);Need(state["controls"]["open"]["enabled"]);
    menu::WorkspaceBroker broker;auto session=broker.Register(workspace);Need(session&&broker.Publish(session,mp::MenuSnapshot(true,1)));std::string error;Need(broker.Submit(session,"player","open",1,nlohmann::json::object(),{},error));char command[32768];std::uint32_t needed{};Need(broker.Poll(session,command,sizeof command,needed)&&needed>0);auto cmd=nlohmann::json::parse(command);Need(cmd["action"]=="open");Need(broker.Publish(session,mp::MenuSnapshot(true,2,cmd["requestId"].get<std::uint64_t>(),true)));Need(broker.Snapshots()[0].pending.empty());
    std::uint64_t revision=2;
    for(auto action:{"hudEditor","presets","miniPlayer","islandSearch"}){
        Need(broker.Submit(session,"player",action,revision,nlohmann::json::object(),{},error));
        Need(broker.Poll(session,command,sizeof command,needed));cmd=nlohmann::json::parse(command);Need(cmd["action"]==action);
        Need(broker.Publish(session,mp::MenuSnapshot(true,++revision,cmd["requestId"].get<std::uint64_t>(),true)));
        Need(broker.Snapshots()[0].pending.empty());
    }
    Need(broker.Publish(session,mp::MenuSnapshot(false,++revision)));Need(!broker.Submit(session,"player","open",revision,nlohmann::json::object(),{},error));Need(broker.Unregister(session));
    auto fixture=std::filesystem::temp_directory_path()/(L"MuoniumMenuSettings-"+std::to_wstring(GetCurrentProcessId()));
    auto plugin=fixture/"MuoniumPlayerBedrock";Need(std::filesystem::create_directories(plugin));
    for(auto name:{"manifest.json","modmenu.settings.json"})std::filesystem::copy_file(folder/name,plugin/name);
    Need(mp::EnsurePlayerConfig(plugin));auto configPath=plugin/"MuoniumPlayerBedrock.json";auto original=Read(configPath);
    auto draft=menu::LoadSettings(fixture,"MuoniumPlayerBedrock");Need(draft.valid&&draft.hotReload&&draft.fields.size()==44);
    for(auto& field:draft.fields)if(field.path=="/hudEnabled")field.value=false;
    Need(Read(configPath)==original); // Unapplied changes / Cancel must not write.
    auto binding=modmenu::binding::Parse("CTRL+F6");Need(binding.has_value());
    for(auto& field:draft.fields)if(field.path=="/openKey")Need(menu::SetBinding(field,*binding));
    auto saved=menu::ApplySettings(fixture,draft);Need(saved.saved);auto applied=mp::ParseConfig(Read(configPath));Need(!applied.hudEnabled&&applied.openKey=="CTRL+F6");
    auto reopened=menu::LoadSettings(fixture,"MuoniumPlayerBedrock");Need(reopened.valid);for(auto& field:reopened.fields)if(field.path=="/openKey")Need(menu::SetBinding(field,{}));
    Need(menu::ApplySettings(fixture,reopened).saved&&mp::ParseConfig(Read(configPath)).openKey=="NONE");
    std::cout<<"Real ModMenu v5 parser, eight chord bindings and current-manifest Workspace register/publish/command/ack/disable interop passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<"\n";return 1;}}
