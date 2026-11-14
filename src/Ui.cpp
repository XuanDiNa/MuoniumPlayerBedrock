// Native UI adaptation of MuoniumPlayer NCMScreen/NavigateBar/ControlsBar.
// Copyright 2026 MuoniumPlayer Team. Apache-2.0.
// Windows-owned rendering, desktop bridge and durable library added by this port.
#include "Runtime.h"
#include "Player.h"
#include "Audio.h"
#include "Jobs.h"
#include "Client.h"
#include "Library.h"
#include "json.hpp"
#include <commctrl.h>
#include <commdlg.h>
#include <dwmapi.h>
#include <mfapi.h>
#include <shlwapi.h>
#include <gdiplus.h>
#ifdef small
#undef small
#endif
#include <windowsx.h>
#include <algorithm>
#include <array>
#include <fstream>
#include <memory>
#include <random>
namespace mp {namespace {
using J=nlohmann::json;
constexpr COLORREF bg=RGB(37,26,38),panel=RGB(27,19,29),surface=RGB(51,36,51),muted=RGB(184,165,182),fg=RGB(250,239,247),accent=RGB(221,136,179);
enum Control {SearchEdit=101,SearchButton,ImportButton,QueueTab,OnlineTab,Previous,PlayPause,Next,Stop,Clear,Close,VolumeControl,SeekControl,
    FavoritesTab,ClientTab,LyricsTab,Connect,Disconnect,ImportPlaylist,ImportLyrics,FetchLyrics,Favorite,Shuffle,Repeat,RemoveRow,FavoriteRow,PlayRow};
enum class Page {Online,Queue,Favorites,Desktop,Lyrics};
struct Hit {RECT rect;int action;std::size_t index;bool enabled{true};};
std::string Read(std::filesystem::path const&p,std::size_t max=65536){
    auto attr=GetFileAttributesW(p.c_str());if(attr==INVALID_FILE_ATTRIBUTES||(attr&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY)))throw std::runtime_error("File unavailable");
    auto n=std::filesystem::file_size(p);if(n>max)throw std::runtime_error("File exceeds limit");std::ifstream f(p,std::ios::binary);std::string s(static_cast<std::size_t>(n),' ');f.read(s.data(),n);if(!f&&n)throw std::runtime_error("Read failed");return s;
}
std::wstring Time(std::int64_t ms){auto seconds=std::max<std::int64_t>(0,ms)/1000;wchar_t s[40];swprintf_s(s,L"%lld:%02lld",seconds/60,seconds%60);return s;}
void Fill(HDC dc,RECT r,COLORREF color){auto b=CreateSolidBrush(color);FillRect(dc,&r,b);DeleteObject(b);}
class App {
public:
    HINSTANCE module;HWND owner{},window{},hud{},edit{},volume{},seek{};std::filesystem::path folder;
    bool preview{},hiddenSmoke{},focusSeen{},settingsReported{},seekDragging{},clientMode{},storageReady{true},dirty{};
    bool chinese{PRIMARYLANGID(GetUserDefaultUILanguage())==LANG_CHINESE};Page page{Page::Online};
    int width{1100},height{740},scroll{},dpi{96},sessionVolume{70};HFONT normal{},small{},large{},titleFont{},lyricFont{};
    Config config;Library library;ShuffleSequence shuffled;std::vector<Track> results;std::vector<Line> lyrics;std::vector<Hit> hits;
    std::wstring status;std::unique_ptr<Audio> audio;std::unique_ptr<Jobs> jobs;std::unique_ptr<Client> client;ClientSnapshot desktop;
    std::unique_ptr<Gdiplus::Bitmap> cover;std::uint64_t coverRevision{},coverGeneration{};std::string desktopTrack;
    std::uint64_t generation{1},searchGeneration{},playlistGeneration{},lyricsGeneration{},playbackLyricsGeneration{},started{},configCheck{},lastConfigError{},lastPaint{},clientCheck{};
    std::filesystem::file_time_type configStamp{};std::array<modmenu::binding::Tracker,4> bindings{};std::vector<std::filesystem::path> cached;
    std::uint64_t lastClientState{~std::uint64_t{}};
    std::int64_t position{},duration{};bool playing{},currentPreview{};std::filesystem::path currentCache;std::mt19937_64 rng{std::random_device{}()};
    explicit App(HINSTANCE m,HWND o,std::filesystem::path p,bool v,bool hidden):module(m),owner(o),folder(std::move(p)),preview(v),hiddenSmoke(hidden){status=T(L"Ready. Search music or connect to NetEase desktop.",L"就緒。搜尋音樂，或連接網易雲桌面客戶端。");}
    ~App(){client.reset();jobs.reset();audio.reset();for(auto const&p:cached){std::error_code ec;std::filesystem::remove(p,ec);}for(auto f:{normal,small,large,titleFont,lyricFont})if(f)DeleteObject(f);}
    std::wstring T(wchar_t const*en,wchar_t const*zh)const{return chinese?zh:en;}
    int S(int x)const{return MulDiv(x,dpi,96);}
    HFONT Font(int size,int weight=FW_NORMAL){return CreateFontW(-S(size),0,0,0,weight,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");}
    void Fonts(){for(auto f:{normal,small,large,titleFont,lyricFont})if(f)DeleteObject(f);normal=Font(15);small=Font(12);large=Font(28,FW_SEMIBOLD);titleFont=Font(19,FW_SEMIBOLD);lyricFont=Font(config.lyricsFontSize,FW_SEMIBOLD);if(edit)SendMessageW(edit,WM_SETFONT,reinterpret_cast<WPARAM>(normal),TRUE);}
    void Text(HDC dc,std::wstring const&t,RECT r,COLORREF color=fg,HFONT font=nullptr,UINT flags=DT_LEFT|DT_SINGLELINE|DT_VCENTER|DT_END_ELLIPSIS){auto old=SelectObject(dc,font?font:normal);SetBkMode(dc,TRANSPARENT);SetTextColor(dc,color);DrawTextW(dc,t.c_str(),static_cast<int>(t.size()),&r,flags|DT_NOPREFIX);SelectObject(dc,old);}
    void Round(HDC dc,RECT r,COLORREF color,int radius=10){auto b=CreateSolidBrush(color);auto old=SelectObject(dc,b);auto pen=SelectObject(dc,GetStockObject(NULL_PEN));RoundRect(dc,r.left,r.top,r.right,r.bottom,S(radius),S(radius));SelectObject(dc,pen);SelectObject(dc,old);DeleteObject(b);}
    void Box(HDC dc,RECT r,std::wstring const&t,int action,bool active=false,bool enabled=true,std::size_t index=0){Round(dc,r,active?RGB(91,53,76):surface);Text(dc,t,r,enabled?(active?accent:fg):RGB(112,96,110),normal,DT_CENTER|DT_SINGLELINE|DT_VCENTER|DT_END_ELLIPSIS);hits.push_back({r,action,index,enabled});}
    void Show(bool visible){if(visible){if(!config.enabled)return;ShowWindow(window,SW_SHOWNORMAL);SetForegroundWindow(window);SetFocus(edit);}else{ShowWindow(window,SW_HIDE);if(owner&&IsWindow(owner))SetForegroundWindow(owner);}}
    void Layout(){RECT r;GetClientRect(window,&r);width=r.right;height=r.bottom;if(edit)MoveWindow(edit,S(24),S(94),S(166),S(30),TRUE);if(volume)MoveWindow(volume,width-S(158),height-S(56),S(130),S(25),TRUE);if(seek)MoveWindow(seek,S(365),height-S(35),std::max(20,width-S(630)),S(20),TRUE);}
    std::string Query(){wchar_t q[2049]{};GetWindowTextW(edit,q,2049);return Utf8(q);}
    void SaveSetting(char const*name,J value){
        try{auto p=folder/L"MuoniumPlayerBedrock.json";auto raw=Read(p);auto j=J::parse(raw);j[name]=std::move(value);auto text=j.dump(2)+"\n";ParseConfig(text);auto tmp=folder/(L".setting-"+std::to_wstring(GetCurrentProcessId())+L"-"+std::to_wstring(GetTickCount64())+L".tmp");
            auto h=CreateFileW(tmp.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);if(h==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot save setting");DWORD n{};bool ok=WriteFile(h,text.data(),static_cast<DWORD>(text.size()),&n,nullptr)&&n==text.size()&&FlushFileBuffers(h);CloseHandle(h);if(!ok||Read(p)!=raw||!MoveFileExW(tmp.c_str(),p.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)){std::error_code ec;std::filesystem::remove(tmp,ec);throw std::runtime_error("Settings changed");}
        }catch(...){status=T(L"Changed for this session. Settings file could not be saved.",L"已套用本次設定，但無法寫入設定檔。");}
    }
    void SaveLibrary(){if(!dirty||hiddenSmoke||!storageReady)return;try{library.shuffle=config.shuffle;WriteLibrary(folder/L"library.json",library);dirty=false;}catch(...){storageReady=false;status=T(L"Library save refused. Existing data was preserved.",L"資料庫儲存失敗；已保留原有檔案。");}}
    void Restore(){try{library=ReadLibrary(folder/L"library.json");}catch(...){storageReady=false;status=T(L"Library is invalid. Preserved unchanged; this session uses a temporary queue.",L"資料庫格式異常，原檔未改動；本次使用暫存佇列。");}}
    void InvalidatePlayback(){++generation;++lyricsGeneration;audio->Stop();lyrics.clear();position=duration=0;playing=false;currentPreview=false;currentCache.clear();}
    void Reload(){
        try{auto p=folder/L"MuoniumPlayerBedrock.json";auto stamp=std::filesystem::last_write_time(p);if(settingsReported&&stamp==configStamp)return;auto c=ParseConfig(Read(p));bool fontChange=config.lyricsFontSize!=c.lyricsFontSize;bool shuffleChange=config.shuffle!=c.shuffle;configStamp=stamp;settingsReported=true;config=c;if(shuffleChange)shuffled.Reset(library.queue.Items().size(),library.queue.Index(),rng());sessionVolume=c.volume;SendMessageW(volume,TBM_SETPOS,TRUE,sessionVolume);audio->Volume(sessionVolume);jobs->Enabled(c.enabled);if(fontChange)Fonts();
            if(!c.enabled){InvalidatePlayback();++searchGeneration;++playlistGeneration;client->Disconnect();desktop={};cover.reset();clientMode=false;ShowWindow(window,SW_HIDE);ShowWindow(hud,SW_HIDE);}Log(folder,"CONFIG accepted; shortcuts may remain NONE");
        }catch(...){if(!lastConfigError||GetTickCount64()-lastConfigError>10000){lastConfigError=GetTickCount64();Log(folder,"CONFIG rejected; last valid settings retained");}}
    }
    void Search(){auto q=Query();if(q.empty()||q.size()>256){status=T(L"Enter a song or artist (up to 256 UTF-8 bytes).",L"請輸入歌名或歌手（最多 256 UTF-8 位元組）。");return;}page=Page::Online;scroll=0;jobs->Submit({true,++searchGeneration,q,0});status=T(L"Searching NetEase…",L"正在搜尋網易雲…");}
    void Playlist(){try{auto id=ParsePlaylistId(Query());jobs->Submit({false,++playlistGeneration,{},id,JobKind::Playlist});status=T(L"Importing public playlist (up to 256 available tracks)…",L"正在匯入公開歌單（最多 256 首可見曲目）…");}catch(std::exception const&e){status=Wide(e.what());}}
    void Import(){
        std::vector<wchar_t> paths(65536);OPENFILENAMEW ofn{sizeof ofn};ofn.hwndOwner=window;ofn.lpstrFilter=L"Audio files\0*.mp3;*.wav;*.flac;*.m4a;*.aac;*.wma\0All files\0*.*\0";ofn.lpstrFile=paths.data();ofn.nMaxFile=static_cast<DWORD>(paths.size());ofn.Flags=OFN_EXPLORER|OFN_FILEMUSTEXIST|OFN_ALLOWMULTISELECT|OFN_NOCHANGEDIR;if(!GetOpenFileNameW(&ofn))return;
        std::filesystem::path first(paths.data());auto next=paths.data()+wcslen(paths.data())+1;std::size_t added{};auto add=[&](std::filesystem::path const&p){auto key="local-"+Utf8(p.wstring());if(library.queue.Add({key,Utf8(p.stem().wstring()),"Local file",p,0}))++added;};
        if(!*next)add(first);else while(*next){add(first/next);next+=wcslen(next)+1;}dirty=true;SaveLibrary();page=Page::Queue;scroll=0;status=std::to_wstring(added)+T(L" tracks imported. Select a track to play.",L" 首已匯入。選取曲目即可播放。");
    }
    void ImportLyric(){wchar_t path[32768]{};OPENFILENAMEW f{sizeof f};f.hwndOwner=window;f.lpstrFilter=L"Timed lyrics\0*.lrc;*.yrc\0";f.lpstrFile=path;f.nMaxFile=32768;f.Flags=OFN_EXPLORER|OFN_FILEMUSTEXIST|OFN_NOCHANGEDIR;if(!GetOpenFileNameW(&f))return;lyrics=ParseLyrics(Read(path,524288));++lyricsGeneration;status=T(L"Lyrics imported for the current track.",L"已為目前曲目匯入歌詞。");}
    void FetchLyric(){auto q=Query();std::int64_t id{};if(q.empty()&&library.queue.Current()&&!clientMode)id=library.queue.Current()->neteaseId;else{if(q.empty()||q.find_first_not_of("0123456789")!=q.npos||q.size()>18){status=T(L"Enter a NetEase song ID in the search box, then Get lyrics.",L"請在搜尋框輸入網易雲歌曲 ID，再按取得歌詞。");return;}id=std::stoll(q);}if(id<=0)return;jobs->Submit({false,++lyricsGeneration,{},id,JobKind::Lyrics});status=T(L"Fetching timed lyrics…",L"正在取得歌詞…");}
    void Load(std::size_t i,bool fromSequence=false){
        if(!library.queue.Select(i))return;if(!fromSequence)shuffled.Reset(library.queue.Items().size(),i,rng());client->Disconnect();clientMode=false;desktop={};cover.reset();InvalidatePlayback();playbackLyricsGeneration=lyricsGeneration;dirty=true;SaveLibrary();auto const&t=*library.queue.Current();status=T(L"Loading…",L"載入中…");
        if(!t.local.empty()){try{auto lrc=t.local;lrc.replace_extension(L".yrc");if(!std::filesystem::exists(lrc))lrc.replace_extension(L".lrc");if(std::filesystem::exists(lrc))lyrics=ParseLyrics(Read(lrc,524288));}catch(...){}if(FAILED(audio->Load(t.local.wstring(),generation)))status=T(L"Windows could not open this audio format.",L"Windows 無法開啟此音訊格式。");}
        else jobs->Submit({false,generation,{},t.neteaseId});
    }
    void DesktopCommand(ClientAction action,std::int64_t ms=0){if(!client->Send({action,desktop.generation,ms}))status=T(L"This control is unavailable in the current NetEase session.",L"目前的網易雲工作階段不支援此控制。");}
    void Advance(int dir,bool ended=false){
        if(clientMode){DesktopCommand(dir>0?ClientAction::Next:ClientAction::Previous);return;}
        if(ended&&config.repeat==2&&library.queue.Index()){Load(*library.queue.Index(),true);return;}
        if(config.shuffle&&(shuffled.Size()!=library.queue.Items().size()||shuffled.Current()!=library.queue.Index()))shuffled.Reset(library.queue.Items().size(),library.queue.Index(),rng());
        auto next=config.shuffle?shuffled.Advance(dir,config.repeat==1,rng()):library.queue.Advance(dir,config.repeat==1);if(next)Load(*next,true);else{audio->Stop();status=T(L"End of queue.",L"已到佇列結尾。");}
    }
    std::vector<Track> const& Rows()const{return page==Page::Online?results:page==Page::Favorites?library.favorites:library.queue.Items();}
    void Act(int id,std::size_t i=0){
        if(!config.enabled&&id!=Close)return;
        switch(id){
            case OnlineTab:page=Page::Online;scroll=0;break;case QueueTab:page=Page::Queue;scroll=0;break;case FavoritesTab:page=Page::Favorites;scroll=0;break;case ClientTab:page=Page::Desktop;break;case LyricsTab:page=Page::Lyrics;scroll=0;break;
            case SearchButton:Search();break;case ImportButton:Import();break;case ImportPlaylist:Playlist();break;case ImportLyrics:ImportLyric();break;case FetchLyrics:FetchLyric();break;
            case Connect:InvalidatePlayback();clientMode=true;desktopTrack.clear();cover.reset();lastClientState=~std::uint64_t{};client->Connect();status=T(L"Connecting. Playback remains owned by NetEase.",L"正在連接。音訊仍由網易雲客戶端播放。");Log(folder,"CLIENT explicit connect");break;
            case Disconnect:client->Disconnect();clientMode=false;desktop={};lyrics.clear();++lyricsGeneration;cover.reset();status=T(L"Disconnected. NetEase playback was left unchanged.",L"已斷開；網易雲播放狀態保持不變。");break;
            case PlayPause:if(clientMode)DesktopCommand(desktop.playing?ClientAction::Pause:ClientAction::Play);else if(audio->Loaded())audio->Toggle();else if(library.queue.Index())Load(*library.queue.Index());else if(!library.queue.Items().empty())Load(0);break;
            case Next:Advance(1);break;case Previous:Advance(-1);break;
            case Stop:if(clientMode)DesktopCommand(ClientAction::Stop);else InvalidatePlayback();break;
            case Clear:if(clientMode)library.queue.Clear();else{InvalidatePlayback();library.queue.Clear();}dirty=true;SaveLibrary();status=T(L"Queue cleared. Favorites kept.",L"已清空佇列，保留收藏。");break;
            case Favorite:if(!clientMode&&library.queue.Current()){library.ToggleFavorite(*library.queue.Current());dirty=true;SaveLibrary();}break;
            case FavoriteRow:{auto t=Rows().at(i);library.ToggleFavorite(t);dirty=true;SaveLibrary();break;}
            case RemoveRow:if(page==Page::Queue){bool selected=library.queue.Index()==i;if(selected&&!clientMode)InvalidatePlayback();library.queue.Remove(i);dirty=true;SaveLibrary();}break;
            case Shuffle:config.shuffle=!config.shuffle;shuffled.Reset(library.queue.Items().size(),library.queue.Index(),rng());SaveSetting("shuffle",config.shuffle);dirty=true;SaveLibrary();break;
            case Repeat:config.repeat=(config.repeat+1)%3;SaveSetting("repeat",config.repeat);break;
            case PlayRow:if(page==Page::Queue)Load(i);else{auto t=Rows().at(i);if(library.queue.Add(t))Load(library.queue.Items().size()-1);else status=T(L"Queue is full (256 tracks).",L"佇列已滿（256 首）。");}break;
            case Close:Show(false);break;
        }InvalidateRect(window,nullptr,FALSE);
    }
    void Results(){for(auto&r:jobs->Take()){
        if(r.kind==JobKind::Search){if(r.generation!=searchGeneration||!config.enabled)continue;if(!r.error.empty())status=Wide(r.error);else{results=std::move(r.tracks);status=results.empty()?T(L"No results.",L"沒有結果。"):T(L"Select a song to add it to the queue and play.",L"選取歌曲即可加入佇列並播放。");}}
        else if(r.kind==JobKind::Playlist){if(r.generation!=playlistGeneration||!config.enabled)continue;if(!r.error.empty())status=Wide(r.error);else{std::size_t n{};for(auto&t:r.tracks)if(library.queue.Add(std::move(t)))++n;dirty=true;SaveLibrary();page=Page::Queue;scroll=0;status=std::to_wstring(n)+T(L" tracks imported; no autoplay. Private/hidden tracks are not included.",L" 首已匯入，不會自動播放；不包含私人或隱藏曲目。");}}
        else if(r.kind==JobKind::Lyrics){if(r.generation!=lyricsGeneration||!config.enabled)continue;if(!r.error.empty())status=Wide(r.error);else{lyrics=ParseLyrics(r.lyrics);status=lyrics.empty()?T(L"No timed lyrics returned.",L"沒有取得時間軸歌詞。"):T(L"Lyrics loaded.",L"歌詞已載入。");}}
        else{if(r.generation!=generation||!config.enabled||clientMode){if(!r.path.empty()){std::error_code ec;std::filesystem::remove(r.path,ec);}continue;}if(!r.error.empty()){status=Wide(r.error);continue;}if(lyricsGeneration==playbackLyricsGeneration){try{lyrics=ParseLyrics(r.lyrics);}catch(...){lyrics.clear();}}currentPreview=r.preview;cached.push_back(r.path);currentCache=r.path;if(FAILED(audio->Load(r.path.wstring(),generation)))status=T(L"Downloaded audio format is unavailable.",L"無法播放下載的音訊格式。");}
    }std::erase_if(cached,[&](auto const&p){if(p==currentCache)return false;std::error_code ec;std::filesystem::remove(p,ec);return !ec;});}
    void Keys(bool eligible){std::array<std::string,4> keys{config.openKey,config.playPauseKey,config.nextKey,config.previousKey};for(std::size_t i=0;i<keys.size();++i){auto binding=modmenu::binding::ParseInput(keys[i]).value_or(modmenu::binding::Binding{});auto sample=modmenu::binding::Sample(binding,[](unsigned k){return (GetAsyncKeyState(static_cast<int>(k))&0x8000)!=0;});if(bindings[i].Update(binding,sample,eligible&&config.enabled).pressed){if(i==0)Show(!IsWindowVisible(window));else if(GetFocus()!=edit)Act(i==1?PlayPause:i==2?Next:Previous);}}focusSeen=eligible;}
    std::int64_t LyricPosition()const{return std::max<std::int64_t>(0,position+config.lyricsTimeOffsetMs);}
    bool Timed()const{return !clientMode||desktop.durationMs>0;}
    std::wstring LyricText()const{if(!Timed())return {};auto i=CurrentLine(lyrics,LyricPosition());return i?Wide(lyrics[*i].text):std::wstring{};}
    std::wstring CurrentTitle()const{return clientMode?(desktop.title.empty()?T(L"NetEase desktop",L"網易雲桌面客戶端"):Wide(desktop.title)):library.queue.Current()?Wide(library.queue.Current()->title):T(L"Nothing playing",L"尚未播放");}
    std::wstring CurrentArtist()const{return clientMode?Wide(desktop.artist):library.queue.Current()?Wide(library.queue.Current()->artist):T(L"Choose your next song",L"選擇想聽的音樂");}
    void DrawLyric(HDC dc,RECT r,bool big=false){
        auto active=Timed()?CurrentLine(lyrics,LyricPosition()):std::nullopt;if(!active){Text(dc,T(L"Music inside Minecraft",L"把音樂裝進 Minecraft"),r,muted,big?lyricFont:large,DT_CENTER|DT_VCENTER|DT_SINGLELINE);return;}
        auto const&l=lyrics[*active];auto text=Wide(l.text);HFONT font=big?lyricFont:large;Text(dc,text,r,muted,font,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);double fraction=1;
        if(!l.words.empty()){std::size_t total{},done{};double progress{};for(auto const&w:l.words){auto n=Wide(w.text).size();total+=n;if(LyricPosition()>=w.at+w.duration)done+=n;else if(LyricPosition()>=w.at&&w.duration>0)progress+=n*std::clamp(double(LyricPosition()-w.at)/w.duration,0.0,1.0);}fraction=total?std::clamp((done+progress)/total,0.0,1.0):1;}
        auto old=SelectObject(dc,font);SIZE size{};GetTextExtentPoint32W(dc,text.c_str(),static_cast<int>(text.size()),&size);SelectObject(dc,old);int left=r.left+std::max<LONG>(0,(r.right-r.left-size.cx)/2);auto saved=SaveDC(dc);IntersectClipRect(dc,left,r.top,left+static_cast<int>(std::min(size.cx,r.right-r.left)*fraction),r.bottom);Text(dc,text,r,accent,font,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);RestoreDC(dc,saved);
    }
    void Artwork(HDC dc,RECT r){Round(dc,r,RGB(72,44,64),18);if(cover){Gdiplus::Graphics g(dc);g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);g.DrawImage(cover.get(),static_cast<int>(r.left),static_cast<int>(r.top),static_cast<int>(r.right-r.left),static_cast<int>(r.bottom-r.top));}else Text(dc,L"♪",r,accent,large,DT_CENTER|DT_VCENTER|DT_SINGLELINE);}
    void UpdateCover(){if(coverRevision==desktop.coverRevision&&coverGeneration==desktop.generation)return;coverRevision=desktop.coverRevision;coverGeneration=desktop.generation;cover.reset();if(!desktop.cover||desktop.cover->empty())return;auto stream=SHCreateMemStream(desktop.cover->data(),static_cast<UINT>(desktop.cover->size()));if(!stream)return;std::unique_ptr<Gdiplus::Bitmap> decoded(Gdiplus::Bitmap::FromStream(stream));if(decoded&&decoded->GetLastStatus()==Gdiplus::Ok&&decoded->GetWidth()&&decoded->GetHeight()&&decoded->GetWidth()<=4096&&decoded->GetHeight()<=4096){auto image=std::make_unique<Gdiplus::Bitmap>(256,256,PixelFormat32bppPARGB);Gdiplus::Graphics graphics(image.get());graphics.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);graphics.DrawImage(decoded.get(),0,0,256,256);cover=std::move(image);}decoded.reset();stream->Release();}
    void Paint(HDC target);
    void RenderPreview();
    void Tick(){
        auto now=GetTickCount64();if(owner&&!IsWindow(owner)){DestroyWindow(window);return;}if(hiddenSmoke&&now-started>1500){DestroyWindow(window);return;}
        if(now-configCheck>500){configCheck=now;Reload();}Results();auto foreground=GetForegroundWindow();bool eligible=foreground==owner||foreground==window;Keys(eligible);
        if(clientMode&&now-clientCheck>500){clientCheck=now;desktop=client->Snapshot();auto key=desktop.title+"\n"+desktop.artist;if(key!=desktopTrack){desktopTrack=key;lyrics.clear();++lyricsGeneration;}UpdateCover();auto signature=(std::uint64_t(desktop.errorCode)<<32)|(desktop.connected?1:0)|(desktop.canPlay?2:0)|(desktop.canPause?4:0)|(desktop.canNext?8:0)|(desktop.canPrevious?16:0)|(desktop.canSeek?32:0)|(desktop.cover?64:0);if(signature!=lastClientState){lastClientState=signature;Log(folder,"CLIENT state="+std::to_string(signature)+" connected="+std::to_string(desktop.connected)+" seek="+std::to_string(desktop.canSeek)+" cover="+std::to_string(bool(desktop.cover))+" error="+std::to_string(desktop.errorCode));}}
        position=clientMode?desktop.positionMs:audio->Position();duration=clientMode?desktop.durationMs:audio->Duration();playing=clientMode?desktop.playing:audio->Playing();
        if(!clientMode&&audio->Ended()&&config.enabled)Advance(1,true);
        if(!clientMode&&FAILED(audio->Error()))status=T(L"Windows audio error. Try another track or format.",L"Windows 音訊錯誤，請嘗試其他曲目或格式。");
        EnableWindow(seek,config.enabled&&(!clientMode?audio->Loaded()&&duration>0:desktop.canSeek));EnableWindow(volume,!clientMode);if(!seekDragging)SendMessageW(seek,TBM_SETPOS,TRUE,duration?static_cast<LPARAM>(position*1000/duration):0);
        bool show=config.enabled&&config.lyricsEnabled&&playing&&eligible&&!IsWindowVisible(window)&&!LyricText().empty();if(show){RECT r;if(owner){GetClientRect(owner,&r);MapWindowPoints(owner,nullptr,reinterpret_cast<POINT*>(&r),2);}else SystemParametersInfoW(SPI_GETWORKAREA,0,&r,0);auto w=std::min<LONG>(r.right-r.left-S(40),S(1000));SetWindowPos(hud,HWND_TOPMOST,r.left+(r.right-r.left-w)/2,r.bottom-S(config.lyricsOffsetY+60),w,S(55),SWP_NOACTIVATE|SWP_SHOWWINDOW);InvalidateRect(hud,nullptr,FALSE);}else ShowWindow(hud,SW_HIDE);
        if(IsWindowVisible(window)&&now-lastPaint>=50){InvalidateRect(window,nullptr,FALSE);lastPaint=now;}
    }
    static LRESULT CALLBACK EditProc(HWND h,UINT msg,WPARAM w,LPARAM l,UINT_PTR,DWORD_PTR raw){auto&a=*reinterpret_cast<App*>(raw);if(msg==WM_KEYDOWN&&w==VK_RETURN){a.Act(SearchButton);return 0;}if(msg==WM_KEYDOWN&&w==VK_ESCAPE){a.Show(false);return 0;}return DefSubclassProc(h,msg,w,l);}
    static LRESULT CALLBACK SliderProc(HWND h,UINT msg,WPARAM w,LPARAM l,UINT_PTR,DWORD_PTR raw){if(msg==WM_KEYDOWN&&w==VK_ESCAPE){reinterpret_cast<App*>(raw)->Show(false);return 0;}return DefSubclassProc(h,msg,w,l);}
    static LRESULT CALLBACK HudProc(HWND h,UINT msg,WPARAM w,LPARAM l){auto*a=reinterpret_cast<App*>(GetWindowLongPtrW(h,GWLP_USERDATA));if(msg==WM_NCCREATE){a=static_cast<App*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);SetWindowLongPtrW(h,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(a));}if(msg==WM_NCHITTEST)return HTTRANSPARENT;if(msg==WM_MOUSEACTIVATE)return MA_NOACTIVATE;if(msg==WM_ERASEBKGND)return 1;if(msg==WM_PAINT&&a){PAINTSTRUCT ps;auto dc=BeginPaint(h,&ps);RECT r;GetClientRect(h,&r);Fill(dc,r,RGB(1,2,3));a->DrawLyric(dc,r,true);EndPaint(h,&ps);return 0;}return DefWindowProcW(h,msg,w,l);}
    static LRESULT CALLBACK Proc(HWND h,UINT msg,WPARAM w,LPARAM l){auto*a=reinterpret_cast<App*>(GetWindowLongPtrW(h,GWLP_USERDATA));if(msg==WM_NCCREATE){a=static_cast<App*>(reinterpret_cast<CREATESTRUCTW*>(l)->lpCreateParams);a->window=h;SetWindowLongPtrW(h,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(a));}if(!a)return DefWindowProcW(h,msg,w,l);
        try{switch(msg){
            case WM_SIZE:a->Layout();return 0;case WM_DPICHANGED:{a->dpi=HIWORD(w);a->Fonts();auto*r=reinterpret_cast<RECT*>(l);SetWindowPos(h,nullptr,r->left,r->top,r->right-r->left,r->bottom-r->top,SWP_NOZORDER|SWP_NOACTIVATE);return 0;}
            case WM_GETMINMAXINFO:reinterpret_cast<MINMAXINFO*>(l)->ptMinTrackSize={a->S(1050),a->S(700)};return 0;
            case WM_ERASEBKGND:return 1;case WM_PAINT:{PAINTSTRUCT ps;auto dc=BeginPaint(h,&ps);a->Paint(dc);EndPaint(h,&ps);return 0;}case WM_TIMER:a->Tick();return 0;case OpenPlayerMessage:a->Show(true);return 0;
            case AudioMessage:{std::unique_ptr<AudioNotice> n(reinterpret_cast<AudioNotice*>(l));if(a->audio)a->audio->Handle(*n);return 0;}
            case WM_HSCROLL:if(reinterpret_cast<HWND>(l)==a->volume&&!a->clientMode){a->sessionVolume=static_cast<int>(SendMessageW(a->volume,TBM_GETPOS,0,0));a->audio->Volume(a->sessionVolume);if(LOWORD(w)==TB_ENDTRACK)a->SaveSetting("volume",a->sessionVolume);}else if(reinterpret_cast<HWND>(l)==a->seek){a->seekDragging=LOWORD(w)==TB_THUMBTRACK;if(!a->seekDragging){auto ms=a->duration*SendMessageW(a->seek,TBM_GETPOS,0,0)/1000;if(a->clientMode)a->DesktopCommand(ClientAction::Seek,ms);else a->audio->Seek(ms);}}return 0;
            case WM_NOTIFY:{auto n=reinterpret_cast<NMCUSTOMDRAW*>(l);if(n->hdr.code==NM_CUSTOMDRAW&&(n->hdr.hwndFrom==a->volume||n->hdr.hwndFrom==a->seek)&&n->dwDrawStage==CDDS_PREPAINT){auto control=n->hdr.hwndFrom;RECT r;GetClientRect(control,&r);Fill(n->hdc,r,panel);int end=r.right-a->S(9),start=a->S(9),y=r.bottom/2;auto max=SendMessageW(control,TBM_GETRANGEMAX,0,0);auto pos=SendMessageW(control,TBM_GETPOS,0,0);int x=start+(max?static_cast<int>((end-start)*pos/max):0);a->Round(n->hdc,{start,y-a->S(2),end,y+a->S(2)},surface,4);bool enabled=IsWindowEnabled(control);if(enabled)a->Round(n->hdc,{start,y-a->S(2),x,y+a->S(2)},accent,4);auto b=CreateSolidBrush(enabled?fg:muted);auto old=SelectObject(n->hdc,b);auto pen=SelectObject(n->hdc,GetStockObject(NULL_PEN));Ellipse(n->hdc,x-a->S(5),y-a->S(5),x+a->S(5),y+a->S(5));SelectObject(n->hdc,pen);SelectObject(n->hdc,old);DeleteObject(b);return CDRF_SKIPDEFAULT;}break;}
            case WM_LBUTTONUP:{POINT p{GET_X_LPARAM(l),GET_Y_LPARAM(l)};auto hits=a->hits;for(auto const&hit:hits)if(PtInRect(&hit.rect,p)){if(hit.enabled)a->Act(hit.action,hit.index);break;}return 0;}
            case WM_MOUSEWHEEL:a->scroll-=GET_WHEEL_DELTA_WPARAM(w)/WHEEL_DELTA*3;InvalidateRect(h,nullptr,FALSE);return 0;case WM_KEYDOWN:if(w==VK_ESCAPE){a->Show(false);return 0;}break;
            case WM_NCHITTEST:{auto result=DefWindowProcW(h,msg,w,l);if(result!=HTCLIENT)return result;POINT p{GET_X_LPARAM(l),GET_Y_LPARAM(l)};ScreenToClient(h,&p);return p.y<a->S(75)&&p.x<a->width-a->S(70)?HTCAPTION:HTCLIENT;}
            case WM_CLOSE:if(a->preview)DestroyWindow(h);else a->Show(false);return 0;
            case WM_DESTROY:KillTimer(h,1);a->SaveLibrary();if(a->hud)DestroyWindow(a->hud);if(a->audio)a->audio->Stop();if(a->client)a->client->Disconnect();PostQuitMessage(0);return 0;
            case WM_CTLCOLOREDIT:{auto dc=reinterpret_cast<HDC>(w);SetTextColor(dc,fg);SetBkColor(dc,surface);SetDCBrushColor(dc,surface);return reinterpret_cast<LRESULT>(GetStockObject(DC_BRUSH));}
        }}catch(...){a->status=a->T(L"Action failed safely; try another track or reload settings.",L"操作失敗，請嘗試其他曲目或重新載入設定。");Log(a->folder,"UI action refused after exception");}return DefWindowProcW(h,msg,w,l);
    }
};
#include "UiView.inl"
}
int RunPlayer(HINSTANCE module,HWND owner,std::filesystem::path folder,bool preview,bool hidden){
    auto oldDpi=SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);HRESULT com=CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);if(FAILED(com))return 1;if(FAILED(MFStartup(MF_VERSION))){CoUninitialize();return 2;}
    Gdiplus::GdiplusStartupInput startup;ULONG_PTR gdip{};Gdiplus::GdiplusStartup(&gdip,&startup,nullptr);INITCOMMONCONTROLSEX controls{sizeof controls,ICC_BAR_CLASSES};InitCommonControlsEx(&controls);int result{};
    {App app(module,owner,std::move(folder),preview,hidden);WNDCLASSEXW wc{sizeof wc};wc.hInstance=module;wc.hCursor=LoadCursorW(nullptr,MAKEINTRESOURCEW(32512));wc.lpfnWndProc=App::Proc;wc.lpszClassName=L"MuoniumPlayerBedrock.Panel.020";
        if(!RegisterClassExW(&wc)&&GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)result=3;else{WNDCLASSEXW hwc=wc;hwc.lpfnWndProc=App::HudProc;hwc.lpszClassName=L"MuoniumPlayerBedrock.Lyrics.020";RegisterClassExW(&hwc);
            RECT area;if(owner){GetClientRect(owner,&area);MapWindowPoints(owner,nullptr,reinterpret_cast<POINT*>(&area),2);}else SystemParametersInfoW(SPI_GETWORKAREA,0,&area,0);app.dpi=owner?static_cast<int>(GetDpiForWindow(owner)):96;int width=app.S(1100),height=app.S(740);auto x=std::max(area.left,area.left+(area.right-area.left-width)/2),y=std::max(area.top,area.top+(area.bottom-area.top-height)/2);
            app.window=CreateWindowExW(WS_EX_TOOLWINDOW,wc.lpszClassName,L"MuoniumPlayer · Bedrock",WS_POPUP|WS_THICKFRAME|WS_CLIPCHILDREN,x,y,width,height,owner,nullptr,module,&app);
            if(!app.window)result=4;else{app.hud=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE|WS_EX_TRANSPARENT|WS_EX_LAYERED,hwc.lpszClassName,L"Muonium lyrics",WS_POPUP,0,0,app.S(1000),app.S(55),owner,nullptr,module,&app);SetLayeredWindowAttributes(app.hud,RGB(1,2,3),255,LWA_COLORKEY);
                app.edit=CreateWindowExW(0,L"EDIT",L"",WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,0,0,100,30,app.window,reinterpret_cast<HMENU>(SearchEdit),module,nullptr);SendMessageW(app.edit,EM_SETLIMITTEXT,2048,0);SendMessageW(app.edit,EM_SETCUEBANNER,TRUE,reinterpret_cast<LPARAM>(L"Song / artist / playlist ID"));SetWindowSubclass(app.edit,App::EditProc,1,reinterpret_cast<DWORD_PTR>(&app));
                app.volume=CreateWindowExW(0,TRACKBAR_CLASSW,L"",WS_CHILD|WS_VISIBLE|TBS_NOTICKS,0,0,100,25,app.window,reinterpret_cast<HMENU>(VolumeControl),module,nullptr);SendMessageW(app.volume,TBM_SETRANGE,TRUE,MAKELPARAM(0,100));app.seek=CreateWindowExW(0,TRACKBAR_CLASSW,L"",WS_CHILD|WS_VISIBLE|TBS_NOTICKS,0,0,100,25,app.window,reinterpret_cast<HMENU>(SeekControl),module,nullptr);SendMessageW(app.seek,TBM_SETRANGE,TRUE,MAKELPARAM(0,1000));
                SetWindowSubclass(app.volume,App::SliderProc,2,reinterpret_cast<DWORD_PTR>(&app));SetWindowSubclass(app.seek,App::SliderProc,2,reinterpret_cast<DWORD_PTR>(&app));SendMessageW(app.edit,EM_SETCUEBANNER,TRUE,reinterpret_cast<LPARAM>(app.chinese?L"歌名／歌手／歌單 ID":L"Song / artist / playlist ID"));
                app.audio=std::make_unique<Audio>(app.window);app.jobs=std::make_unique<Jobs>(app.window,app.folder);app.client=std::make_unique<Client>();app.Fonts();app.Layout();app.Restore();app.Reload();BOOL dark=TRUE;DwmSetWindowAttribute(app.window,20,&dark,sizeof dark);app.started=GetTickCount64();SetTimer(app.window,1,33,nullptr);
                if(preview&&hidden)app.RenderPreview();if(preview&&!hidden)app.Show(true);Log(app.folder,"READY 0.2.0-test1 native UI; no startup media/network activity; keys NONE");MSG msg;while(GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}app.client.reset();app.jobs.reset();while(PeekMessageW(&msg,nullptr,AudioMessage,AudioMessage,PM_REMOVE))delete reinterpret_cast<AudioNotice*>(msg.lParam);Log(app.folder,"STOP player worker exited");
            }
        }
    }
    if(gdip)Gdiplus::GdiplusShutdown(gdip);MFShutdown();CoUninitialize();if(oldDpi)SetThreadDpiAwarenessContext(oldDpi);return result;
}
}

