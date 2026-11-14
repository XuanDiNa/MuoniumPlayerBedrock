#pragma once
#include <cstdint>
namespace mp {
struct Lyric3DTransform {
    float scale{}, alpha{}, y{}, rotation{};
};
// Screen-space client overlay transform. It never writes world or server state.
Lyric3DTransform Lyric3DTransformFor(int lineOffset,float progress,int depth,int perspective);
}
