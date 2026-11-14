#pragma once
#include "sdk/ModMenuBinding.h"
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>
namespace mp {
struct Config {
    bool enabled{true}, lyricsEnabled{true}, shuffle{false};
    bool speakerEnabled{false};
    bool worldLyricsOcclusion{false};
    bool smtcPublishEnabled{false};
    bool hudEnabled{true}, dynamicIslandEnabled{true};
    bool musicInfoEnabled{true}, musicLyricsEnabled{true}, spectrumEnabled{true}, lyricColorCustom{false};
    int infoXPercent{0}, infoYPercent{43}, infoScalePercent{100};
    int desktopLyricsXPercent{50}, desktopLyricsYPercent{87}, desktopLyricsScalePercent{85};
    int islandOffsetX{}, islandOffsetY{}, islandScalePercent{88};
    int spectrumOffsetX{}, spectrumOffsetY{}, spectrumScalePercent{100};
    int lyricColorRed{255}, lyricColorGreen{255}, lyricColorBlue{255};
    int volume{70}, lyricsFontSize{22}, lyricsOffsetY{70}, repeat{0};
    int lyricsTimeOffsetMs{};
    int lyricsMode{1};
    std::string openKey{"NONE"}, playPauseKey{"NONE"}, nextKey{"NONE"}, previousKey{"NONE"};
    std::string speakerKey{"NONE"};
    std::string miniPlayerKey{"NONE"},hudEditorKey{"NONE"},islandSearchKey{"NONE"};
};
Config ParseConfig(std::string_view);
int KeyCode(std::string_view);
std::wstring Wide(std::string_view);
std::string Utf8(std::wstring_view);
struct Word {std::int64_t at{}, duration{}; std::string text;};
struct Line {std::int64_t at{}, duration{}; std::string text; std::vector<Word> words;};
std::vector<Line> ParseLyrics(std::string_view);
std::optional<std::size_t> CurrentLine(std::vector<Line> const&, std::int64_t);
struct Track {
    std::string key, title, artist;
    std::filesystem::path local;
    std::int64_t neteaseId{};
    bool preview{};
};
class Queue {
public:
    bool Add(Track);
    bool Select(std::size_t);
    std::optional<std::size_t> Advance(int direction, bool wrap);
    Track const* Current() const;
    std::vector<Track> const& Items() const {return items_;}
    std::optional<std::size_t> Index() const {return current_;}
    void Clear();
    bool Remove(std::size_t);
private:
    std::vector<Track> items_; std::optional<std::size_t> current_;
};
std::string FormEncode(std::string_view);
struct Encrypted {std::string params, encSecKey;};
Encrypted Weapi(std::string_view plain, std::string_view deterministicKey = {});
struct Resolved {std::string url, lyrics; bool preview{};};
std::vector<Track> SearchNetEase(std::string_view);
Resolved ResolveNetEase(std::int64_t);
std::int64_t ParsePlaylistId(std::string_view);
std::vector<Track> ImportNetEasePlaylist(std::int64_t);
std::string NetEaseLyrics(std::int64_t);
std::filesystem::path DownloadMedia(std::string const&, std::filesystem::path const&, std::uint64_t);
}
