"""Passive room-voice controls; no server addon or automatic microphone activation."""
def apply_model(text):
    assert text.count('        ACTION\n')==1
    text=text.replace('        ACTION\n','        INFO,\n        ACTION\n')
    assert text.count('        public final Kind kind;')==1
    text=text.replace('        public final Kind kind;','''        private Supplier<String[]> liveChoices;
        public Entry liveChoices(Supplier<String[]> source) {liveChoices=source;return this;}
        public final Kind kind;''')
    assert text.count('        public String[] getChoices() {')==1
    text=text.replace('        public String[] getChoices() {','''        public String[] getChoices() {
            if(liveChoices!=null)return liveChoices.get();''')
    anchor='                ,new Category("host-tools",'
    assert text.count(anchor)==1
    controls='''                ,new Category("voice-broadcast", "语音与音乐广播", "纯客户端 · 加入一起听直连／公网房间 · 无需服务器插件", 0xFF65C9C2, HudSettingsIcons.MODERN_UI,
                        new Group("房间语音", HudSettingsIcons.MODERN_UI,
                                toggle("开启房间语音", "默认关闭；开启后接收同房间语音。关闭页面或暂时失焦不会断开。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().enabled(),
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setEnabled(!v.enabled());}),
                                toggle("开启麦克风", "默认关闭；需同时开启房间语音并加入房间才收音。建议使用耳机，未提供回声消除。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().microphone(),
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setMicrophone(!v.microphone());}),
                                choice("麦克风增益", "只放大麦克风。100%为原音量，收音较远可先试200%；也会放大环境噪声。即时生效并保存。",
                                        () -> Math.round(com.muoniumplayer.bedrock.RoomVoice.global().microphoneGain()*100)+"%",
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setMicrophoneGain(v.microphoneGain()>=4?0:Math.min(4,v.microphoneGain()+.5f));},
                                        new String[]{"0%","50%","100%","150%","200%","250%","300%","350%","400%"},
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().setMicrophoneGain(Float.parseFloat(value.replace("%",""))/100)),
                                toggle("拒听", "暂停收听、麦克风及音乐送出；解除后恢复原开关。快捷键可在 ModMenuBE 普通设置绑定。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().deafened(),
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().toggleDeafened()),
                                toggle("距离音讯", "双方在同一游戏世界开启：远近音量、左右方向和隔墙闷音。默认关闭；房间模式保留。坐标仅在距离模式传送。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().spatial(),
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setSpatial(!v.spatial());}),
                                action("重新启用当前世界", "距离模式要求双方位于同一世界；无法自动核对服务器。切换世界后必须重新点击，开页不会自行确认。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().confirmSpatialWorld()),
                                choice("传送距离", "仅影响自己送出的距离音讯；超出范围无声。未知方块区域保守降低音量。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().spatialRange()+" 格",
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setSpatialRange(v.spatialRange()>=64?8:v.spatialRange()*2);},
                                        new String[]{"8 格","16 格","32 格","64 格"},
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().setSpatialRange(Integer.parseInt(value.replace(" 格","")))),
                                choice("麦克风输入装置", "默认跟随系统；选择装置不会自动开麦。装置不可用时不会偷偷切到其他麦克风。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().deviceLabel(true),
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().nextDevice(true),null,
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().selectDevice(true,value))
                                        .liveChoices(() -> com.muoniumplayer.bedrock.RoomVoice.global().deviceOptions(true)),
                                choice("耳机／喇叭输出装置", "只调整房间语音的输出装置，不改变本机音乐播放器。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().deviceLabel(false),
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().nextDevice(false),null,
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().selectDevice(false,value))
                                        .liveChoices(() -> com.muoniumplayer.bedrock.RoomVoice.global().deviceOptions(false)),
                                action("刷新音频装置", "插拔耳机或麦克风后重新列出；不会开启收音。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().refreshDevices()),
                                toggle("广播当前音乐", "默认关闭；只发送播放器最终音频，不采集系统声音。本机静音或暂停时停止音乐广播。",
                                        () -> com.muoniumplayer.bedrock.RoomVoice.global().music(),
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setMusic(!v.music());}),
                                choice("发送音量", "调整发送的麦克风与音乐音量。",
                                        () -> Math.round(com.muoniumplayer.bedrock.RoomVoice.global().sendGain()*100)+"%",
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setSendGain(v.sendGain()>=1?0:v.sendGain()+.25f);},
                                        new String[]{"0%","25%","50%","75%","100%"},
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().setSendGain(Float.parseFloat(value.replace("%",""))/100)),
                                choice("接收音量", "仅调整其他房间成员的语音和音乐音量。",
                                        () -> Math.round(com.muoniumplayer.bedrock.RoomVoice.global().playbackGain()*100)+"%",
                                        () -> {var v=com.muoniumplayer.bedrock.RoomVoice.global();v.setPlaybackGain(v.playbackGain()>=1?0:v.playbackGain()+.25f);},
                                        new String[]{"0%","25%","50%","75%","100%"},
                                        value -> com.muoniumplayer.bedrock.RoomVoice.global().setPlaybackGain(Float.parseFloat(value.replace("%",""))/100)),
                                new Entry(Kind.INFO, () -> "状态："+com.muoniumplayer.bedrock.RoomVoice.global().statusText(),
                                        "房主需保持在线。最多八人语音；网络和穿透品质会影响延迟。", null, null, null, null, ColorTarget.NONE)))
'''
    return text.replace(anchor,controls+anchor)
