#include "PassiveScreenRequest.h"
#include "ModMenu/Settings.h"
#include "Player.h"
#include <array>
#include <algorithm>
namespace mp {
std::optional<PassiveScreenRequest::Stamp> PassiveScreenRequest::Current()const {
    auto path=folder_/L"MuoniumPlayerBedrock.json";
    auto handle=CreateFileW(path.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
            nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
    if(handle==INVALID_HANDLE_VALUE)return {};
    BY_HANDLE_FILE_INFORMATION info{};bool ok=GetFileInformationByHandle(handle,&info)!=FALSE;CloseHandle(handle);
    if(!ok||info.nNumberOfLinks!=1||(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))
            ||info.nFileSizeHigh||info.nFileSizeLow>65536)return {};
    return Stamp{info.dwVolumeSerialNumber,info.nFileIndexHigh,info.nFileIndexLow,info.nFileSizeHigh,info.nFileSizeLow,info.ftLastWriteTime};
}
unsigned PassiveScreenRequest::Poll(bool enabled)noexcept {
    try {
        auto stamp=Current();if(!stamp||(previous_&&*stamp==*previous_))return 0;
        auto draft=menu::LoadSettings(folder_.parent_path(),folder_.filename().string());
        if(!draft.valid)return 0;
        auto found=std::find_if(draft.fields.begin(),draft.fields.end(),[](auto const& f){return f.path=="/openInterfaceRequest";});
        if(found==draft.fields.end()||found->type!=menu::SettingType::Choice||found->options.size()!=6)return 0;
        int choice=std::get<int>(found->value);if(choice<0||choice>5)return 0;
        auto config=ParseConfig(draft.original);
        if(!choice){previous_=stamp;primed_=true;return 0;}
        // Use the exact frozen local3 consumer for reset, including description,
        // manifest, concurrent-edit and atomic-save validation. Preserve unknown keys.
        found->value=0;
        auto saved=menu::ApplySettings(folder_.parent_path(),draft);if(!saved.saved)return 0;
        const bool dispatch=primed_&&enabled&&config.enabled;
        primed_=true;previous_.reset();
        Log(folder_,"MODMENU local3 request consumed choice="+std::to_string(choice)+" dispatched="+(dispatch?"true":"false"));
        constexpr std::array<unsigned,6> events{0,11,7,8,9,10};
        return dispatch?events[choice]:0;
    }catch(...){return 0;}
}
}
