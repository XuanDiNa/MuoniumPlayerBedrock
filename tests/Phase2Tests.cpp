#include "Player.h"
#include "Client.h"
#include "Library.h"
#include "Lyrics3D.h"
#include <iostream>
#include <stdexcept>
#include <fstream>
#include <chrono>
#include <set>
using namespace mp;
int count{};
void Need(bool b,char const* message){++count;if(!b)throw std::runtime_error(message);}
template<class F>void Reject(F f){bool b=false;try{f();}catch(...){b=true;}Need(b,"invalid input accepted");}
int main(){try{
    Need(IsNetEaseSource("cloudmusic.exe"),"observed desktop identity");
    Need(!IsNetEaseSource("com.bilibili.bilibiliPC")&&!IsNetEaseSource("fakecloudmusic.exe")&&!IsNetEaseSource("cloudmusic.exe.evil"),"unrelated session excluded");
    ClientSnapshot s;s.connected=true;s.generation=7;s.canPlay=true;
    Need(ClientAllows(s,{ClientAction::Play,7}),"allowed command");
    Need(!ClientAllows(s,{ClientAction::Next,7})&&!ClientAllows(s,{ClientAction::Play,6}),"capability and stale generation");
    s.canSeek=true;s.durationMs=1000;Need(ClientAllows(s,{ClientAction::Seek,7,500})&&!ClientAllows(s,{ClientAction::Seek,7,-1})&&!ClientAllows(s,{ClientAction::Seek,7,1001}),"seek bounds");s.durationMs=0;Need(!ClientAllows(s,{ClientAction::Seek,7,0}),"zero duration seek refused");
    s.canPause=s.canNext=s.canPrevious=s.canStop=true;for(auto action:{ClientAction::Pause,ClientAction::Next,ClientAction::Previous,ClientAction::Stop})Need(ClientAllows(s,{action,7})&&!ClientAllows(s,{action,6}),"transport generation guards");
    s.connected=false;Need(!ClientAllows(s,{ClientAction::Play,7}),"disconnected command");
    Need(ParsePlaylistId("12345")==12345&&ParsePlaylistId("https://music.163.com/#/playlist?id=12345")==12345,"playlist ID and URL");
    Reject([]{ParsePlaylistId("https://evil.test/playlist?id=12345");});Reject([]{ParsePlaylistId("0");});
    Reject([]{ParsePlaylistId("https://music.163.com/song?id=12345");});
    Library l;l.queue.Add({"a","A","Artist",{},123});l.queue.Add({"b","B","Local",L"C:\\music\\B.mp3",0});l.queue.Select(1);l.shuffle=true;
    Need(l.ToggleFavorite(l.queue.Items()[0])&&l.IsFavorite("a"),"add favorite");
    auto loaded=ParseLibrary(SerializeLibrary(l));Need(loaded.queue.Items().size()==2&&loaded.queue.Index()==1&&loaded.shuffle&&loaded.favorites.size()==1,"library roundtrip");
    Need(loaded.queue.Current()->local==l.queue.Current()->local,"path roundtrip");
    Need(!loaded.ToggleFavorite(loaded.favorites[0])&&!loaded.IsFavorite("a"),"remove favorite with aliased entry");
    Need(loaded.queue.Remove(0)&&loaded.queue.Index()==0&&loaded.queue.Current()->key=="b","remove shifts selection");
    Need(loaded.queue.Remove(0)&&!loaded.queue.Current(),"remove selected last entry");
    Need(ReplacePlaylist(loaded,3778678,{{"netease-1","one","a",{},1},{"netease-2","two","b",{},2}})&&loaded.playlistId==3778678&&loaded.playlist.size()==2,"playlist replace");
    auto playlistText=SerializeLibrary(loaded);auto playlistRound=ParseLibrary(playlistText);Need(playlistRound.playlistId==3778678&&playlistRound.playlist.size()==2,"playlist persistence");
    Need(ReplacePlaylist(loaded,3778678,{{"netease-3","three","c",{},3}})&&loaded.queue.Items().size()==0&&loaded.playlist.size()==1,"playlist refresh preserves queue boundary");
    Reject([]{ParseLibrary("{bad");});Reject([]{ParseLibrary(R"({"version":2})");});
    Reject([]{ParseLibrary(R"({"version":1,"version":1,"queue":[],"favorites":[]})");});
    Reject([]{ParseLibrary(R"({"version":1,"queue":[{"key":"a","title":"bad","neteaseId":-1}],"favorites":[]})");});
    for(int i=0;i<40;++i)Need(ShuffleIndex(3,1,i)!=1,"shuffle immediate repeat");
    Need(!ShuffleIndex(0,{},3)&&ShuffleIndex(1,0,3)==0,"shuffle bounds");
    ShuffleSequence sequence;sequence.Reset(7,2,42);std::set<std::size_t> visited{2};for(int n=0;n<6;++n){auto i=sequence.Advance(1,false,55);Need(i&&visited.insert(*i).second,"shuffled pass visits each track once");}Need(!sequence.Advance(1,false,55),"shuffle stops without repeat");auto last=sequence.Current();auto next=sequence.Advance(1,true,68);Need(next&&next!=last,"repeat shuffle does not repeat boundary");
    sequence.Reset(3,1,0);auto second=sequence.Advance(1,false,4);Need(second&&sequence.Advance(-1,false,4)==1,"shuffle previous follows history");
    auto c=ParseConfig(R"({"lyricsTimeOffsetMs":-1200,"shuffle":true})");Need(c.lyricsTimeOffsetMs==-1200&&c.shuffle,"new settings");
    Reject([]{ParseConfig(R"({"lyricsTimeOffsetMs":60001})");});
    auto center=Lyric3DTransformFor(0,0.5f,4,35);auto near=Lyric3DTransformFor(-1,0.5f,4,35);auto far=Lyric3DTransformFor(3,0.5f,4,35);Need(center.scale>near.scale&&near.scale>far.scale&&center.alpha>far.alpha&&center.rotation==0,"3d perspective transform");Need(Lyric3DTransformFor(0,-2,4,35).alpha>=0&&Lyric3DTransformFor(0,2,4,35).alpha>=0,"3d progress bounds");
    auto root=std::filesystem::temp_directory_path()/("mp-library-test-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));std::filesystem::create_directory(root);auto path=root/"library.json";
    Need(ReadLibrary(path).queue.Items().empty(),"missing library is empty");WriteLibrary(path,l);Need(ReadLibrary(path).favorites.size()==1,"atomic library roundtrip");
    {std::ofstream f(path,std::ios::trunc);f<<"{broken";}Reject([&]{WriteLibrary(path,l);});{std::ifstream f(path);std::string text((std::istreambuf_iterator<char>(f)),{});Need(text=="{broken","malformed library preserved");}
    {std::ofstream f(path,std::ios::trunc);}Reject([&]{WriteLibrary(path,l);});Need(std::filesystem::file_size(path)==0,"empty invalid library preserved");std::filesystem::remove(path);std::filesystem::remove(root);
    std::cout<<count<<" phase2 checks passed\n";return 0;
}catch(std::exception const&e){std::cerr<<"FAIL "<<count<<": "<<e.what()<<"\n";return 1;}}
