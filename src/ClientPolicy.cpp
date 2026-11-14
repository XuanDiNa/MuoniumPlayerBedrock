#include "Client.h"
#include <algorithm>
namespace mp {
bool IsNetEaseSource(std::string_view source){
    std::string s(source);std::transform(s.begin(),s.end(),s.begin(),[](unsigned char c){return static_cast<char>(c>='A'&&c<='Z'?c+32:c);});
    return s=="cloudmusic.exe";
}
bool ClientAllows(ClientSnapshot const&s,ClientCommand const&c){
    if(!s.connected||c.generation!=s.generation)return false;
    switch(c.action){case ClientAction::Play:return s.canPlay;case ClientAction::Pause:return s.canPause;case ClientAction::Next:return s.canNext;case ClientAction::Previous:return s.canPrevious;case ClientAction::Stop:return s.canStop;case ClientAction::Seek:return s.canSeek&&s.durationMs>0&&c.positionMs>=0&&c.positionMs<=s.durationMs;}
    return false;
}
}
