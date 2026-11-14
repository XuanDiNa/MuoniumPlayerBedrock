#pragma once
#include "Player.h"
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>
namespace mp {
constexpr UINT OpenPlayerMessage=WM_APP+42;
std::string MenuDeclaration();
std::string MenuSnapshot(bool enabled,std::uint64_t revision,std::uint64_t request=0,bool succeeded=true);
enum class JobKind {Playback,Search,Playlist,Lyrics};
struct Job {bool search{};std::uint64_t generation{};std::string query;std::int64_t id{};JobKind kind{JobKind::Playback};};
struct JobResult {
    bool search{};std::uint64_t generation{};std::vector<Track> tracks;JobKind kind{JobKind::Playback};
    std::filesystem::path path;std::string lyrics,error;bool preview{};
};
class Jobs {
public:
    Jobs(HWND,std::filesystem::path folder);
    ~Jobs();
    void Submit(Job);
    std::deque<JobResult> Take();
    void Enabled(bool);
private:
    void Run(std::stop_token);
    HWND hwnd_;std::filesystem::path folder_,cache_;
    std::mutex mutex_;std::condition_variable_any cv_;std::deque<Job> pending_;
    std::deque<JobResult> results_;bool enabled_{true};std::jthread thread_,menuThread_;
};
}
