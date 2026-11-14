#pragma once
#include "Player.h"
namespace mp {
struct Library {
    Queue queue;std::vector<Track> favorites;std::vector<Track> playlist;std::int64_t playlistId{};bool shuffle{};
    bool IsFavorite(std::string_view key)const;
    bool ToggleFavorite(Track const&);
};
bool ReplacePlaylist(Library&,std::int64_t,std::vector<Track>);
std::optional<std::size_t> ShuffleIndex(std::size_t,std::optional<std::size_t>,std::uint64_t random);
class ShuffleSequence {
public:
    void Reset(std::size_t,std::optional<std::size_t>,std::uint64_t seed);
    std::optional<std::size_t> Advance(int direction,bool wrap,std::uint64_t seed);
    std::optional<std::size_t> Current()const{return cursor_?std::optional(order_[*cursor_]):std::nullopt;}
    std::size_t Size()const{return order_.size();}
private:
    std::vector<std::size_t> order_;std::optional<std::size_t> cursor_;
};
std::string SerializeLibrary(Library const&);
Library ParseLibrary(std::string_view);
// Missing file is an empty library. Existing malformed/reparse files are never overwritten.
Library ReadLibrary(std::filesystem::path const&);
void WriteLibrary(std::filesystem::path const&,Library const&);
}
