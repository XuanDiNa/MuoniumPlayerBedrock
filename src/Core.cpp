#include "Player.h"
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include "json.hpp"
#include <algorithm>
#include <charconv>
#include <regex>
#include <set>
#include <stdexcept>
namespace mp {
std::wstring Wide(std::string_view s){
    if(s.empty())return {};
    if(s.size()>2*1024*1024)throw std::runtime_error("Text exceeds limit");
    int n=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),nullptr,0);
    if(!n)throw std::runtime_error("Invalid UTF-8");
    std::wstring out(n,L' ');MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),out.data(),n);return out;
}
std::string Utf8(std::wstring_view s){
    if(s.empty())return {};
    if(s.size()>1024*1024)throw std::runtime_error("Text exceeds limit");
    int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),nullptr,0,nullptr,nullptr);
    if(!n)throw std::runtime_error("Invalid Unicode");
    std::string out(n,' ');WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,s.data(),static_cast<int>(s.size()),out.data(),n,nullptr,nullptr);return out;
}
int KeyCode(std::string_view k){
    if(auto binding=modmenu::binding::ParseInput(k))return static_cast<int>(binding->key);
    throw std::runtime_error("Unsupported key token");
}
Config ParseConfig(std::string_view raw){
    using J=nlohmann::json;
    if(raw.size()>65536)throw std::runtime_error("Settings exceed 64 KiB");
    Wide(raw);std::vector<std::set<std::string>> keys;
    auto cb=[&](int depth,J::parse_event_t event,J& value){
        if(depth>32)throw std::runtime_error("Settings nesting exceeds limit");
        if(event==J::parse_event_t::object_start)keys.emplace_back();
        if(event==J::parse_event_t::key&&!keys.back().insert(value.get<std::string>()).second)throw std::runtime_error("Duplicate settings key");
        if(event==J::parse_event_t::object_end)keys.pop_back();return true;
    };
    auto j=J::parse(raw,cb);if(!j.is_object())throw std::runtime_error("Settings must be an object");Config c;
    auto flag=[&](char const*k,bool& out){if(j.contains(k)){if(!j[k].is_boolean())throw std::runtime_error("Expected Boolean setting");out=j[k].get<bool>();}};
    auto num=[&](char const*k,int& out,int low,int high){if(j.contains(k)){if(!j[k].is_number_integer())throw std::runtime_error("Expected integer setting");auto v=j[k].get<std::int64_t>();if(v<low||v>high)throw std::runtime_error("Setting outside range");out=static_cast<int>(v);}};
    num("lyricsMode",c.lyricsMode,0,2);
    flag("hudEnabled",c.hudEnabled);flag("dynamicIslandEnabled",c.dynamicIslandEnabled);
    flag("musicInfoEnabled",c.musicInfoEnabled);flag("musicLyricsEnabled",c.musicLyricsEnabled);
    flag("spectrumEnabled",c.spectrumEnabled);flag("lyricColorCustom",c.lyricColorCustom);
    num("infoXPercent",c.infoXPercent,0,100);num("infoYPercent",c.infoYPercent,0,100);num("infoScalePercent",c.infoScalePercent,50,200);
    num("desktopLyricsXPercent",c.desktopLyricsXPercent,0,100);num("desktopLyricsYPercent",c.desktopLyricsYPercent,0,100);num("desktopLyricsScalePercent",c.desktopLyricsScalePercent,50,200);
    num("islandOffsetX",c.islandOffsetX,-1000,1000);num("islandOffsetY",c.islandOffsetY,-1000,1000);num("islandScalePercent",c.islandScalePercent,60,135);
    num("spectrumOffsetX",c.spectrumOffsetX,-1000,1000);num("spectrumOffsetY",c.spectrumOffsetY,-1000,1000);num("spectrumScalePercent",c.spectrumScalePercent,25,200);
    num("lyricColorRed",c.lyricColorRed,0,255);num("lyricColorGreen",c.lyricColorGreen,0,255);num("lyricColorBlue",c.lyricColorBlue,0,255);
    flag("enabled",c.enabled);flag("lyricsEnabled",c.lyricsEnabled);flag("shuffle",c.shuffle);num("lyricsTimeOffsetMs",c.lyricsTimeOffsetMs,-60000,60000);num("volume",c.volume,0,100);num("lyricsFontSize",c.lyricsFontSize,14,48);num("lyricsOffsetY",c.lyricsOffsetY,0,500);num("repeat",c.repeat,0,2);
    flag("speakerEnabled",c.speakerEnabled);
    flag("worldLyricsOcclusion",c.worldLyricsOcclusion);
    flag("smtcPublishEnabled",c.smtcPublishEnabled);
    std::set<std::string> bindings;
    for(auto const&[k,out]:std::vector<std::pair<char const*,std::string*>>{{"openKey",&c.openKey},{"playPauseKey",&c.playPauseKey},{"nextKey",&c.nextKey},{"previousKey",&c.previousKey},{"speakerKey",&c.speakerKey},{"miniPlayerKey",&c.miniPlayerKey},{"hudEditorKey",&c.hudEditorKey},{"islandSearchKey",&c.islandSearchKey}}){
        if(j.contains(k)){if(!j[k].is_string())throw std::runtime_error("Expected key token");*out=j[k].get<std::string>();}
        auto binding=modmenu::binding::ParseInput(*out);if(!binding)throw std::runtime_error("Unsupported key token");
        if(binding->key&&!bindings.insert(modmenu::binding::Token(*binding)).second)throw std::runtime_error("Player shortcuts must differ");
    }return c;
}
namespace {
std::int64_t Number(std::string const&s){std::int64_t v{};auto r=std::from_chars(s.data(),s.data()+s.size(),v);if(r.ec!=std::errc{}||r.ptr!=s.data()+s.size())throw std::runtime_error("Invalid lyric time");return v;}
}
std::vector<Line> ParseLyrics(std::string_view input){
    if(input.size()>524288)throw std::runtime_error("Lyrics exceed 512 KiB");Wide(input);
    if(input.starts_with("\xEF\xBB\xBF"))input.remove_prefix(3);
    std::vector<Line> out;std::int64_t offset{};
    static std::regex const lrc(R"(\[(\d{1,3}):([0-5]\d)(?:\.(\d{1,3}))?\])"),yrc(R"(^\[(\d{1,9}),(\d{1,7})\])"),word(R"(\((\d{1,9}),(\d{1,7}),\d{1,7}\))"),off(R"(^\[offset:([+-]?\d{1,7})\]\s*$)");
    while(!input.empty()&&out.size()<4096){
        auto n=input.find('\n');auto sv=input.substr(0,n);input=n==input.npos?std::string_view{}:input.substr(n+1);
        if(sv.size()>16384)continue;std::string s(sv);if(!s.empty()&&s.back()=='\r')s.pop_back();std::smatch m;
        if(std::regex_match(s,m,off)){auto text=m[1].str();if(text.starts_with('+'))text.erase(0,1);offset=Number(text);continue;}
        if(std::regex_search(s,m,yrc)){
            Line line{Number(m[1].str()),Number(m[2].str())};auto body=s.substr(m.length());
            std::vector<std::smatch> matches;for(std::sregex_iterator i(body.begin(),body.end(),word),end;i!=end&&matches.size()<1024;++i)matches.push_back(*i);
            for(std::size_t i=0;i<matches.size();++i){auto const&w=matches[i];auto start=w.position()+w.length();auto stop=i+1<matches.size()?matches[i+1].position():static_cast<std::ptrdiff_t>(body.size());auto text=body.substr(start,stop-start);line.words.push_back({Number(w[1].str()),Number(w[2].str()),text});line.text+=text;}
            if(!line.text.empty())out.push_back(std::move(line));continue;
        }
        std::size_t consumed{};std::vector<std::int64_t> times;
        while(consumed<s.size()&&std::regex_search(s.cbegin()+consumed,s.cend(),m,lrc,std::regex_constants::match_continuous)){
            auto frac=m[3].str();while(frac.size()<3)frac+='0';times.push_back(Number(m[1].str())*60000+Number(m[2].str())*1000+Number(frac));consumed+=m.length();
        }
        if(!times.empty()){auto text=s.substr(consumed);for(auto at:times)if(out.size()<4096)out.push_back({at,0,text,{}});}
    }
    for(auto&l:out){l.at=std::max<std::int64_t>(0,l.at+offset);for(auto&w:l.words)w.at=std::max<std::int64_t>(0,w.at+offset);}
    std::stable_sort(out.begin(),out.end(),[](auto const&a,auto const&b){return a.at<b.at;});
    for(std::size_t i=0;i<out.size();++i)if(!out[i].duration)out[i].duration=i+1<out.size()?out[i+1].at-out[i].at:5000;
    return out;
}
std::optional<std::size_t> CurrentLine(std::vector<Line> const& lines,std::int64_t at){
    auto i=std::upper_bound(lines.begin(),lines.end(),at,[](auto t,Line const&l){return t<l.at;});if(i==lines.begin())return {};return static_cast<std::size_t>(i-lines.begin()-1);
}
bool Queue::Add(Track t){if(items_.size()>=256||t.key.empty())return false;items_.push_back(std::move(t));return true;}
bool Queue::Select(std::size_t i){if(i>=items_.size())return false;current_=i;return true;}
Track const* Queue::Current()const{return current_&&*current_<items_.size()?&items_[*current_]:nullptr;}
std::optional<std::size_t> Queue::Advance(int direction,bool wrap){
    if(items_.empty())return {};if(!current_){current_=direction>=0?0:items_.size()-1;return current_;}
    auto next=static_cast<std::int64_t>(*current_)+(direction>=0?1:-1);
    if(next<0||next>=static_cast<std::int64_t>(items_.size())){if(!wrap)return {};next=next<0?static_cast<std::int64_t>(items_.size()-1):0;}
    current_=static_cast<std::size_t>(next);return current_;
}
void Queue::Clear(){items_.clear();current_.reset();}
bool Queue::Remove(std::size_t i){if(i>=items_.size())return false;items_.erase(items_.begin()+i);if(current_){if(*current_==i)current_.reset();else if(*current_>i)--*current_;}return true;}
}
