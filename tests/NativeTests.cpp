#include "Audio.h"
#include "Player.h"
#include "Runtime.h"
#include <mfapi.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>
void Check(bool v,char const*m){if(!v)throw std::runtime_error(m);}
int main(int argc,char** argv){
    try{
        bool online=argc>1&&std::string(argv[1])=="--online";std::filesystem::path onlineFolder,onlineFile;
        if(online){
            auto playlist=mp::ImportNetEasePlaylist(3778678);Check(!playlist.empty()&&playlist.size()<=256,"public playlist import bounds");Check(!mp::ParseLyrics(mp::NetEaseLyrics(186016)).empty(),"independent lyrics retrieval");std::cout<<"Public playlist parsed "<<playlist.size()<<" tracks; independent lyrics parsed.\n";
            auto tracks=mp::SearchNetEase("晴天");Check(!tracks.empty(),"online search empty");auto resolved=mp::ResolveNetEase(tracks[0].neteaseId);Check(!resolved.url.empty(),"online stream resolution");Check(!mp::ParseLyrics(resolved.lyrics).empty(),"online lyrics");onlineFolder=std::filesystem::temp_directory_path()/("mp-online-test-"+std::to_string(GetCurrentProcessId())+"-"+std::to_string(GetTickCount64()));onlineFile=mp::DownloadMedia(resolved.url,onlineFolder,1);std::cout<<"Online search parsed "<<tracks.size()<<" tracks; stream, timed lyrics and cache retrieved. No audio played.\n";
        }
        Check(SUCCEEDED(CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED)),"COM");Check(SUCCEEDED(MFStartup(MF_VERSION)),"MFStartup");
        auto path=std::filesystem::temp_directory_path()/("mp-wav-test-"+std::to_string(GetCurrentProcessId())+".wav");
        std::vector<unsigned char> wav(44+44100*2,0);auto put=[&](std::size_t at,unsigned v,unsigned bytes){for(unsigned i=0;i<bytes;++i)wav[at+i]=static_cast<unsigned char>(v>>(8*i));};
        std::memcpy(wav.data(),"RIFF",4);put(4,static_cast<unsigned>(wav.size()-8),4);std::memcpy(wav.data()+8,"WAVEfmt ",8);put(16,16,4);put(20,1,2);put(22,1,2);put(24,44100,4);put(28,88200,4);put(32,2,2);put(34,16,2);std::memcpy(wav.data()+36,"data",4);put(40,88200,4);
        {std::ofstream f(path,std::ios::binary);f.write(reinterpret_cast<char*>(wav.data()),wav.size());}
        auto owner=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE,L"STATIC",L"MP owner discovery fixture",WS_POPUP|WS_VISIBLE,-10000,-10000,640,480,nullptr,nullptr,GetModuleHandleW(nullptr),nullptr);Check(owner&&mp::FindOwnerWindow(GetCurrentProcessId())==owner,"visible exact-process owner discovery");Check(!mp::FindOwnerWindow(0xfffffffe),"unrelated process cannot own overlay");DestroyWindow(owner);
        HWND window=CreateWindowExW(0,L"STATIC",L"MP native test",0,0,0,1,1,HWND_MESSAGE,nullptr,GetModuleHandleW(nullptr),nullptr);Check(window!=nullptr,"message window");
        {mp::Audio audio(window);Check(SUCCEEDED(audio.Load((online?onlineFile:path).wstring(),100,false)),"async load");auto end=GetTickCount64()+10000;
            while(!audio.Loaded()&&GetTickCount64()<end){MSG msg;while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){if(msg.message==mp::AudioMessage){std::unique_ptr<mp::AudioNotice> notice(reinterpret_cast<mp::AudioNotice*>(msg.lParam));audio.Handle(*notice);}else DispatchMessageW(&msg);}Sleep(10);}
            Check(audio.Loaded(),"audio loaded");Check(online?audio.Duration()>0:(audio.Duration()>=990&&audio.Duration()<=1010),"audio duration");Check(!audio.Playing(),"no autoplay");
            mp::AudioNotice stale;stale.generation=99;stale.type=MFP_EVENT_TYPE_PLAY;Check(!audio.Handle(stale)&&!audio.Playing(),"stale callback ignored");audio.Volume(0);audio.Seek(500);audio.Stop();Check(!audio.Loaded(),"stop releases item");
        }
        MSG msg;while(PeekMessageW(&msg,window,mp::AudioMessage,mp::AudioMessage,PM_REMOVE))delete reinterpret_cast<mp::AudioNotice*>(msg.lParam);
        DestroyWindow(window);std::filesystem::remove(path);if(online){std::filesystem::remove(onlineFile);std::filesystem::remove(onlineFolder);}MFShutdown();CoUninitialize();std::cout<<"Native audio metadata, no autoplay, stale-event and stop tests passed\n";return 0;
    }catch(std::exception const&e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}
}
