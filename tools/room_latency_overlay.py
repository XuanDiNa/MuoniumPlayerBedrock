"""Additive, pinned-source room RTT adapters. Never edits the vendor tree."""
P='com.muoniumplayer.bedrock.RoomLatency'

def replace(text, before, after, count=1):
    assert text.count(before)==count, before
    return text.replace(before,after)

def server(text):
    text=replace(text,'volatile boolean open = true;',f'volatile boolean open = true;\n        final {P}.Probe latency = new {P}.Probe();\n        boolean latencySupported;')
    text=replace(text,'15_000_000_000L','2_000_000_000L')
    text=replace(text,'''                byte[] ping = MpProto.encode(MpProto.msg(MpProto.S_PING));
                for (DirectMpPlatform.Session s : platform.snapshot()) {
                    if (s instanceof Conn) ((Conn) s).send(ping);
                }''',f'''                for (DirectMpPlatform.Session s : platform.snapshot()) {{
                    if (s instanceof Conn c) {{
                        long token=c.latency.begin(System.nanoTime());
                        if(token!=0) {{
                            var ping=MpProto.msg(MpProto.S_PING);ping.addProperty("t",token);
                            c.send(MpProto.encode(ping));
                        }}
                    }}
                }}
                publishLatencies(now);''')
    text=replace(text,'conn = new Conn(socket, uuid, name, out);','conn = new Conn(socket, uuid, name, out);\n            conn.latencySupported=MpProto.num(hello,"bedrockLatency",0)==1;')
    text=replace(text,'conn.lastFrameNanos = System.nanoTime();',f'''conn.lastFrameNanos = System.nanoTime();
            var pong=MpProto.decode(frame);
            if(pong!=null && MpProto.C_PONG.equals(MpProto.str(pong,MpProto.OP,"")))
                conn.latency.accept(MpProto.num(pong,"t",0),conn.lastFrameNanos);''')
    return replace(text,'    private void acceptLoop() {',f'''    // Engine-thread only: never disclose members or latency from another room.
    private void publishLatencies(long now) {{
        var manager=MpParty.manager();if(manager==null)return;
        var sessions=platform.snapshot();
        for(var recipient:sessions) {{
            if(!(recipient instanceof Conn target)||!target.open||!target.latencySupported)continue;
            var room=manager.roomOf(target.uuid);if(room==null)continue;
            var samples=new com.google.gson.JsonObject();
            for(var session:sessions) {{
                if(samples.size()>=256)break;
                if(session instanceof Conn c && c.open && room.has(c.uuid)) {{
                    int ms=c.latency.millis(now);if(ms>=0)samples.addProperty(c.uuid.toString(),ms);
                }}
            }}
            var table=MpProto.msg({P}.OP);table.addProperty("room",room.id);table.add("samples",samples);
            target.send(MpProto.encode(table));
        }}
    }}

    private void acceptLoop() {{''')

def client(text):
    text=replace(text,'m.addProperty("auth", auth);','m.addProperty("auth", auth);\n        m.addProperty("bedrockLatency",1);')
    text=replace(text,'                    MpPartyClient.markDirectSource();','                    if(latencyFrame(f))continue;\n                    MpPartyClient.markDirectSource();')
    # Different indentation also occurs in the replay loop; anchor the read path.
    text=replace(text,'''                // MC 通道的帧被丢弃，直连这一路必须放行）。
                MpPartyClient.markDirectSource();''','''                // MC 通道的帧被丢弃，直连这一路必须放行）。
                if(latencyFrame(frame))continue;
                MpPartyClient.markDirectSource();''')
    for signature in ['public void close() {','public void detachQuietly() {']:
        text=replace(text,signature,signature+f'\n        {P}.clear(this);')
    return replace(text,'    /** 探一帧的 op 是不是 S_PING',f'''    // Answer on this socket, without waiting for UI rendering or the facade route.
    private boolean latencyFrame(byte[] frame) {{
        var msg=MpProto.decode(frame);if(msg==null)return false;
        String op=MpProto.str(msg,MpProto.OP,"");
        if(MpProto.S_PING.equals(op)) {{
            var pong=MpProto.msg(MpProto.C_PONG);pong.addProperty("t",MpProto.num(msg,"t",0));
            send(MpProto.encode(pong));return true;
        }}
        if({P}.OP.equals(op)) {{{P}.receive(this,msg);return true;}}
        return false;
    }}

    /** 探一帧的 op 是不是 S_PING''')

def facade(text):
    for connection in ['self','c']:
        text=replace(text,f'if (router != null) router.attachDirect({connection});',
            f'if (router != null) {{ router.attachDirect({connection}); {P}.bind({connection}); }}')
    return text

def ui(text):
    start=text.index('    private void buildMemberRow(')
    end=text.index('    private MpMember memberAt(',start)
    chunk=text[start:end]
    chunk=replace(chunk,'bg.getWidth() >= 480','bg.getWidth() >= 550',count=2)
    chunk=replace(chunk,'Math.max(72, memberToolsLeft(bg) - 54 - 13 - chipLane - reserve)',
        'Math.max(24, memberToolsLeft(bg) - 54 - 44 - chipLane - reserve)')
    chunk=replace(chunk,'String mark = member == null ? "" : member.automixLabel();','String mark = member == null || bg.getWidth()<550 ? "" : member.automixLabel();')
    chunk=replace(chunk,'return member == null ? "" : member.automixLabel();','return member == null || bg.getWidth()<550 ? "" : member.automixLabel();')
    chunk=replace(chunk,'return member != null && "admin".equals(member.role) ? "撤管理" : "设管理";',
        'return bg.getWidth()<518 ? "" : member != null && "admin".equals(member.role) ? "撤管理" : "设管理";')
    chunk=replace(chunk,'() -> "转房主"','() -> bg.getWidth()<518 ? "" : "转房主"')
    chunk=replace(chunk,'() -> MpPartyClient.inChannel() && !MpPartyClient.hasControl() ? "发起踢出" : "踢出"',
        '() -> bg.getWidth()<518 ? "" : MpPartyClient.inChannel() && !MpPartyClient.hasControl() ? "发起踢出" : "踢出"')
    chunk=replace(chunk,'Math.max(54.0 + 120, bg.getWidth() - 230)','memberToolsLeft(bg) - 8')
    chunk=replace(chunk,'        final Panel tools = rowTools(bg);',f'''        LabelWidget latency = label(bg, () -> {{
            MpMember member=memberAt(list,slot);
            return member==null?"":{P}.label(member.uuid);
        }},FontManager.pf10bold);
        latency.setTooltip("一起听连接 RTT：成员 ↔ 托管房主，包含穿透／代理；非游戏服务器 Ping");
        latency.setBeforeRenderCallback(() -> {{
            MpMember member=memberAt(list,slot);int ms=member==null?-1:{P}.millis(member.uuid);
            latency.setColor(ms<0?secondary():ms<=100?OK_COLOR:ms<=250?WARN_COLOR:ERROR_COLOR);
            latency.setMaxWidth(54);latency.setWidthLimitType(LabelWidget.WidthLimitType.TRIM_TO_WIDTH);
            latency.setPosition(Math.max(0,bg.getWidth()-10-latency.getWidth()),inkTop(latency,23));
        }});
        final Panel tools = rowTools(bg);''')
    chunk=replace(chunk,'        double[] box = memberToolBoxes(bg);','')
    chunk=replace(chunk,'            b.setBounds(box[','            double[] box=memberToolBoxes(bg);\n            b.setBounds(box[',count=3)
    for index,tip in enumerate(['设置／撤销管理','转让房主','踢出／发起踢出投票']):
        chunk=replace(chunk,f'            b.setBounds(box[{index}], 26);',f'            b.setTooltip("{tip}");\n            b.setBounds(box[{index}], 26);')
    text=text[:start]+chunk+text[end:]
    start=text.index('    private static double memberToolsLeft(')
    tail=replace(text[start:],'return width - 222.0;','return width - 64 - 222.0;')
    tail=replace(tail,'return width - squeezed;','return width - 64 - squeezed;')
    return text[:start]+tail
