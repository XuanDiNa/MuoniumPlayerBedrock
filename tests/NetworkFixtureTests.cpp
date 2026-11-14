// Execute the actual optional detour forwarding/snapshot code with owned fake
// objects, without installing hooks or loading Minecraft.
#include "../src/NetworkMetrics.cpp"
#include <iostream>
#include <stdexcept>
#include <latch>
#include <thread>
namespace {
struct Peer {std::uintptr_t* table;Peer* child{};bool local{},bad{},fail{};int ping{-1},updates{};};
struct Network {Peer* peers[2]{};int count{};bool switchDuring{};int updates{};};
std::uintptr_t table[8]{};
Peer* Leaf(Peer* peer){return peer->child?peer->child:peer;}
bool Local(void* self){return Leaf(static_cast<Peer*>(self))->local;}
void* Status(void* self,void* buffer){
    auto peer=Leaf(static_cast<Peer*>(self));if(peer->bad)return nullptr;
    auto ping=static_cast<std::int64_t>(peer->ping);std::memcpy(static_cast<std::byte*>(buffer)+8,&ping,8);return buffer;
}
void UpdatePeer(void* self){auto peer=static_cast<Peer*>(self);++peer->updates;
    if(peer->fail)throw std::runtime_error("original fixture failure");
    if(peer->child)mp::RakDetour(peer->child);
}
void UpdateNetwork(void* self,void*){auto network=static_cast<Network*>(self);++network->updates;
    for(int i=0;i<network->count;++i)mp::PeerDetour(network->peers[i]);
    if(network->switchDuring)mp::SelectNetworkMetrics(0);
}
void Need(bool condition){if(!condition)throw std::runtime_error("network fixture assertion");}
}
int main(){
    table[2]=reinterpret_cast<std::uintptr_t>(&Status);table[5]=reinterpret_cast<std::uintptr_t>(&Local);
    mp::textLo=mp::dataLo=1;mp::textHi=mp::dataHi=UINTPTR_MAX;
    mp::networkOriginal=&UpdateNetwork;mp::peerOriginal=mp::rakOriginal=&UpdatePeer;mp::ready=true;
    Peer peer{table};Network network{{&peer},1};auto owner=reinterpret_cast<std::uintptr_t>(&network);
    auto sample=[&]{return mp::ReadNetworkMetrics(GetTickCount64(),owner);};
    mp::NetworkDetour(&network,nullptr);Need(peer.updates==1&&network.updates==1&&sample().ping==-1);
    mp::SelectNetworkMetrics(owner);peer.local=true;mp::NetworkDetour(&network,nullptr);Need(sample().kind==1&&sample().ping==0);
    peer.local=false;peer.ping=47;mp::NetworkDetour(&network,nullptr);Need(sample().kind==2&&sample().ping==47);
    auto epoch=sample().epoch;peer.ping=80;mp::NetworkDetour(&network,nullptr);Need(sample().ping==80&&sample().epoch==epoch);
    Peer child{table};child.ping=33;peer.child=&child;mp::NetworkDetour(&network,nullptr);
    Need(sample().ping==33&&child.updates==1&&mp::current==nullptr);peer.child=nullptr;
    Peer broken{table};broken.bad=true;network.peers[1]=&broken;network.count=2;
    mp::NetworkDetour(&network,nullptr);Need(sample().kind==0&&sample().ping==-1);
    network.count=0;mp::NetworkDetour(&network,nullptr);Need(sample().ping==-1);
    network.count=1;mp::NetworkDetour(&network,nullptr);Need(sample().ping==80);
    // Even if reset's try_lock fails, recycled owner addresses cannot leak RTT.
    std::latch locked(1),release(1);
    std::thread holder([&]{std::lock_guard lock(mp::stateMutex);locked.count_down();release.wait();});
    locked.wait();mp::SelectNetworkMetrics(0);mp::SelectNetworkMetrics(owner);
    bool unknown=sample().ping==-1;release.count_down();holder.join();
    Need(unknown&&sample().ping==-1);mp::NetworkDetour(&network,nullptr);Need(sample().ping==80);
    peer.ping=65535;mp::NetworkDetour(&network,nullptr);Need(sample().ping==-1);
    peer.fail=true;bool thrown=false;try{mp::NetworkDetour(&network,nullptr);}catch(std::runtime_error const&){thrown=true;}
    Need(thrown&&mp::current==nullptr);peer.fail=false;peer.ping=29;
    mp::NetworkDetour(&network,nullptr);Need(sample().ping==29);
    network.switchDuring=true;mp::NetworkDetour(&network,nullptr);Need(sample().ping==-1&&mp::current==nullptr);
    auto counts=mp::ReadMetricDiagnostics();Need(counts.networkUpdates==static_cast<unsigned>(network.updates)&&counts.selectedUpdates>0&&counts.peerUpdates>0&&counts.snapshots>0);
    std::cout<<"Actual detour forwarding, nested peer sampling, ambiguity, RTT updates, recycled-owner reset and exception restoration passed\n";
}
