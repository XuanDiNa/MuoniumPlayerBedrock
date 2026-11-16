#pragma once
#include "Runtime.h"
#include "WorkspaceHandoff.h"
#include <cstring>
namespace mp {
// Mutex-protected, bounded, process-private mailbox; no filesystem polling.
// Header magic/version, session/request/deadline, logical control, phase.
class WorkspaceTransport {
    HANDLE mapping_{},mutex_{};unsigned char* view_{};
public:
    ~WorkspaceTransport(){Close();}
    void Close(){if(view_)UnmapViewOfFile(view_);if(mapping_)CloseHandle(mapping_);if(mutex_)CloseHandle(mutex_);view_=nullptr;mapping_=mutex_=nullptr;}
    bool Open(std::wstring const& prefix){
        Close();mapping_=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,64,(prefix+L"-workspace").c_str());
        if(!mapping_||GetLastError()==ERROR_ALREADY_EXISTS){Close();return false;}
        mutex_=CreateMutexW(nullptr,FALSE,(prefix+L"-workspace-lock").c_str());
        if(!mutex_||GetLastError()==ERROR_ALREADY_EXISTS){Close();return false;}
        view_=static_cast<unsigned char*>(MapViewOfFile(mapping_,FILE_MAP_READ|FILE_MAP_WRITE,0,0,64));
        if(!view_){Close();return false;}std::memset(view_,0,64);
        uint32_t magic=0x4D57534D,version=1;std::memcpy(view_,&magic,4);std::memcpy(view_+4,&version,4);return true;
    }
    bool Exchange(WorkspaceChannel& channel){
        if(!view_)return false;auto locked=WaitForSingleObject(mutex_,0);if(locked!=WAIT_OBJECT_0&&locked!=WAIT_ABANDONED)return false;
        auto r=channel.Read();uint64_t s{},id{};uint32_t phase{};
        std::memcpy(&s,view_+8,8);std::memcpy(&id,view_+16,8);std::memcpy(&phase,view_+36,4);
        bool same=s==r.session&&id==r.request;
        if(same){
            if(r.phase==WorkspacePhase::Requested&&(phase==2||phase==3))channel.Transition(r,r.phase,WorkspacePhase(phase));
            else if(r.phase==WorkspacePhase::Accepted&&phase==5)channel.Transition(r,r.phase,WorkspacePhase::Return);
            r=channel.Read();
        }
        if(!same||phase!=uint32_t(r.phase)){
            std::memcpy(view_+8,&r.session,8);std::memcpy(view_+16,&r.request,8);std::memcpy(view_+24,&r.deadline,8);
            std::memcpy(view_+32,&r.control,4);phase=uint32_t(r.phase);std::memcpy(view_+36,&phase,4);
        }
        ReleaseMutex(mutex_);return true;
    }
};
}
