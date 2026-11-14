#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>
namespace mp {
enum class ClientAction {Play,Pause,Next,Previous,Seek,Stop};
struct ClientCommand {ClientAction action{};std::uint64_t generation{};std::int64_t positionMs{};};
struct ClientSnapshot {
    bool connected{},playing{},canPlay{},canPause{},canNext{},canPrevious{},canSeek{},canStop{};
    std::uint64_t generation{},revision{},coverRevision{};
    std::uint32_t errorCode{};
    std::string title,artist,album,message{"Not connected. Open NetEase CloudMusic, then Connect."};
    std::int64_t positionMs{},durationMs{};
    std::shared_ptr<std::vector<std::uint8_t> const> cover;
};
bool IsNetEaseSource(std::string_view);
bool ClientAllows(ClientSnapshot const&,ClientCommand const&);
class Client {
public:
    Client();~Client();
    void Connect();void Disconnect();
    bool Send(ClientCommand);
    ClientSnapshot Snapshot()const;
private:
    struct Impl;std::unique_ptr<Impl> impl_;
};
}
