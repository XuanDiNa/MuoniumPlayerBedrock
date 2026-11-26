#include "Runtime.h"
#include "HostEvidence.h"
#include "HostEvidenceReference.h"
#include <bcrypt.h>
#include <json.hpp>
#include <algorithm>
#include <array>
#include <cstring>
#include <vector>

namespace mp { namespace {
using Json=nlohmann::json;
constexpr std::uint64_t MaxFile=512ull*1024*1024;
struct Failure {char const* reason;};
void Require(bool ok,char const* reason){if(!ok)throw Failure{reason};}
struct File {
    HANDLE value{INVALID_HANDLE_VALUE};
    ~File(){if(value!=INVALID_HANDLE_VALUE)CloseHandle(value);}
};
struct Hasher {
    BCRYPT_ALG_HANDLE alg{};BCRYPT_HASH_HANDLE hash{};
    Hasher(){
        Require(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0,"hash-provider-failed");
        if(BCryptCreateHash(alg,&hash,nullptr,0,nullptr,0,0)<0){BCryptCloseAlgorithmProvider(alg,0);alg=nullptr;throw Failure{"hash-create-failed"};}
    }
    ~Hasher(){if(hash)BCryptDestroyHash(hash);if(alg)BCryptCloseAlgorithmProvider(alg,0);}
    void update(unsigned char* bytes,DWORD count){Require(BCryptHashData(hash,bytes,count,0)>=0,"hash-update-failed");}
    std::string finish(){
        std::array<unsigned char,32> digest{};
        Require(BCryptFinishHash(hash,digest.data(),32,0)>=0,"hash-finish-failed");
        constexpr char hex[]="0123456789ABCDEF";std::string result;
        for(auto byte:digest){result+=hex[byte>>4];result+=hex[byte&15];}return result;
    }
};
class Image {
    File file;
public:
    std::uint64_t size{};
    IMAGE_NT_HEADERS64 nt{};
    std::vector<IMAGE_SECTION_HEADER> sections;
    explicit Image(std::filesystem::path const& path){
        file.value=CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
        Require(file.value!=INVALID_HANDLE_VALUE,"open-failed");
        LARGE_INTEGER length{};Require(GetFileSizeEx(file.value,&length)!=FALSE,"size-read-failed");
        Require(length.QuadPart>=static_cast<LONGLONG>(sizeof(IMAGE_DOS_HEADER))&&length.QuadPart<=MaxFile,"size-out-of-bounds");
        size=length.QuadPart;
        IMAGE_DOS_HEADER dos{};read(0,&dos,sizeof(dos));
        Require(dos.e_magic==IMAGE_DOS_SIGNATURE&&dos.e_lfanew>=64&&dos.e_lfanew<=4096,"invalid-dos-header");
        read(dos.e_lfanew,&nt,sizeof(nt));
        Require(nt.Signature==IMAGE_NT_SIGNATURE&&nt.OptionalHeader.Magic==IMAGE_NT_OPTIONAL_HDR64_MAGIC,"invalid-pe-header");
        Require(nt.FileHeader.SizeOfOptionalHeader==sizeof(IMAGE_OPTIONAL_HEADER64)&&
            nt.FileHeader.NumberOfSections>0&&nt.FileHeader.NumberOfSections<=96&&
            nt.OptionalHeader.NumberOfRvaAndSizes<=IMAGE_NUMBEROF_DIRECTORY_ENTRIES,"pe-limits-exceeded");
        auto table=static_cast<std::uint64_t>(dos.e_lfanew)+24+nt.FileHeader.SizeOfOptionalHeader;
        auto headerEnd=table+sizeof(IMAGE_SECTION_HEADER)*nt.FileHeader.NumberOfSections;
        Require(headerEnd<=nt.OptionalHeader.SizeOfHeaders&&nt.OptionalHeader.SizeOfHeaders<=size&&
            nt.OptionalHeader.SizeOfImage>0&&nt.OptionalHeader.SizeOfImage<=MaxFile,"invalid-image-bounds");
        sections.resize(nt.FileHeader.NumberOfSections);read(table,sections.data(),sections.size()*sizeof(IMAGE_SECTION_HEADER));
        for(auto const& s:sections){
            Require(static_cast<std::uint64_t>(s.PointerToRawData)+s.SizeOfRawData<=size,"section-file-bounds");
            Require(!s.SizeOfRawData||s.PointerToRawData>=nt.OptionalHeader.SizeOfHeaders,"section-header-overlap");
            Require(static_cast<std::uint64_t>(s.VirtualAddress)+std::max(s.Misc.VirtualSize,s.SizeOfRawData)<=nt.OptionalHeader.SizeOfImage,"section-image-bounds");
        }
        for(std::size_t i=0;i<sections.size();++i)for(std::size_t j=i+1;j<sections.size();++j){
            auto const& a=sections[i];auto const& b=sections[j];
            Require(!a.SizeOfRawData||!b.SizeOfRawData||static_cast<std::uint64_t>(a.PointerToRawData)+a.SizeOfRawData<=b.PointerToRawData||
                static_cast<std::uint64_t>(b.PointerToRawData)+b.SizeOfRawData<=a.PointerToRawData,"overlapping-file-sections");
            auto av=std::max(a.SizeOfRawData,a.Misc.VirtualSize),bv=std::max(b.SizeOfRawData,b.Misc.VirtualSize);
            Require(!av||!bv||static_cast<std::uint64_t>(a.VirtualAddress)+av<=b.VirtualAddress||
                static_cast<std::uint64_t>(b.VirtualAddress)+bv<=a.VirtualAddress,"overlapping-image-sections");
        }
    }
    void read(std::uint64_t offset,void* buffer,std::size_t length){
        Require(offset<=size&&length<=size-offset&&length<=MAXDWORD,"read-bounds");
        LARGE_INTEGER at{};at.QuadPart=offset;
        Require(SetFilePointerEx(file.value,at,nullptr,FILE_BEGIN)!=FALSE,"seek-failed");
        DWORD count{};Require(ReadFile(file.value,buffer,static_cast<DWORD>(length),&count,nullptr)&&count==length,"read-failed");
    }
    std::string digest(std::uint64_t offset,std::uint64_t length){
        Require(offset<=size&&length<=size-offset,"hash-bounds");
        Hasher h;std::vector<unsigned char> buffer(1024*1024);
        for(std::uint64_t done=0;done<length;){
            auto count=static_cast<DWORD>(std::min<std::uint64_t>(buffer.size(),length-done));
            read(offset+done,buffer.data(),count);h.update(buffer.data(),count);done+=count;
        }return h.finish();
    }
    bool offset(std::uint64_t rva,std::uint64_t length,std::uint64_t& result)const{
        for(auto const& s:sections)if(rva>=s.VirtualAddress){
            auto delta=rva-s.VirtualAddress;
            if(delta<=s.SizeOfRawData&&length<=s.SizeOfRawData-delta){result=s.PointerToRawData+delta;return true;}
        }return false;
    }
    void stable(){LARGE_INTEGER now{};Require(GetFileSizeEx(file.value,&now)&&static_cast<std::uint64_t>(now.QuadPart)==size,"size-changed");}
};
std::string SectionName(IMAGE_SECTION_HEADER const& s){
    std::string name;for(auto c:s.Name){if(!c)break;name+=c>=33&&c<=126?static_cast<char>(c):'?';}return name;
}
Json Sections(Image& image){
    Json rows=Json::array();for(auto const& s:image.sections)rows.push_back({{"name",SectionName(s)},
        {"rva",s.VirtualAddress},{"virtualSize",s.Misc.VirtualSize},{"rawOffset",s.PointerToRawData},
        {"rawSize",s.SizeOfRawData},{"characteristics",s.Characteristics},{"sha256",image.digest(s.PointerToRawData,s.SizeOfRawData)}});
    return rows;
}
Json CompareSections(Json const& actual,Json const& expected,bool& all){
    Json result=Json::array();all=true;
    for(auto const& row:expected){
        auto match=std::count(actual.begin(),actual.end(),row)==1;
        all&=match;result.push_back({{"name",row.at("name")},{"match",match},{"expectedSha256",row.at("sha256")}});
    }return result;
}
Json CompareRegions(Image& image,Json const& reference,bool& all){
    Json rows=Json::array();all=true;
    for(auto row:reference){
        std::uint64_t offset{};bool mapped=image.offset(row.at("rva"),row.at("bytes"),offset);
        std::string digest=mapped?image.digest(offset,row.at("bytes")):"";
        bool match=mapped&&digest==row.at("sha256").get<std::string>();all&=match;
        row["expectedSha256"]=row["sha256"];row["sha256"]=digest;row["mapped"]=mapped;row["match"]=match;
        rows.push_back(std::move(row));
    }return rows;
}
Json Imports(Image& image,bool& complete){
    Json result=Json::array();complete=true;
    if(image.nt.OptionalHeader.NumberOfRvaAndSizes<=IMAGE_DIRECTORY_ENTRY_IMPORT)return result;
    auto const& dir=image.nt.OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if(!dir.VirtualAddress&&!dir.Size)return result;
    for(std::uint64_t i=0;i<256;++i){
        auto delta=i*sizeof(IMAGE_IMPORT_DESCRIPTOR);std::uint64_t offset{};
        if(delta+sizeof(IMAGE_IMPORT_DESCRIPTOR)>dir.Size||!image.offset(static_cast<std::uint64_t>(dir.VirtualAddress)+delta,sizeof(IMAGE_IMPORT_DESCRIPTOR),offset)){complete=false;break;}
        IMAGE_IMPORT_DESCRIPTOR desc{};image.read(offset,&desc,sizeof(desc));
        if(!desc.OriginalFirstThunk&&!desc.TimeDateStamp&&!desc.ForwarderChain&&!desc.Name&&!desc.FirstThunk)return result;
        std::string name;bool terminated=false,valid=true;
        for(std::uint64_t k=0;k<128;++k){
            if(!image.offset(static_cast<std::uint64_t>(desc.Name)+k,1,offset)){valid=false;break;}
            unsigned char c{};image.read(offset,&c,1);if(!c){terminated=true;break;}
            if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c=='-')){valid=false;break;}
            name+=static_cast<char>(c);
        }
        if(!valid||!terminated||name.empty()||name.find("..")!=std::string::npos){complete=false;break;}
        result.push_back(name);
    }complete=false;return result;
}
void CheckFolder(std::filesystem::path const& folder){
    for(auto p=std::filesystem::absolute(folder).lexically_normal();;p=p.parent_path()){
        auto flags=GetFileAttributesW(p.c_str());
        Require(flags!=INVALID_FILE_ATTRIBUTES&&(flags&FILE_ATTRIBUTE_DIRECTORY)&&!(flags&FILE_ATTRIBUTE_REPARSE_POINT),"unsafe-report-folder");
        if(p==p.parent_path())break;
    }
}
std::string Save(std::filesystem::path const& folder,std::string const& report){
    CheckFolder(folder);auto logs=folder/L"logs";
    if(!CreateDirectoryW(logs.c_str(),nullptr))Require(GetLastError()==ERROR_ALREADY_EXISTS,"report-folder-failed");
    CheckFolder(logs);SYSTEMTIME t{};GetLocalTime(&t);wchar_t name[160]{};
    swprintf_s(name,L"Muonium-host-evidence-%04u%02u%02u-%02u%02u%02u-%llu-%lu.json",t.wYear,t.wMonth,t.wDay,
        t.wHour,t.wMinute,t.wSecond,GetTickCount64(),GetCurrentProcessId());
    File output;output.value=CreateFileW((logs/name).c_str(),GENERIC_WRITE|DELETE,FILE_SHARE_READ,nullptr,CREATE_NEW,
        FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
    Require(output.value!=INVALID_HANDLE_VALUE,"report-create-failed");DWORD count{};
    bool ok=WriteFile(output.value,report.data(),static_cast<DWORD>(report.size()),&count,nullptr)&&count==report.size()&&FlushFileBuffers(output.value);
    if(!ok){FILE_DISPOSITION_INFO disposition{TRUE};SetFileInformationByHandle(output.value,FileDispositionInfo,&disposition,sizeof(disposition));}
    Require(ok,"report-write-failed");return std::filesystem::path(name).string();
}
} // namespace
HostEvidenceResult SaveHostEvidence(std::filesystem::path const& exe,std::filesystem::path const& folder,std::string const& expectedSha) noexcept{
    HostEvidenceResult result;
    try{
        Require(expectedSha.size()==64&&std::all_of(expectedSha.begin(),expectedSha.end(),[](char c){return (c>='0'&&c<='9')||(c>='A'&&c<='F');}),"expected-digest-invalid");
        Image image(exe);auto reference=Json::parse(HostEvidenceReference);auto const& nt=image.nt;
        bool target=nt.FileHeader.Machine==reference.at("machine")&&nt.FileHeader.TimeDateStamp==reference.at("timestamp")&&
            nt.OptionalHeader.ImageBase==reference.at("imageBase");
        Json report={{"schema",1},{"diagnosticOnly",true},{"nativeAuthorized",false},
            {"target2660",target},{"nativeEquivalenceConfirmed",false},{"comparisonReference",reference.at("originalSha256")},
            {"file",{{"bytes",image.size},{"sha256",expectedSha}}},
            {"pe",{{"machine",nt.FileHeader.Machine},{"timestamp",nt.FileHeader.TimeDateStamp},
                {"imageSize",nt.OptionalHeader.SizeOfImage},{"imageBase",nt.OptionalHeader.ImageBase},
                {"entryRva",nt.OptionalHeader.AddressOfEntryPoint},{"subsystem",nt.OptionalHeader.Subsystem},
                {"checksum",nt.OptionalHeader.CheckSum},{"headerSize",nt.OptionalHeader.SizeOfHeaders}}}};
        auto sections=Sections(image);report["sections"]=sections;
        auto headerDigest=image.digest(0,nt.OptionalHeader.SizeOfHeaders);
        report["headerSha256"]=headerDigest;report["loaderHeaderMatch"]=headerDigest==reference.at("loaderHeaderSha256").get<std::string>();
        // Hash the gaps and trailing certificate/overlay region as well. These
        // fingerprints describe differences without exporting their content.
        std::vector<std::pair<std::uint64_t,std::uint64_t>> ranges;
        for(auto const& s:image.sections)if(s.SizeOfRawData)ranges.emplace_back(s.PointerToRawData,s.SizeOfRawData);
        std::sort(ranges.begin(),ranges.end());std::uint64_t cursor=nt.OptionalHeader.SizeOfHeaders;
        Json gaps=Json::array();
        for(auto const& [offset,length]:ranges){
            if(offset>cursor)gaps.push_back({{"offset",cursor},{"bytes",offset-cursor},{"sha256",image.digest(cursor,offset-cursor)}});
            cursor=offset+length;
        }
        if(cursor<image.size)gaps.push_back({{"offset",cursor},{"bytes",image.size-cursor},{"sha256",image.digest(cursor,image.size-cursor)}});
        report["unsectionedRegions"]=gaps;
        Json dirs=Json::array();for(DWORD i=0;i<nt.OptionalHeader.NumberOfRvaAndSizes;++i){auto const& d=nt.OptionalHeader.DataDirectory[i];dirs.push_back({{"index",i},{"rva",d.VirtualAddress},{"size",d.Size}});}
        report["directories"]=dirs;report["loaderDirectoriesMatch"]=dirs==reference.at("loaderDirectories");
        bool importsComplete{};report["imports"]=Imports(image,importsComplete);report["importsComplete"]=importsComplete;
        bool originals{},loader{};report["originalSections"]=CompareSections(sections,reference.at("sections"),originals);
        report["loaderSections"]=CompareSections(sections,reference.at("loaderSections"),loader);report["loaderSectionsMatch"]=loader;
        bool guards=false,bodies=false,slots=false;
        for(auto key:{"guards","bodies","slots"})report[key]=Json::array();
        if(target){
            report["guards"]=CompareRegions(image,reference.at("guards"),guards);
            report["bodies"]=CompareRegions(image,reference.at("bodies"),bodies);
            report["slots"]=CompareRegions(image,reference.at("slots"),slots);
        }
        result.nativeEquivalent=target&&originals&&guards&&bodies&&slots;
        report["nativeEquivalenceConfirmed"]=result.nativeEquivalent;
        // The same read-only handle is held throughout. This final full-file
        // digest binds all regional evidence to the host-check diagnostic.
        Require(image.digest(0,image.size)==expectedSha,"file-digest-changed");image.stable();
        auto encoded=report.dump(2)+"\n";Require(encoded.size()<=128*1024,"report-too-large");
        result.filename=Save(folder,encoded);result.saved=true;result.reason="saved";
    }catch(Failure const& e){result.reason=e.reason;result.nativeEquivalent=false;}
    catch(...){result.reason="unexpected-error";result.nativeEquivalent=false;}
    return result;
}
} // namespace mp
