#include "LoaderPolicy.h"
#include <thread>
#include <vector>
#include <stdexcept>
#include <iostream>
void Need(bool b){if(!b)throw std::runtime_error("loader ownership/lifecycle assertion");}
int main(){try{
    for(auto const& p:mp::HostIdentities){
        bool patched=p.sha256=="807508E53ADE36497C30C95770016B9CABBC9DE9E7FA83130D28BA586B3C410F"||
            p.sha256=="FAD0788DB090874B7BADC6353BBBCBD38FAC9172DA1F5D3F30771056E8A7A6A5"||
            p.sha256=="B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53"||
            p.sha256=="409C9597C5C973B3FA9B11204612850F4BAE8F83270C7692DCC783C491FCF04C"||
            p.sha256=="FCBCCAEC4E0EE3AB203146BDF45E41878AED7B42237E6DE2C5053F0730C6E10B";
        Need(mp::AdmitLoader(&p,mp::LoaderKind::Preload,false)==!patched);
        Need(!mp::AdmitLoader(&p,mp::LoaderKind::Preload,true));
        Need(mp::AdmitLoader(&p,mp::LoaderKind::LeviLamina,true)==patched);
        Need(!mp::AdmitLoader(&p,mp::LoaderKind::LeviLamina,false));
    }
    Need(!mp::AdmitLoader(nullptr,mp::LoaderKind::LeviLamina,true));
    auto reinstalled=mp::MatchHostIdentity("FAD0788DB090874B7BADC6353BBBCBD38FAC9172DA1F5D3F30771056E8A7A6A5",323855872);
    Need(reinstalled&&mp::AdmitLoader(reinstalled,mp::LoaderKind::LeviLamina,true));
    Need(!mp::AdmitLoader(reinstalled,mp::LoaderKind::Preload,false));
    auto player=mp::MatchHostIdentity("B554A2DA8B2271A4A164AE544A4A447F3A630B5FC2261AFEFE47C7C206826A53",323855872);
    Need(player&&mp::AdmitLoader(player,mp::LoaderKind::LeviLamina,true));
    Need(!mp::AdmitLoader(player,mp::LoaderKind::Preload,false));
    Need(!mp::AdmitLoader(player,mp::LoaderKind::Preload,true));
    Need(!mp::AdmitLoader(player,mp::LoaderKind::LeviLamina,false));
    if(player){auto foreign=*player;Need(!mp::AdmitLoader(&foreign,mp::LoaderKind::LeviLamina,true));}
    mp::StartGate gate;std::atomic<int> starts{};std::vector<std::thread> workers;
    for(int i=0;i<16;i++)workers.emplace_back([&]{if(gate.begin())++starts;});
    for(auto& t:workers)t.join();Need(starts==1);gate.finish();Need(gate.begin());Need(!gate.begin());gate.finish();
    std::cout<<"Wrong-loader refusal, exact LL admission and concurrent one-start/restart passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
