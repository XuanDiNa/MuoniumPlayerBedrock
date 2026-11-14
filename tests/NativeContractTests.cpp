#include "Runtime.h"
#include "NativeContracts.h"
#include <iostream>
#include <stdexcept>
#include <vector>
void Need(bool b,char const* why){if(!b)throw std::runtime_error(why);}
int main(){try{
    Need(!mp::SelectNativeContract(nullptr),"no file identity means no native contract");
    for(auto const& identity:mp::HostIdentities){
        auto p=mp::SelectNativeContract(&identity);
        Need(p&&p->version==identity.version,"every verified original/loader image selects its exact contract");
        std::vector<unsigned char> image(4096);
        auto dos=reinterpret_cast<IMAGE_DOS_HEADER*>(image.data());dos->e_magic=IMAGE_DOS_SIGNATURE;dos->e_lfanew=128;
        auto nt=reinterpret_cast<IMAGE_NT_HEADERS64*>(image.data()+128);nt->Signature=IMAGE_NT_SIGNATURE;
        nt->FileHeader.Machine=0x8664;nt->FileHeader.TimeDateStamp=identity.timestamp;
        nt->OptionalHeader.Magic=IMAGE_NT_OPTIONAL_HDR64_MAGIC;nt->OptionalHeader.SizeOfImage=identity.imageSize;
        mp::VerifiedHostIdentity.store(&identity);
        Need(mp::LoadedNativeContract(reinterpret_cast<std::uintptr_t>(image.data()))==p,"matching mapped headers retain verified contract");
        nt->OptionalHeader.SizeOfImage+=4096;
        Need(!mp::LoadedNativeContract(reinterpret_cast<std::uintptr_t>(image.data())),"wrong mapped image size refuses before native memory access");
        nt->OptionalHeader.SizeOfImage=identity.imageSize;nt->FileHeader.TimeDateStamp++;
        Need(!mp::LoadedNativeContract(reinterpret_cast<std::uintptr_t>(image.data())),"cross-version loaded headers refuse");
    }
    mp::VerifiedHostIdentity.store(nullptr);
    Need(!mp::LoadedNativeContract(0),"null host refuses");
    std::cout<<"Version-isolated contracts, exact loaded-header admission and unknown host refusal passed\n";return 0;
}catch(std::exception const& e){std::cerr<<e.what()<<'\n';return 1;}}
