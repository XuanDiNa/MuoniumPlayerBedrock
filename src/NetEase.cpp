#include "Player.h"
// Native adaptation of MuoniumPlayer MuWeapi.java / MuNeteaseApi.java.
// Copyright 2026 MuoniumPlayer Team. Apache-2.0. Changes: C++/BCrypt/WinHTTP,
// bounded unauthenticated operations, no account storage or platform fallback.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <bcrypt.h>
#include <wincrypt.h>
#include <winhttp.h>
#include "json.hpp"
#include <algorithm>
#include <array>
#include <fstream>
#include <memory>
#include <stdexcept>
namespace mp {
using J=nlohmann::json;
namespace {
void Crypto(NTSTATUS r){if(r<0)throw std::runtime_error("Windows cryptography failed");}
struct Algorithm {BCRYPT_ALG_HANDLE h{};~Algorithm(){if(h)BCryptCloseAlgorithmProvider(h,0);}};
struct Key {BCRYPT_KEY_HANDLE h{};~Key(){if(h)BCryptDestroyKey(h);}};
std::string B64(std::vector<unsigned char>const& data){DWORD n{};if(!CryptBinaryToStringA(data.data(),static_cast<DWORD>(data.size()),CRYPT_STRING_BASE64|CRYPT_STRING_NOCRLF,nullptr,&n))throw std::runtime_error("Base64 failed");std::string s(n,'\0');if(!CryptBinaryToStringA(data.data(),static_cast<DWORD>(data.size()),CRYPT_STRING_BASE64|CRYPT_STRING_NOCRLF,s.data(),&n))throw std::runtime_error("Base64 failed");s.resize(n);return s;}
std::string Aes(std::string_view input,std::string_view secret){
    Algorithm alg;Crypto(BCryptOpenAlgorithmProvider(&alg.h,BCRYPT_AES_ALGORITHM,nullptr,0));
    Crypto(BCryptSetProperty(alg.h,BCRYPT_CHAINING_MODE,reinterpret_cast<PUCHAR>(const_cast<wchar_t*>(BCRYPT_CHAIN_MODE_CBC)),sizeof(BCRYPT_CHAIN_MODE_CBC),0));
    Key key;Crypto(BCryptGenerateSymmetricKey(alg.h,&key.h,nullptr,0,reinterpret_cast<PUCHAR>(const_cast<char*>(secret.data())),static_cast<ULONG>(secret.size()),0));
    std::array<unsigned char,16> iv;std::copy_n("0102030405060708",16,iv.begin());ULONG n{};
    Crypto(BCryptEncrypt(key.h,reinterpret_cast<PUCHAR>(const_cast<char*>(input.data())),static_cast<ULONG>(input.size()),nullptr,iv.data(),16,nullptr,0,&n,BCRYPT_BLOCK_PADDING));
    std::vector<unsigned char> out(n);std::copy_n("0102030405060708",16,iv.begin());
    Crypto(BCryptEncrypt(key.h,reinterpret_cast<PUCHAR>(const_cast<char*>(input.data())),static_cast<ULONG>(input.size()),nullptr,iv.data(),16,out.data(),n,&n,BCRYPT_BLOCK_PADDING));out.resize(n);return B64(out);
}
std::vector<unsigned char> Hex(std::string_view hex){std::vector<unsigned char> out;auto n=[](char c){return c>='0'&&c<='9'?c-'0':c-'a'+10;};for(std::size_t i=0;i<hex.size();i+=2)out.push_back(static_cast<unsigned char>((n(hex[i])<<4)|n(hex[i+1])));return out;}
std::string Rsa(std::string_view key){
    auto modulus=Hex("e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7");
    BCRYPT_RSAKEY_BLOB header{BCRYPT_RSAPUBLIC_MAGIC,1024,3,128,0,0};std::vector<unsigned char> blob(sizeof header);std::memcpy(blob.data(),&header,sizeof header);blob.insert(blob.end(),{1,0,1});blob.insert(blob.end(),modulus.begin(),modulus.end());
    Algorithm alg;Crypto(BCryptOpenAlgorithmProvider(&alg.h,BCRYPT_RSA_ALGORITHM,nullptr,0));Key pub;Crypto(BCryptImportKeyPair(alg.h,nullptr,BCRYPT_RSAPUBLIC_BLOB,&pub.h,blob.data(),static_cast<ULONG>(blob.size()),0));
    std::array<unsigned char,128> input{},out{};std::reverse_copy(key.begin(),key.end(),input.end()-key.size());ULONG n{};
    Crypto(BCryptEncrypt(pub.h,input.data(),128,nullptr,nullptr,0,out.data(),128,&n,BCRYPT_PAD_NONE));
    constexpr char digits[]="0123456789abcdef";std::string result;for(auto b:out){result+=digits[b>>4];result+=digits[b&15];}return result;
}
struct Internet {HINTERNET h{};Internet()=default;explicit Internet(HINTERNET x):h(x){};Internet(Internet const&)=delete;~Internet(){if(h)WinHttpCloseHandle(h);}};
struct Url {std::wstring host,path;INTERNET_PORT port{};bool secure{};};
Url Crack(std::string const& s){
    if(s.size()>8192||s.find_first_of("\r\n")!=s.npos)throw std::runtime_error("Invalid media URL");auto w=Wide(s);URL_COMPONENTS c{};c.dwStructSize=sizeof c;c.dwHostNameLength=c.dwUrlPathLength=c.dwExtraInfoLength=c.dwUserNameLength=c.dwPasswordLength=DWORD(-1);
    if(!WinHttpCrackUrl(w.c_str(),static_cast<DWORD>(w.size()),0,&c)||c.nScheme!=INTERNET_SCHEME_HTTPS||c.dwUserNameLength||c.dwPasswordLength)throw std::runtime_error("HTTPS URL required");
    Url out;out.host.assign(c.lpszHostName,c.dwHostNameLength);out.path.assign(c.lpszUrlPath,c.dwUrlPathLength);if(c.dwExtraInfoLength)out.path.append(c.lpszExtraInfo,c.dwExtraInfoLength);out.port=c.nPort;out.secure=true;return out;
}
bool MediaHost(std::string const& url){auto u=Crack(url);std::transform(u.host.begin(),u.host.end(),u.host.begin(),::towlower);return u.port==443&&(u.host==L"music.163.com"||u.host.ends_with(L".music.126.net")||u.host.ends_with(L".music.127.net")||u.host.ends_with(L".music.163.com"));}
std::vector<unsigned char> Request(std::string const& url,std::string const& body,std::size_t limit){
    auto u=Crack(url);Internet session(WinHttpOpen(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,WINHTTP_NO_PROXY_NAME,WINHTTP_NO_PROXY_BYPASS,0));if(!session.h)throw std::runtime_error("HTTP session unavailable");WinHttpSetTimeouts(session.h,5000,5000,10000,10000);
    Internet conn(WinHttpConnect(session.h,u.host.c_str(),u.port,0));if(!conn.h)throw std::runtime_error("HTTP connect failed");
    Internet req(WinHttpOpenRequest(conn.h,body.empty()?L"GET":L"POST",u.path.c_str(),nullptr,WINHTTP_NO_REFERER,WINHTTP_DEFAULT_ACCEPT_TYPES,WINHTTP_FLAG_SECURE));if(!req.h)throw std::runtime_error("HTTP request failed");
    DWORD noRedirect=WINHTTP_OPTION_REDIRECT_POLICY_NEVER;WinHttpSetOption(req.h,WINHTTP_OPTION_REDIRECT_POLICY,&noRedirect,sizeof noRedirect);
    wchar_t const* headers=L"Content-Type: application/x-www-form-urlencoded\r\nReferer: https://music.163.com/\r\nOrigin: https://music.163.com\r\nCookie: os=pc; appver=8.9.70\r\n";
    if(!WinHttpSendRequest(req.h,headers,DWORD(-1),body.empty()?WINHTTP_NO_REQUEST_DATA:const_cast<char*>(body.data()),static_cast<DWORD>(body.size()),static_cast<DWORD>(body.size()),0)||!WinHttpReceiveResponse(req.h,nullptr))throw std::runtime_error("Network request failed or timed out");
    DWORD status{},bytes=sizeof status;if(!WinHttpQueryHeaders(req.h,WINHTTP_QUERY_STATUS_CODE|WINHTTP_QUERY_FLAG_NUMBER,WINHTTP_HEADER_NAME_BY_INDEX,&status,&bytes,WINHTTP_NO_HEADER_INDEX)||status!=200)throw std::runtime_error("Music platform refused request (HTTP "+std::to_string(status)+")");
    std::vector<unsigned char> data;std::array<unsigned char,32768> buffer;ULONGLONG deadline=GetTickCount64()+90000;
    for(;;){if(GetTickCount64()>deadline)throw std::runtime_error("Media request exceeded time limit");DWORD read{};if(!WinHttpReadData(req.h,buffer.data(),static_cast<DWORD>(buffer.size()),&read))throw std::runtime_error("Network read failed");if(!read)break;if(read>limit-data.size())throw std::runtime_error("Network response exceeds limit");data.insert(data.end(),buffer.begin(),buffer.begin()+read);}return data;
}
J Api(std::string_view endpoint,J const& params){auto enc=Weapi(params.dump());auto body="params="+FormEncode(enc.params)+"&encSecKey="+enc.encSecKey;auto raw=Request("https://music.163.com"+std::string(endpoint),body,2*1024*1024);auto j=J::parse(raw);if(!j.is_object()||j.value("code",0)!=200)throw std::runtime_error("Music platform returned code "+std::to_string(j.is_object()?j.value("code",0):0));return j;}
J PublicApi(std::string const& endpoint,J const& params,bool post=false){std::string form;for(auto i=params.begin();i!=params.end();++i){if(!form.empty())form+='&';form+=FormEncode(i.key())+"="+FormEncode(i.value().is_string()?i.value().get<std::string>():i.value().dump());}auto raw=Request("https://music.163.com"+endpoint+(post?"":"?"+form),post?form:std::string{},2*1024*1024);auto j=J::parse(raw);if(!j.is_object()||j.value("code",0)!=200)throw std::runtime_error("Music platform returned code "+std::to_string(j.is_object()?j.value("code",0):0));return j;}
std::string Str(J const& j,char const*k){auto i=j.find(k);return i!=j.end()&&i->is_string()?i->get<std::string>():std::string{};}
}
std::string FormEncode(std::string_view s){constexpr char h[]="0123456789ABCDEF";std::string out;for(unsigned char c:s){if((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'||c=='.'||c=='*')out+=char(c);else if(c==' ')out+='+';else{out+='%';out+=h[c>>4];out+=h[c&15];}}return out;}
Encrypted Weapi(std::string_view plain,std::string_view fixed){
    if(plain.size()>65536)throw std::runtime_error("Protocol payload exceeds limit");std::string secret(fixed);
    if(secret.empty()){constexpr char chars[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";while(secret.size()<16){unsigned char b;Crypto(BCryptGenRandom(nullptr,&b,1,BCRYPT_USE_SYSTEM_PREFERRED_RNG));if(b<248)secret+=chars[b%62];}}
    if(secret.size()!=16||secret.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")!=secret.npos)throw std::runtime_error("Invalid protocol key");
    return {Aes(Aes(plain,"0CoJUm6Qyw8W8jud"),secret),Rsa(secret)};
}
std::vector<Track> SearchNetEase(std::string_view query){
    if(query.empty()||query.size()>256)throw std::runtime_error("Search requires 1-256 UTF-8 bytes");Wide(query);
    auto j=PublicApi("/api/search/get/web",{{"s",query},{"type","1"},{"limit","30"},{"offset","0"},{"total","true"}},true);
    std::vector<Track> out;if(!j.contains("result")||!j["result"].contains("songs"))return out;
    for(auto const& song:j["result"]["songs"]){if(out.size()>=30)break;if(!song.contains("id")||!song["id"].is_number_integer())continue;auto id=song["id"].get<std::int64_t>();auto title=Str(song,"name");if(id<=0||title.empty()||title.size()>512)continue;std::string artists;
        auto a=song.find("ar");if(a==song.end())a=song.find("artists");if(a!=song.end()&&a->is_array())for(auto const& artist:*a){auto name=Str(artist,"name");if(artists.size()+name.size()>512)break;if(!artists.empty())artists+=" / ";artists+=name;}
        out.push_back({"netease-"+std::to_string(id),title,artists,{},id});}return out;
}
Resolved ResolveNetEase(std::int64_t id){
    if(id<=0)throw std::runtime_error("Invalid track ID");auto j=PublicApi("/api/song/enhance/player/url",{{"ids","["+std::to_string(id)+"]"},{"br","128000"}});
    if(!j.contains("data")||!j["data"].is_array()||j["data"].empty())throw std::runtime_error("No playable stream available");auto const& d=j["data"][0];auto url=Str(d,"url");if(url.empty())throw std::runtime_error("Track unavailable without account or playback rights");if(url.starts_with("http://"))url.replace(0,7,"https://");if(!MediaHost(url))throw std::runtime_error("Unreviewed media host refused");
    Resolved out{url,{},d.contains("freeTrialInfo")&&!d["freeTrialInfo"].is_null()};
    try{auto lyrics=PublicApi("/api/song/lyric",{{"id",id},{"lv",-1},{"kv",-1},{"tv",-1},{"yv",-1}});if(lyrics.contains("yrc"))out.lyrics=Str(lyrics["yrc"],"lyric");if(out.lyrics.empty()&&lyrics.contains("lrc"))out.lyrics=Str(lyrics["lrc"],"lyric");if(out.lyrics.size()>524288)out.lyrics.clear();}catch(...){/* Playback remains usable without lyrics. */}
    return out;
}
std::string NetEaseLyrics(std::int64_t id){
    if(id<=0)throw std::runtime_error("Invalid song ID");auto j=PublicApi("/api/song/lyric",{{"id",id},{"lv",-1},{"kv",-1},{"tv",-1},{"yv",-1}});std::string text;if(j.contains("yrc"))text=Str(j["yrc"],"lyric");if(text.empty()&&j.contains("lrc"))text=Str(j["lrc"],"lyric");if(text.size()>524288)throw std::runtime_error("Lyrics exceed limit");return text;
}
std::vector<Track> ImportNetEasePlaylist(std::int64_t id){
    if(id<=0)throw std::runtime_error("Invalid playlist ID");auto j=PublicApi("/api/v6/playlist/detail",{{"id",id},{"n",256},{"s",0}});std::vector<Track> out;
    if(!j.contains("playlist")||!j["playlist"].contains("tracks")||!j["playlist"]["tracks"].is_array())throw std::runtime_error("Public playlist unavailable");
    for(auto const&s:j["playlist"]["tracks"]){if(out.size()>=256)break;auto sid=s.value("id",std::int64_t{});auto name=Str(s,"name");if(sid<=0||name.empty()||name.size()>1024)continue;std::string artist;if(s.contains("ar")&&s["ar"].is_array())for(auto const&a:s["ar"]){auto n=Str(a,"name");if(artist.size()+n.size()>1000)break;if(!artist.empty())artist+=" / ";artist+=n;}out.push_back({"netease-"+std::to_string(sid),name,artist,{},sid});}return out;
}
std::filesystem::path DownloadMedia(std::string const& url,std::filesystem::path const& folder,std::uint64_t generation){
    if(!MediaHost(url))throw std::runtime_error("Unreviewed media host refused");auto data=Request(url,{},128*1024*1024);if(data.empty())throw std::runtime_error("Empty media stream");std::filesystem::create_directories(folder);
    auto path=folder/("track-"+std::to_string(generation)+".media");if(std::filesystem::exists(path))throw std::runtime_error("Cache destination already exists");
    try{std::ofstream f(path,std::ios::binary);if(!f)throw std::runtime_error("Cannot create playback cache");f.write(reinterpret_cast<char*>(data.data()),data.size());f.close();if(!f)throw std::runtime_error("Cannot finish playback cache");}catch(...){std::error_code ec;std::filesystem::remove(path,ec);throw;}return path;
}
}
