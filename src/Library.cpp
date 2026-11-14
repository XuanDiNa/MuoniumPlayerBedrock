#include "Library.h"
#include "json.hpp"
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <algorithm>
#include <charconv>
#include <fstream>
#include <set>
#include <stdexcept>
#include <numeric>
#include <random>
namespace mp {
using J=nlohmann::json;
bool Library::IsFavorite(std::string_view key)const{return std::any_of(favorites.begin(),favorites.end(),[&](auto const&t){return t.key==key;});}
bool Library::ToggleFavorite(Track const&t){auto key=t.key;auto i=std::find_if(favorites.begin(),favorites.end(),[&](auto const&v){return v.key==key;});if(i!=favorites.end()){favorites.erase(i);return false;}if(favorites.size()>=256)throw std::runtime_error("Favorites limit: 256 tracks");favorites.push_back(t);return true;}
bool ReplacePlaylist(Library& l,std::int64_t id,std::vector<Track> tracks){if(id<=0||tracks.size()>256)return false;std::set<std::string> keys;for(auto const&t:tracks){if(t.neteaseId<=0||t.key.empty()||!keys.insert(t.key).second)return false;}l.playlistId=id;l.playlist=std::move(tracks);return true;}
std::optional<std::size_t> ShuffleIndex(std::size_t n,std::optional<std::size_t> current,std::uint64_t r){if(!n)return {};if(n==1)return 0;if(!current||*current>=n)return r%n;auto i=static_cast<std::size_t>(r%(n-1));return i>=*current?i+1:i;}
void ShuffleSequence::Reset(std::size_t n,std::optional<std::size_t> current,std::uint64_t seed){order_.resize(n);std::iota(order_.begin(),order_.end(),0);std::mt19937_64 random(seed);std::shuffle(order_.begin(),order_.end(),random);cursor_.reset();if(current&&*current<n){auto i=std::find(order_.begin(),order_.end(),*current);std::iter_swap(order_.begin(),i);cursor_=0;}}
std::optional<std::size_t> ShuffleSequence::Advance(int direction,bool wrap,std::uint64_t seed){
    if(order_.empty())return {};if(!cursor_){cursor_=direction>=0?0:order_.size()-1;return Current();}
    if(direction<0){if(*cursor_>0)--*cursor_;else if(wrap)cursor_=order_.size()-1;else return {};return Current();}
    if(*cursor_+1<order_.size()){++*cursor_;return Current();}if(!wrap)return {};
    auto previous=Current();std::mt19937_64 random(seed);std::shuffle(order_.begin(),order_.end(),random);if(order_.size()>1&&order_[0]==previous){auto i=std::find(order_.begin(),order_.end(),*ShuffleIndex(order_.size(),previous,random()));std::iter_swap(order_.begin(),i);}cursor_=0;return Current();
}
namespace {
J Entry(Track const&t){return {{"key",t.key},{"title",t.title},{"artist",t.artist},{"local",Utf8(t.local.wstring())},{"neteaseId",t.neteaseId}};}
Track Entry(J const&j){
    if(!j.is_object())throw std::runtime_error("Invalid library track");Track t;
    t.key=j.at("key").get<std::string>();t.title=j.at("title").get<std::string>();t.artist=j.value("artist",std::string{});auto p=j.value("local",std::string{});t.neteaseId=j.value("neteaseId",std::int64_t{});
    if(t.key.empty()||t.key.size()>1024||t.title.empty()||t.title.size()>1024||t.artist.size()>1024||p.size()>32768||t.neteaseId<0||(!p.empty()&&t.neteaseId)|| (p.empty()&&!t.neteaseId))throw std::runtime_error("Invalid library track values");
    Wide(t.key);Wide(t.title);Wide(t.artist);t.local=Wide(p);if(!p.empty()&&(!t.local.is_absolute()||p.find('\0')!=p.npos))throw std::runtime_error("Invalid local library path");return t;
}
std::string Read(std::filesystem::path const&p){auto attr=GetFileAttributesW(p.c_str());if(attr==INVALID_FILE_ATTRIBUTES||(attr&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY)))throw std::runtime_error("Library unavailable");auto n=std::filesystem::file_size(p);if(n>4*1024*1024)throw std::runtime_error("Library exceeds 4 MiB");std::ifstream f(p,std::ios::binary);std::string s(static_cast<std::size_t>(n),' ');f.read(s.data(),n);if(!f&&n)throw std::runtime_error("Library read failed");return s;}
}
std::string SerializeLibrary(Library const&l){J j{{"version",1},{"queue",J::array()},{"favorites",J::array()},{"playlist",J::array()},{"playlistId",l.playlistId},{"selected",l.queue.Index()?J(*l.queue.Index()):J(nullptr)},{"shuffle",l.shuffle}};for(auto const&t:l.queue.Items())j["queue"].push_back(Entry(t));for(auto const&t:l.favorites)j["favorites"].push_back(Entry(t));for(auto const&t:l.playlist)j["playlist"].push_back(Entry(t));auto s=j.dump(2)+"\n";ParseLibrary(s);return s;}
Library ParseLibrary(std::string_view raw){
    if(raw.size()>4*1024*1024)throw std::runtime_error("Library exceeds limit");Wide(raw);
    std::vector<std::set<std::string>> keys;auto callback=[&](int depth,J::parse_event_t event,J&value){if(depth>16)throw std::runtime_error("Library nesting exceeds limit");if(event==J::parse_event_t::object_start)keys.emplace_back();if(event==J::parse_event_t::key&&!keys.back().insert(value.get<std::string>()).second)throw std::runtime_error("Duplicate library key");if(event==J::parse_event_t::object_end)keys.pop_back();return true;};
    auto j=J::parse(raw,callback);if(!j.is_object()||j.at("version")!=1)throw std::runtime_error("Unsupported library format");Library l;
    for(auto k:{"queue","favorites"}){auto const&a=j.at(k);if(!a.is_array()||a.size()>256)throw std::runtime_error("Library list exceeds limit");for(auto const&v:a){auto t=Entry(v);if(k==std::string_view("queue"))l.queue.Add(t);else if(!l.IsFavorite(t.key))l.favorites.push_back(t);}}
    if(j.contains("playlistId")){if(!j["playlistId"].is_number_integer())throw std::runtime_error("Invalid playlist ID");l.playlistId=j["playlistId"].get<std::int64_t>();if(l.playlistId<0)throw std::runtime_error("Invalid playlist ID");}
    if(j.contains("playlist")){auto const&a=j["playlist"];if(!a.is_array()||a.size()>256)throw std::runtime_error("Playlist exceeds limit");for(auto const&v:a)l.playlist.push_back(Entry(v));if(l.playlistId<=0&&!l.playlist.empty())throw std::runtime_error("Playlist ID missing");}
    if(j.contains("selected")&&!j["selected"].is_null()){if(!j["selected"].is_number_unsigned()&&!j["selected"].is_number_integer())throw std::runtime_error("Invalid selection");auto n=j["selected"].get<std::int64_t>();if(n<0||!l.queue.Select(static_cast<std::size_t>(n)))throw std::runtime_error("Selection outside queue");}
    l.shuffle=j.value("shuffle",false);return l;
}
Library ReadLibrary(std::filesystem::path const&p){if(!std::filesystem::exists(p))return {};return ParseLibrary(Read(p));}
void WriteLibrary(std::filesystem::path const&p,Library const&l){
    auto exists=std::filesystem::exists(p);auto raw=exists?Read(p):std::string{};if(exists)ParseLibrary(raw);auto text=SerializeLibrary(l);
    auto tmp=p.parent_path()/(L".library-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64())+L".tmp");
    HANDLE h=CreateFileW(tmp.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);if(h==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot save library");DWORD bytes{};bool ok=WriteFile(h,text.data(),static_cast<DWORD>(text.size()),&bytes,nullptr)&&bytes==text.size()&&FlushFileBuffers(h);CloseHandle(h);
    bool same=false;try{same=std::filesystem::exists(p)?Read(p)==raw:raw.empty();}catch(...){}
    if(!ok||!same||!MoveFileExW(tmp.c_str(),p.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)){std::error_code ec;std::filesystem::remove(tmp,ec);throw std::runtime_error("Library changed or save failed");}
}
std::int64_t ParsePlaylistId(std::string_view v){
    if(v.empty()||v.size()>2048)throw std::runtime_error("Enter a public NetEase playlist ID or URL");
    if(v.starts_with("https://music.163.com/")){v.remove_prefix(22);if(v.starts_with("#/"))v.remove_prefix(2);if(!v.starts_with("playlist?"))throw std::runtime_error("Not a playlist URL");auto at=v.find("id=");if(at==v.npos|| (at&&v[at-1]!='?'&&v[at-1]!='&'))throw std::runtime_error("Playlist ID missing");v=v.substr(at+3);v=v.substr(0,v.find_first_of("&#"));}
    if(v.empty()||v.find_first_not_of("0123456789")!=v.npos)throw std::runtime_error("Invalid playlist ID");std::int64_t id{};auto r=std::from_chars(v.data(),v.data()+v.size(),id);if(r.ec!=std::errc{}||id<=0)throw std::runtime_error("Invalid playlist ID");return id;
}
}
