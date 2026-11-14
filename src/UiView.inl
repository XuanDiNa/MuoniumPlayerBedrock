// Rendering and offline fixture for the native MuoniumPlayer shell.
void App::Paint(HDC target){
    RECT area{0,0,width,height};auto dc=CreateCompatibleDC(target);auto bitmap=CreateCompatibleBitmap(target,std::max(1,width),std::max(1,height));auto old=SelectObject(dc,bitmap);Fill(dc,area,bg);hits.clear();Fill(dc,{0,0,S(214),height},panel);
    Text(dc,L"MuoniumPlayer",{S(24),S(25),S(200),S(57)},accent,titleFont);Text(dc,L"BEDROCK · 0.2.0-test1",{S(25),S(58),S(200),S(80)},muted,small);
    Round(dc,{S(19),S(89),S(195),S(130)},surface);
    Box(dc,{S(24),S(135),S(190),S(166)},T(L"Search NetEase",L"搜尋網易雲"),SearchButton);
    Text(dc,T(L"YOUR MUSIC",L"我的音樂"),{S(25),S(185),S(195),S(210)},muted,small);
    int y=220;for(auto const&n:std::vector<std::tuple<Page,int,std::wstring>>{{Page::Online,OnlineTab,T(L"Discover",L"探索音樂")},{Page::Queue,QueueTab,T(L"Play queue",L"播放佇列")},{Page::Favorites,FavoritesTab,T(L"Favorites",L"我的收藏")},{Page::Desktop,ClientTab,T(L"NetEase desktop",L"網易雲桌面連動")},{Page::Lyrics,LyricsTab,T(L"Now playing / lyrics",L"正在播放／歌詞")}}){Box(dc,{S(16),S(y),S(198),S(y+36)},std::get<2>(n),std::get<1>(n),page==std::get<0>(n));y+=44;}
    Box(dc,{S(24),height-S(192),S(190),height-S(160)},T(L"Import local music",L"匯入本機音樂"),ImportButton);
    Box(dc,{S(24),height-S(151),S(190),height-S(119)},T(L"Import playlist ID / URL",L"匯入歌單 ID／網址"),ImportPlaylist);
    Text(dc,T(L"Settings in ModMenu",L"在 ModMenu 調整設定"),{S(24),height-S(73),S(201),height-S(49)},muted,small);Text(dc,T(L"All keys unbound by default",L"所有快捷鍵預設無綁定"),{S(24),height-S(49),S(204),height-S(25)},muted,small);
    int left=S(242),right=width-S(30),bottom=height-S(126);Box(dc,{width-S(55),S(22),width-S(22),S(55)},L"×",Close);
    auto heading=page==Page::Online?T(L"Discover music",L"探索音樂"):page==Page::Queue?T(L"Your play queue",L"播放佇列"):page==Page::Favorites?T(L"Your favorites",L"我的收藏"):page==Page::Desktop?T(L"NetEase desktop",L"網易雲桌面連動"):T(L"Now playing",L"正在播放");
    Text(dc,heading,{left,S(28),right-S(30),S(67)},fg,large);Text(dc,status,{left,S(76),right,S(120)},muted,small,DT_LEFT|DT_WORDBREAK|DT_END_ELLIPSIS);
    if(page==Page::Desktop){
        Artwork(dc,{left,S(145),left+S(165),S(310)});int x=left+S(195);Text(dc,desktop.title.empty()?T(L"Your music, your account",L"使用你的網易雲帳號播放"):Wide(desktop.title),{x,S(146),right,S(195)},fg,titleFont);
        Text(dc,Wide(desktop.artist),{x,S(194),right,S(225)},muted);Text(dc,Wide(desktop.album),{x,S(225),right,S(250)},muted,small);
        Box(dc,{x,S(270),x+S(130),S(307)},T(L"Connect / retry",L"連接／重試"),Connect);Box(dc,{x+S(143),S(270),x+S(268),S(307)},T(L"Disconnect",L"斷開連接"),Disconnect,false,clientMode);
        Round(dc,{left,S(336),right,std::min(bottom,S(510))},surface);Text(dc,Wide(desktop.message),{left+S(19),S(348),right-S(19),S(399)},fg,normal,DT_LEFT|DT_WORDBREAK|DT_END_ELLIPSIS);
        Text(dc,T(L"1. Open NetEase and sign in there.\n2. Select any song in NetEase, then Connect here.\n3. Use the supported controls below. Volume stays in NetEase.\nDisconnecting does not pause your desktop player.",L"1. 開啟網易雲，並在客戶端內登入。\n2. 在網易雲選擇歌曲，再按此處的連接。\n3. 使用下方可用的控制，音量請在網易雲調整。\n斷開連接不會暫停桌面播放器。"),{left+S(19),S(406),right-S(19),bottom},muted,small,DT_LEFT|DT_WORDBREAK|DT_END_ELLIPSIS);
    }else if(page==Page::Lyrics){
        int side=std::min(S(235),(right-left)/3);Artwork(dc,{left,S(151),left+side,S(151)+side});Text(dc,CurrentTitle(),{left,S(163)+side,left+side,S(205)+side},fg,titleFont);Text(dc,CurrentArtist(),{left,S(206)+side,left+side,S(240)+side},muted);
        int x=left+side+S(40);int center=S(275);auto active=Timed()?CurrentLine(lyrics,LyricPosition()):std::nullopt;
        if(!Timed())Text(dc,T(L"This desktop session has no usable timeline.\nTimed lyric sync is unavailable.",L"此桌面工作階段未提供可用時間軸。\n目前無法同步時間歌詞。"),{x,S(150),right,S(350)},muted,normal,DT_CENTER|DT_VCENTER|DT_WORDBREAK);
        else if(lyrics.empty())Text(dc,T(L"Lyrics will appear here.\nImport LRC / YRC, or enter a song ID and Get lyrics.",L"歌詞將顯示在此。\n匯入 LRC／YRC，或輸入歌曲 ID 並取得歌詞。"),{x,S(150),right,S(350)},muted,normal,DT_CENTER|DT_VCENTER|DT_WORDBREAK);
        else{int current=active?static_cast<int>(*active):0;for(int k=-2;k<=3;++k){auto i=current+k;if(i<0||i>=static_cast<int>(lyrics.size()))continue;RECT row{x,center+k*S(58),right,center+(k+1)*S(58)};if(k==0&&active)DrawLyric(dc,row);else Text(dc,Wide(lyrics[i].text),row,muted,normal,DT_CENTER|DT_VCENTER|DT_SINGLELINE|DT_END_ELLIPSIS);}}
        Box(dc,{left,bottom-S(44),left+S(150),bottom-S(8)},T(L"Import LRC / YRC",L"匯入 LRC／YRC"),ImportLyrics);Box(dc,{left+S(162),bottom-S(44),left+S(285),bottom-S(8)},T(L"Get lyrics",L"取得歌詞"),FetchLyrics);
        Text(dc,T(L"Timing offset: ",L"時間偏移：")+std::to_wstring(config.lyricsTimeOffsetMs)+L" ms · ModMenu",{left+S(306),bottom-S(44),right,bottom-S(8)},muted,small);
    }else{
        Text(dc,page==Page::Online?T(L"NETEASE MUSIC · search songs and artists",L"網易雲音樂 · 搜尋歌曲與歌手"):std::to_wstring(Rows().size())+T(L" tracks · saved locally",L" 首 · 本機保存"),{left,S(135),right,S(162)},accent,small);
        if(page==Page::Queue)Box(dc,{right-S(118),S(125),right,S(159)},T(L"Clear queue",L"清空佇列"),Clear);
        auto const&rows=Rows();int top=S(181),rowH=S(53),visible=std::max(0,(bottom-top)/rowH);scroll=std::clamp(scroll,0,std::max(0,static_cast<int>(rows.size())-visible));
        if(rows.empty())Text(dc,page==Page::Online?T(L"Search using the box on the left.",L"在左側輸入歌名或歌手並搜尋。"):T(L"No music here yet. Import music or add a favorite.",L"這裡還沒有音樂。匯入歌曲或加入收藏。"),{left,top,right,bottom},muted,normal,DT_CENTER|DT_VCENTER|DT_SINGLELINE);
        for(int row=0;row<visible&&row+scroll<static_cast<int>(rows.size());++row){auto i=static_cast<std::size_t>(row+scroll);RECT r{left,top+row*rowH,right,top+(row+1)*rowH-S(3)};bool current=page==Page::Queue&&library.queue.Index()==i;Round(dc,r,current?RGB(75,47,67):row%2?surface:bg,8);
            Text(dc,std::to_wstring(i+1),{r.left+S(9),r.top,r.left+S(42),r.bottom},current?accent:muted,small);Text(dc,Wide(rows[i].title),{r.left+S(48),r.top+S(2),r.right-S(90),r.top+S(26)},current?accent:fg);Text(dc,Wide(rows[i].artist),{r.left+S(48),r.top+S(25),r.right-S(90),r.bottom},muted,small);
            hits.push_back({{r.left,r.top,r.right-S(92),r.bottom},PlayRow,i});Box(dc,{r.right-S(80),r.top+S(8),r.right-S(43),r.bottom-S(8)},library.IsFavorite(rows[i].key)?L"♥":L"♡",FavoriteRow,library.IsFavorite(rows[i].key),true,i);
            if(page==Page::Queue)Box(dc,{r.right-S(37),r.top+S(8),r.right-S(5),r.bottom-S(8)},L"×",RemoveRow,false,true,i);
        }
    }
    Fill(dc,{S(214),height-S(112),width,height},panel);Artwork(dc,{S(234),height-S(90),S(288),height-S(36)});Text(dc,CurrentTitle()+(currentPreview?L" [Preview]":L""),{S(300),height-S(98),S(528),height-S(70)},fg);Text(dc,CurrentArtist(),{S(300),height-S(70),S(528),height-S(46)},muted,small);
    int middle=(width+S(214))/2;bool transport=clientMode?desktop.connected:true;Box(dc,{middle-S(80),height-S(91),middle-S(43),height-S(56)},L"|‹",Previous,false,transport&&(!clientMode||desktop.canPrevious));Box(dc,{middle-S(34),height-S(91),middle+S(34),height-S(56)},playing?L"Ⅱ":L"▶",PlayPause,true,transport&&(!clientMode||(playing?desktop.canPause:desktop.canPlay)));Box(dc,{middle+S(43),height-S(91),middle+S(80),height-S(56)},L"›|",Next,false,transport&&(!clientMode||desktop.canNext));
    Box(dc,{middle+S(90),height-S(91),middle+S(125),height-S(56)},L"■",Stop,false,!clientMode||desktop.canStop);
    Box(dc,{S(234),height-S(30),S(288),height-S(7)},L"♥",Favorite,false,!clientMode&&library.queue.Current());Box(dc,{S(297),height-S(30),S(352),height-S(7)},config.shuffle?L"⇄ on":L"⇄",Shuffle,config.shuffle,!clientMode);
    Text(dc,Time(position),{S(365),height-S(58),S(430),height-S(34)},muted,small);Text(dc,duration?Time(duration):L"--:--",{width-S(330),height-S(58),width-S(266),height-S(34)},muted,small,DT_RIGHT|DT_VCENTER|DT_SINGLELINE);
    Text(dc,clientMode?T(L"Volume in NetEase",L"音量由網易雲控制"):T(L"Volume ",L"音量 ")+std::to_wstring(sessionVolume)+L"%",{width-S(170),height-S(95),width-S(18),height-S(65)},muted,small);
    Box(dc,{width-S(250),height-S(91),width-S(182),height-S(56)},config.repeat==0?L"↻ off":config.repeat==1?L"↻ all":L"↻ 1",Repeat,config.repeat!=0,!clientMode);
    BitBlt(target,0,0,width,height,dc,0,0,SRCCOPY);SelectObject(dc,old);DeleteObject(bitmap);DeleteDC(dc);
}
void App::RenderPreview(){
    std::filesystem::create_directories(folder/L"artifacts");BITMAPINFO info{};info.bmiHeader={sizeof(BITMAPINFOHEADER),width,-height,1,32,BI_RGB,0,0,0,0,0};void*pixels{};auto dc=CreateCompatibleDC(nullptr);auto bitmap=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,nullptr,0);if(!bitmap||!pixels)throw std::runtime_error("Preview unavailable");auto old=SelectObject(dc,bitmap);
    auto oldLibrary=library;auto oldPage=page;auto oldLyrics=lyrics;auto oldResults=results;auto oldDesktop=desktop;auto oldMode=clientMode;
    results={{"netease-1","把音樂裝進 Minecraft","MuoniumPlayer · Native UI port",{},1},{"netease-2","Unicode: 金鏟子 🎵","Artist / rendering fixture",{},2},{"netease-3","Your next favorite song","Example artist",{},3}};library.queue.Clear();for(auto const&t:results)library.queue.Add(t);library.queue.Select(0);library.favorites={results[0]};lyrics=ParseLyrics("[00:00]Music inside Minecraft\n[00:04]把音樂裝進 Minecraft\n[00:08]逐字歌詞與本機音樂\n[00:12]Enjoy your music\n");position=5000;duration=180000;status=T(L"Offline UI fixture. No network or playback commands.",L"離線介面樣本；沒有網路或播放指令。");
    bool originalLanguage=chinese;
    for(bool useChinese:{true,false}){chinese=useChinese;
      for(auto const&[p,name]:std::vector<std::pair<Page,std::wstring>>{{Page::Online,L"player-preview"},{Page::Queue,L"queue-preview"},{Page::Favorites,L"favorites-preview"},{Page::Lyrics,L"lyrics-preview"},{Page::Desktop,L"desktop-preview"}}){
        page=p;clientMode=p==Page::Desktop;if(clientMode){desktop.connected=true;desktop.title="NetEase desktop session";desktop.artist="Your account · your playback rights";desktop.message="Connected. This client session does not expose a seekable timeline.";desktop.canPlay=desktop.canNext=desktop.canPrevious=true;}Paint(dc);
        SendMessageW(seek,TBM_SETPOS,TRUE,position*1000/duration);EnableWindow(seek,!clientMode);EnableWindow(volume,!clientMode);
        SendMessageW(edit,EM_SETCUEBANNER,TRUE,reinterpret_cast<LPARAM>(chinese?L"歌名／歌手／歌單 ID":L"Song / artist / playlist ID"));
        for(HWND control:{edit,seek,volume}){RECT r;GetWindowRect(control,&r);MapWindowPoints(nullptr,window,reinterpret_cast<POINT*>(&r),2);auto saved=SaveDC(dc);SetViewportOrgEx(dc,r.left,r.top,nullptr);SendMessageW(control,WM_PRINT,reinterpret_cast<WPARAM>(dc),PRF_CLIENT|PRF_ERASEBKGND);RestoreDC(dc,saved);}
        BITMAPFILEHEADER header{};header.bfType=0x4d42;header.bfOffBits=sizeof header+sizeof(BITMAPINFOHEADER);header.bfSize=header.bfOffBits+width*height*4;auto suffix=chinese?L"":L"-en";std::ofstream f(folder/L"artifacts"/(name+suffix+L".bmp"),std::ios::binary);f.write(reinterpret_cast<char*>(&header),sizeof header);f.write(reinterpret_cast<char*>(&info.bmiHeader),sizeof info.bmiHeader);f.write(static_cast<char*>(pixels),static_cast<std::streamsize>(width)*height*4);
      }
    }chinese=originalLanguage;
    library=std::move(oldLibrary);page=oldPage;lyrics=std::move(oldLyrics);results=std::move(oldResults);desktop=std::move(oldDesktop);clientMode=oldMode;SelectObject(dc,old);DeleteObject(bitmap);DeleteDC(dc);
}
