#include "Player.h"
#include <iostream>
#include <stdexcept>
using namespace mp;
int checks=0;
void Check(bool ok,char const* label){++checks;if(!ok)throw std::runtime_error(label);}
template<class F>void Refuses(F f,char const* label){bool refused=false;try{f();}catch(...){refused=true;}Check(refused,label);}
int main(){try{
    Check(ParseConfig(R"({"openKey":"CTRL+K","playPauseKey":"SHIFT+K","nextKey":"LALT+K","previousKey":"CTRL+SHIFT+LALT+K"})").openKey=="CTRL+K","combination config accepted by actual 0.7 player parser");
    Refuses([]{ParseConfig(R"({"openKey":"CTRL+K","nextKey":"CTRL+K"})");},"same chord conflicts, distinct modifier masks do not");

    for(auto token:{"CTRL+K","SHIFT+K","LALT+K","CTRL+SHIFT+K","CTRL+LALT+K","SHIFT+LALT+K","CTRL+SHIFT+LALT+K"}){
        auto raw=std::string("{\"openKey\":\"")+token+"\"}";auto c=ParseConfig(raw);auto binding=*modmenu::binding::ParseInput(c.openKey);
        modmenu::binding::Tracker tracker;modmenu::binding::Snapshot sample{};Check(!tracker.Update(binding,sample,true).pressed,"runtime primes released binding");
        sample[162]=(binding.modifiers&1)!=0;sample[160]=(binding.modifiers&2)!=0;sample[164]=(binding.modifiers&4)!=0;sample['K']=true;
        Check(tracker.Update(binding,sample,true).pressed,"runtime uses parsed full chord");Check(!tracker.Update(binding,sample,true).pressed,"no repeat");
        tracker.Update(binding,sample,false);Check(!tracker.Update(binding,sample,true).pressed,"refocus held chord does not act");
    }
    Check(ParseConfig("{}").volume==70&&ParseConfig("{}").openKey=="NONE","defaults no key");
    Check(ParseConfig("{}").miniPlayerKey=="NONE"&&ParseConfig("{}").hudEditorKey=="NONE","new UI shortcuts remain unbound");
    Check(ParseConfig("{}").islandSearchKey=="NONE","island search defaults unbound");
    Check(ParseConfig(R"({"islandSearchKey":"F7"})").islandSearchKey=="F7","island search accepts custom key");
    Refuses([]{ParseConfig(R"({"islandSearchKey":"F7","openKey":"F7"})");},"island search binding collision");
    Check(!ParseConfig("{}").worldLyricsOcclusion&&!ParseConfig("{}").smtcPublishEnabled,"new optional features default off");
    Check(ParseConfig(R"({"worldLyricsOcclusion":true,"smtcPublishEnabled":true})").smtcPublishEnabled,"new feature boolean parsing");
    Refuses([]{ParseConfig(R"({"worldLyricsOcclusion":1})");},"occlusion strict boolean");
    Refuses([]{ParseConfig(R"({"smtcPublishEnabled":"true"})");},"publisher strict boolean");
    Refuses([]{ParseConfig(R"({"openKey":"F6","miniPlayerKey":"F6"})");},"mini binding collision");
    Refuses([]{ParseConfig(R"({"hudEditorKey":"F6","miniPlayerKey":"F6"})");},"editor binding collision");
    Refuses([&]{ParseConfig(R"({"openKey":"F6","speakerKey":"F6"})");},"speaker binding conflicts with existing controls");
    Refuses([&]{ParseConfig(R"({"speakerKey":"ESC"})");},"speaker cannot bind Escape");
    Check(ParseConfig("{}").hudEnabled&&ParseConfig("{}").dynamicIslandEnabled,"HUD defaults preserve display");
    Check(!ParseConfig(R"({"hudEnabled":false,"dynamicIslandEnabled":false})").hudEnabled,"independent HUD off");
    Refuses([]{ParseConfig(R"({"dynamicIslandEnabled":1})");},"island strict boolean");
    for(auto raw:{R"({"musicInfoEnabled":1})",R"({"musicLyricsEnabled":"true"})",R"({"spectrumEnabled":null})",
            R"({"infoXPercent":101})",R"({"infoScalePercent":49})",R"({"desktopLyricsScalePercent":201})",
            R"({"islandOffsetX":-1001})",R"({"islandScalePercent":136})",R"({"spectrumScalePercent":24})",
            R"({"lyricColorCustom":0})",R"({"lyricColorRed":256})",R"({"lyricColorGreen":1.5})",R"({"lyricColorBlue":"20"})"})
        Refuses([&]{ParseConfig(raw);},"individual HUD/color settings must reject bad edits atomically");
    auto layout=ParseConfig(R"({"musicInfoEnabled":false,"musicLyricsEnabled":true,"spectrumEnabled":false,"infoXPercent":100,"infoYPercent":0,"infoScalePercent":50,"desktopLyricsXPercent":0,"desktopLyricsYPercent":100,"desktopLyricsScalePercent":200,"islandOffsetX":-1000,"islandOffsetY":1000,"islandScalePercent":60,"spectrumOffsetX":1000,"spectrumOffsetY":-1000,"spectrumScalePercent":25,"lyricColorCustom":true,"lyricColorRed":0,"lyricColorGreen":180,"lyricColorBlue":255})");
    Check(!layout.musicInfoEnabled&&layout.musicLyricsEnabled&&!layout.spectrumEnabled&&layout.infoXPercent==100&&layout.infoScalePercent==50,"independent layout parsed");
    Check(layout.islandOffsetX==-1000&&layout.spectrumScalePercent==25&&layout.lyricColorCustom&&layout.lyricColorGreen==180,"signed offsets and RGB parsed");
    auto c=ParseConfig(R"({"enabled":false,"volume":0,"openKey":"NONE","future":{"keep":true}})");
    Check(!c.enabled&&c.volume==0&&c.openKey=="NONE","unbound zero volume");
    for(auto raw:{R"({"volume":-1})",R"({"volume":101})",R"({"enabled":1})",R"({"openKey":"ESC"})",R"({"openKey":"F9","nextKey":"F9"})",R"({"volume":1,"volume":2})",R"({"repeat":3})",R"([])"})Refuses([&]{ParseConfig(raw);},"invalid config refused");
    Check(KeyCode("NONE")==0&&KeyCode("F9")==0x78,"key conversion");
    Check(Utf8(Wide("金鏟子 音樂 🎵"))=="金鏟子 音樂 🎵","unicode roundtrip");
    Refuses([]{Wide(std::string("\xC0\xAF",2));},"invalid UTF8");
    auto l=ParseLyrics("\xEF\xBB\xBF[ar:artist]\n[offset:-100]\n[00:01.20][00:02.250]你好\n[00:04]next\n[00:61]bad\n");
    Check(l.size()==3&&l[0].at==1100&&l[1].at==2150&&l[2].at==3900,"LRC offset multiple tags");
    Check(!CurrentLine(l,1000)&&CurrentLine(l,1100)==0&&CurrentLine(l,9999)==2,"lyric selection boundaries");
    auto y=ParseLyrics("[1000,1200](1000,400,0)你(1400,800,0)好\n[2300,200](2300,200,0)!\n");
    Check(y.size()==2&&y[0].words.size()==2&&y[0].text=="你好"&&y[0].words[1].at==1400,"YRC words");
    Check(ParseLyrics("[00:01.1234]bad\n[bad]x\n").empty(),"bad timing rejected");
    Refuses([]{ParseLyrics(std::string(524289,'a'));},"lyrics bounded");
    Queue q;Check(!q.Current()&&!q.Advance(1,false),"empty queue");
    q.Add({"a","A"});q.Add({"b","B"});Check(q.Select(0)&&q.Current()->key=="a","select");
    Check(q.Advance(1,false)==1&&!q.Advance(1,false)&&q.Current()->key=="b","queue end stable");
    Check(q.Advance(1,true)==0&&!q.Select(7),"repeat and invalid select");
    q.Clear();for(int i=0;i<256;++i)Check(q.Add({std::to_string(i),"track"}),"bounded queue accepts");Check(!q.Add({"overflow","track"}),"queue capacity");
    Check(FormEncode("a b&你好")=="a+b%26%E4%BD%A0%E5%A5%BD","form unicode");
    Refuses([]{Weapi("{}","short");},"bad AES key");
    auto enc=Weapi("{}","0123456789abcdef");
    Check(enc.params=="kxBNj/l03n7aQ/q0EdABwtOsIYuwYGkESNTUk4QvWCg=","weapi AES golden");
    Check(enc.encSecKey=="35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b949debf242ee39a71ca1f6cda71b1b86a45aa909ee27f7e78e267d34e732f0de948206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b993156520ea826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52","weapi RSA golden");
    std::cout<<checks<<" checks passed\n";return 0;
}catch(std::exception const&e){std::cerr<<"FAIL after "<<checks<<": "<<e.what()<<"\n";return 1;}}
