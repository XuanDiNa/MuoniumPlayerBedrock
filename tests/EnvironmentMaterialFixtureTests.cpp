// Exercise the production material reader on owned old/new layout objects.
#include "../src/EnvironmentBridge.cpp"
#include <iostream>
#include <stdexcept>
template<class T>void Put(unsigned char* p,std::size_t at,T value){std::memcpy(p+at,&value,sizeof value);}
void Need(bool b,char const* why){if(!b)throw std::runtime_error(why);}
int main(){try{
    unsigned char block[128]{},legacy[256]{},oldMaterial[4]{};
    Put<void*>(block,0x68,legacy);std::memcpy(legacy+0x98,"stone",5);
    Put<std::size_t>(legacy,0xA8,5);Put<std::size_t>(legacy,0xB0,15);
    mp::Reader reader{};mp::native=&mp::Contract2652;
    Put<void*>(legacy,0x28,oldMaterial);oldMaterial[0]=1;oldMaterial[3]=1;
    Need(mp::Material(block,reader)==1,"old pointer material solid block");
    oldMaterial[1]=1;Need(mp::Material(block,reader)==0,"old never-buildable block");
    oldMaterial[0]=5;oldMaterial[2]=1;Need(mp::Material(block,reader)==8,"old water block");
    // The old pointer slot becomes an inline two-byte material in 26.60.
    mp::native=&mp::Contract2660;reader={};std::memset(legacy+0x28,0,8);
    legacy[0x28]=1;legacy[0x29]=4;
    Need(mp::Material(block,reader)==1,"26.60 inline solid material");
    legacy[0x29]=5;Need(mp::Material(block,reader)==0,"26.60 bit zero means never-buildable");
    legacy[0x29]=0;Need(mp::Material(block,reader)==0,"26.60 no motion blocking");
    legacy[0x28]=5;legacy[0x29]=2;
    Need(mp::Material(block,reader)==8,"26.60 inline water type and liquid bit");
    legacy[0x28]=1;legacy[0x29]=2;
    Need(mp::Material(block,reader)==0,"26.60 liquid bit alone is not water");
    Need(mp::Material(nullptr,reader)==255,"null block remains unknown");
    std::cout<<"Production old pointer and 26.60 inline materials: solid, non-solid, water and unknown passed\n";
    return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
