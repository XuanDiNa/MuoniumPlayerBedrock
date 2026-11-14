#include "Client.h"
#include <chrono>
#include <thread>
#include <iostream>
int main(){
    mp::Client c;if(c.Snapshot().connected||c.Send({mp::ClientAction::Play,0})){std::cerr<<"Unexpected initial activity\n";return 1;}
    c.Connect();mp::ClientSnapshot s;for(int i=0;i<24;++i){std::this_thread::sleep_for(std::chrono::milliseconds(250));s=c.Snapshot();if(s.connected)break;}
    std::cout<<"READ_ONLY connected="<<s.connected<<" metadata="<<!s.title.empty()<<" play="<<s.canPlay<<" pause="<<s.canPause<<" next="<<s.canNext<<" seek="<<s.canSeek<<" cover="<<bool(s.cover)<<" duration_ms="<<s.durationMs<<"\n";
    if(s.connected){auto first=s.generation;std::this_thread::sleep_for(std::chrono::milliseconds(1700));auto refreshed=c.Snapshot();if(!refreshed.connected||refreshed.generation!=first){std::cerr<<"Session identity changed during read-only stability check\n";return 3;}std::cout<<"Session generation remained stable across refreshes\n";}
    c.Disconnect();if(c.Snapshot().connected||c.Send({mp::ClientAction::Next,s.generation})){std::cerr<<"Disconnect command guard failed\n";return 1;}
    std::cout<<"No transport commands sent; disconnect guard passed\n";return s.connected?0:2;
}
