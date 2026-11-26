"""Additive client-only room voice. Never changes the pinned upstream tree."""
P='com.muoniumplayer.bedrock.RoomVoice'
R=P+'Router'
W=P+'Wire'

def replace(text,before,after,count=1):
    assert text.count(before)==count,before
    return text.replace(before,after)

def server(text):
    text=replace(text,'implements DirectMpPlatform.Session {',f'implements DirectMpPlatform.Session, {R}.Peer {{')
    text=replace(text,'volatile boolean open = true;',f'''volatile boolean open = true;
        boolean voiceSupported,spatialVoiceSupported;
        final {W}.Outbound outbound=new {W}.Outbound(this::writeFrame,this::close);
        public UUID id() {{return uuid;}}
        public String room() {{var m=MpParty.manager();var r=m==null?null:m.roomOf(uuid);return r==null?"":r.id;}}
        public boolean open() {{return open;}}
        public boolean supportsVoice() {{return voiceSupported;}}
        public boolean supportsSpatialVoice() {{return spatialVoiceSupported;}}
        public void control(com.google.gson.JsonObject m) {{outbound.control(MpProto.encode(m));}}
        public void audio(com.google.gson.JsonObject m) {{outbound.audio(MpProto.encode(m));}}''')
    text=replace(text,'public void send(byte[] payload) {', '''public void send(byte[] payload) {outbound.control(payload);}
        private void writeFrame(byte[] payload) {''')
    text=replace(text,'            open = false;','            open = false;\n            outbound.close();')
    text=replace(text,'    private final DirectMpPlatform platform;',f'''    private final DirectMpPlatform platform;
    private final {R} voice;
''')
    text=replace(text,'this.platform = new DirectMpPlatform(this::onEngineTask, localChat);',f'''this.platform = new DirectMpPlatform(this::onEngineTask, localChat);
        this.voice=new {R}(() -> platform.snapshot().stream().filter(s->s instanceof Conn).map(s->({R}.Peer)s).toList(),System::nanoTime);''')
    text=replace(text,'                    MpParty.tick();','                    MpParty.tick();\n                    voice.tick();')
    text=replace(text,'conn = new Conn(socket, uuid, name, out);','conn = new Conn(socket, uuid, name, out);\n            conn.voiceSupported=MpProto.num(hello,"bedrockVoice",0)==1;\n            conn.spatialVoiceSupported=MpProto.num(hello,"bedrockSpatialVoice",0)==1;')
    text=replace(text,'            var pong=MpProto.decode(frame);',f'''            var pong=MpProto.decode(frame);
            if(pong!=null && {R}.AUDIO.equals(MpProto.str(pong,MpProto.OP,""))) {{voice.accept(conn,pong);continue;}}''')
    text=replace(text,'            final Conn self = conn;','            final Conn self = conn;\n            if(self!=null)self.close();')
    return text

def client(text):
    text=replace(text,'    private Thread readerThread;',f'''    private Thread readerThread;
    private final {W}.Outbound outbound=new {W}.Outbound(this::writeFrame,this::close);
    public void bindVoice() {{var id=selfUuid();if(id!=null&&isConnected()){P}.global().bind(this,id,m->outbound.audio(MpProto.encode(m)));}}''')
    text=replace(text,'m.addProperty("auth", auth);','m.addProperty("auth", auth);\n        m.addProperty("bedrockVoice",1);\n        m.addProperty("bedrockSpatialVoice",1);')
    text=replace(text,'        if(MpProto.S_PING.equals(op)) {',f'''        if({R}.SESSION.equals(op)||{R}.AUDIO.equals(op)) {{{P}.global().receive(this,msg);return true;}}
        if(MpProto.S_PING.equals(op)) {{''')
    for signature in ['public void close() {','public void detachQuietly() {']:
        text=replace(text,signature,signature+f'\n        {P}.global().unbind(this);outbound.close();')
    text=replace(text,'    public void send(byte[] frame) {','    public void send(byte[] frame) {outbound.control(frame);}\n    private void writeFrame(byte[] frame) {')
    return text

def facade(text):
    for c in ['self','c']:
        text=replace(text,f'com.muoniumplayer.bedrock.RoomLatency.bind({c});',f'com.muoniumplayer.bedrock.RoomLatency.bind({c}); {c}.bindVoice();')
    return text

def ui(text):
    # A spare row in the direct dialog; no overlap with proxy/tunnel or copy controls.
    return replace(text,'        // ── 托管态 ──────────────────────────────────────────────────────────', '''        btn(dialog,()->PartyIcons.LINK,()->"房间语音",null,
            com.muoniumplayer.bedrock.RoomVoiceSettings::open,STYLE_GHOST,
            b->{b.setBounds(150,28);b.setPosition(Math.max(20,dialog.getWidth()-170),hosting?270:308);});
        // ── 托管态 ──────────────────────────────────────────────────────────''')
