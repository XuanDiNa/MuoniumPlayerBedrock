#include "ArkeInterop.h"
#include <iostream>
#include <stdexcept>
static void Require(bool value,char const* text){if(!value)throw std::runtime_error(text);}
int main(int argc,char** argv){try{
    Require(argc==2,"exact Arke image required");std::vector<unsigned char> bytes;
    Require(mp::arke::LoadImage(argv[1],bytes)&&mp::arke::HashMatches(bytes),"frozen image identity");
    bytes.back()^=1;Require(!mp::arke::HashMatches(bytes),"one changed byte must reject the entire profile");
    int a{},b{},c{},x{},y{},z{},foreign{};
    std::array<void*,3> values{&a,&b,&c},callbacks{&x,&y,&z};
    mp::arke::Slots slots;slots.originals=values;
    for(unsigned i=0;i<3;i++)slots.addresses[i]=reinterpret_cast<void* volatile*>(&values[i]);
    values[1]=&foreign;
    Require(!mp::arke::Commit(slots,callbacks),"concurrent slot ownership change rejects installation");
    Require(values[0]==&a&&values[1]==&foreign&&values[2]==&c,"rollback restores only our preceding change");
    values[1]=&b;Require(mp::arke::Commit(slots,callbacks),"complete transaction");
    Require(values==callbacks,"all callbacks installed");
    values[0]=&foreign;mp::arke::Rollback(slots,callbacks,3);
    Require(values[0]==&foreign&&values[1]==&b&&values[2]==&c,"rollback never overwrites a later owner's change");
    std::cout<<"ARKE_SLOTS hashMutationRejected=true conditionalRollback=true passed\n";return 0;
}catch(std::exception const& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
