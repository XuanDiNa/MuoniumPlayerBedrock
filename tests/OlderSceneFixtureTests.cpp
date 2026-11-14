#include "../src/CinematicBridge.cpp"
#include <iostream>
#include <stdexcept>
namespace mp {bool CinematicHud(void*) noexcept{return false;}}
int main(){try{
    for(auto p:{&mp::Contract2645,&mp::Contract2651,&mp::Contract2652,&mp::Contract2660}){
        mp::native=p;std::array<std::byte,512> scene{};unsigned char valid=1;void* expected=scene.data()+8;
        auto flag=&valid;std::memcpy(scene.data()+p->sceneValidity,&flag,sizeof flag);
        std::memcpy(scene.data()+p->sceneClient,&expected,sizeof expected);void* actual{};
        if(!mp::Scene(scene.data(),actual)||actual!=expected)throw std::runtime_error("scene must resolve the exact old/new client layout");
        valid=0;if(mp::Scene(scene.data(),actual))throw std::runtime_error("expired scene must refuse");
    }
    std::cout<<"Four production cinematic scene layouts and validity revocation passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
