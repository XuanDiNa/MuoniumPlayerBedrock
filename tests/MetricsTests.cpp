#include "GameMetrics.h"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
int main(){
    auto need=[](bool ok){if(!ok)throw std::runtime_error("metrics behavior");};
    mp::GameFrameCounter f;
    need(f.frame(0)==-1);
    for(int i=1;i<=60;++i)f.frame(i/60.0);
    need(f.value()==60);
    // Long pauses never become a low-FPS interval.
    need(f.frame(5)==-1);
    for(int i=1;i<=30;++i)f.frame(5+i/30.0);
    need(f.value()==30);
    need(f.frame(std::numeric_limits<double>::quiet_NaN())==-1);
    f.frame(10);need(f.frame(9)==-1);
    mp::ConnectionMetrics c;
    need(c.snapshot(100,1).ping==-1);
    c.observe(1,10,true,-1,100);
    need(c.snapshot(101,1).ping==0&&c.snapshot(101,1).kind==1);
    need(c.snapshot(101,2).ping==-1);
    c.observe(2,20,false,-1,200);need(c.snapshot(201,2).ping==-1);
    c.observe(2,20,false,52,250);need(c.snapshot(251,2).ping==52);
    c.observe(2,20,false,95,750);need(c.snapshot(751,2).ping==95);
    need(c.snapshot(4001,2).ping==-1);need(c.snapshot(700,2).ping==-1);
    c.observe(2,21,false,-1,800);need(c.snapshot(801,2).ping==-1);
    c.observe(2,21,false,0,850);need(c.snapshot(851,2).ping==0&&c.snapshot(851,2).kind==2);
    c.observe(2,21,false,65535,900);need(c.snapshot(901,2).ping==-1);
    c.reset();need(c.snapshot(901,2).ping==-1);
    std::cout<<"FPS cadence/pause/invalid time, RTT local/remote/freshness/session/disconnect passed\n";
}
