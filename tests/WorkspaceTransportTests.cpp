#include "WorkspaceTransport.h"
#include <iostream>
#include <stdexcept>
void Need(bool v,char const* message){if(!v)throw std::runtime_error(message);}
int main(){try{
    mp::WorkspaceChannel channel;mp::WorkspaceTransport transport;
    auto prefix=L"Local\\MuoniumWorkspaceTest-"+std::to_wstring(GetCurrentProcessId());Need(transport.Open(prefix),"private transport");
    auto map=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,(prefix+L"-workspace").c_str());Need(map!=nullptr,"companion open mapping");
    auto p=static_cast<unsigned char*>(MapViewOfFile(map,FILE_MAP_ALL_ACCESS,0,0,64));Need(p!=nullptr,"companion view");
    auto phase=[&](uint32_t v){std::memcpy(p+36,&v,4);};
    mp::WorkspaceRequest r{42,(1ull<<63)+99,GetTickCount64()+14000,9,mp::WorkspacePhase::Requested};channel.Offer(r);
    Need(transport.Exchange(channel),"send explicit request");uint64_t id{};std::memcpy(&id,p+16,8);Need(id==r.request,"high uint64 request exact");
    phase(2);transport.Exchange(channel);Need(channel.Read().phase==mp::WorkspacePhase::Created,"only actual created status");
    channel.Transition(r,mp::WorkspacePhase::Created,mp::WorkspacePhase::Accepted);transport.Exchange(channel);
    uint32_t written{};std::memcpy(&written,p+36,4);Need(written==4,"menu acknowledgement returned to Java");
    phase(5);transport.Exchange(channel);Need(channel.Read().phase==mp::WorkspacePhase::Return,"explicit Return request");
    auto next=r;next.request++;channel.Offer(next);phase(2);transport.Exchange(channel);
    Need(channel.Read().phase==mp::WorkspacePhase::Requested,"stale acknowledgement cannot acknowledge a new request");
    channel.Transition(next,mp::WorkspacePhase::Requested,mp::WorkspacePhase::Cancelled);phase(2);transport.Exchange(channel);
    Need(channel.Read().phase==mp::WorkspacePhase::Cancelled,"cancelled request cannot reactivate from late Java completion");
    UnmapViewOfFile(p);CloseHandle(map);transport.Close();
    Need(!OpenFileMappingW(FILE_MAP_READ,FALSE,(prefix+L"-workspace").c_str()),"mapping lifetime cleanup");
    std::cout<<"Workspace private transport: exact identity, creation, return, stale/cancel refusal passed\n";return 0;
}catch(std::exception const&e){std::cerr<<e.what()<<'\n';return 1;}}
