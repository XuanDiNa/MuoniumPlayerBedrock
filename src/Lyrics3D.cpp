#include "Lyrics3D.h"
#include <algorithm>
#include <cmath>
namespace mp {
Lyric3DTransform Lyric3DTransformFor(int lineOffset,float progress,int depth,int perspective){
    depth=std::clamp(depth,1,12);perspective=std::clamp(perspective,0,100);progress=std::clamp(progress,0.0f,1.0f);
    auto distance=std::abs(lineOffset);float scale=1.0f/(1.0f+static_cast<float>(distance)*depth*0.045f);
    float alpha=std::clamp(1.0f-static_cast<float>(distance)*depth*0.12f,0.10f,1.0f);
    if(lineOffset==0)alpha=std::max(alpha,0.95f);float travel=(progress-0.5f)*static_cast<float>(perspective)*0.12f;
    float y=static_cast<float>(lineOffset)*52.0f*scale+travel*(lineOffset<0?-1.0f:1.0f);
    float rotation=std::clamp(static_cast<float>(lineOffset)*perspective*0.035f,-18.0f,18.0f);
    return {scale,alpha,y,rotation};
}
}
