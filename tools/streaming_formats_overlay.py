"""Narrow seek/metadata adapters for the pinned JAAD decoder; no vendor edits."""
import hashlib


def generate(reference, output, root, report):
    from youtube_session_overlay import apply_store
    path = 'com/muoniumplayer/core/ncm/music/YouTubeSessionStore.java'
    source = reference / 'muonium-common/src/main/java' / path
    dest = output / path
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(apply_store(source.read_text(encoding='utf-8-sig')), encoding='utf-8')
    report.append({'source': source.relative_to(root).as_posix(), 'output': dest.relative_to(root).as_posix(),
                   'original_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                   'adapted_sha256': hashlib.sha256(dest.read_bytes()).hexdigest()})
    base = 'repackage/net/sourceforge/jaad/mp4/'
    for name in ['MP4Container.java', 'MP4InputStream.java', 'api/Track.java']:
        source = reference / 'muonium-common/src/main/java' / base / name
        text = source.read_text(encoding='utf-8-sig')
        if name == 'MP4Container.java':
            # Stop on metadata: fMP4's following moof belongs to the incremental
            # sample reader. Do not skip it while searching for the first mdat.
            text = text.replace('moovFound = true;', 'moovFound = true;\n                break;')
        elif name == 'MP4InputStream.java':
            text = text.replace('if(in!=null) l += in.skip((n-l));',
                'if(in!=null) { long skipped=in.skip(n-l); if(skipped==0) { if(in.read()<0)throw new EOFException(); skipped=1; } l+=skipped; }')
        else:
            marker='public boolean hasMoreFrames() {'
            assert text.count(marker) == 1
            text = text.replace(marker, '''public int bedrockFrameCount() { return frames.size(); }
    public long bedrockTrackId() { return tkhd.getTrackID(); }
    public long bedrockFrameOffset(int index) { return frames.get(index).getOffset(); }
    public long bedrockFrameSize(int index) { return frames.get(index).getSize(); }
    ''' + marker)
        dest = output / base / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text, encoding='utf-8')
        report.append({'source': source.relative_to(root).as_posix(), 'output': dest.relative_to(root).as_posix(),
                       'original_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                       'adapted_sha256': hashlib.sha256(dest.read_bytes()).hexdigest()})
    path = 'com/muoniumplayer/core/ncm/music/QishuiAudioDecryptor.java'
    source = reference / 'muonium-common/src/main/java' / path
    text = source.read_text(encoding='utf-8-sig')
    marker = '    private QishuiAudioDecryptor() {'
    assert text.count(marker) == 1
    text = text.replace(marker, '''    static byte[] bedrockStreamKey(String auth) throws IOException {
        return hexToBytes(resolveKey(auth));
    }
    static byte[][] bedrockStreamIvs(byte[] data, int count) throws IOException {
        Box moov=requireBox(findBox(data,"moov",0,data.length),"moov");
        Box trak=requireBox(findBox(data,"trak",moov.dataStart,moov.end),"trak");
        Box mdia=requireBox(findBox(data,"mdia",trak.dataStart,trak.end),"mdia");
        Box minf=requireBox(findBox(data,"minf",mdia.dataStart,mdia.end),"minf");
        Box stbl=requireBox(findBox(data,"stbl",minf.dataStart,minf.end),"stbl");
        Box senc=requireBox(findBox(data,"senc",stbl.dataStart,stbl.end),"senc");
        // Same whole-sample, eight-byte-IV variant as upstream. No subsample guesses.
        if(readU32(data,senc.dataStart)!=0 || readU32(data,senc.dataStart+4)!=count)
            throw new IOException("Unsupported authorized sample encryption layout");
        return parseSenc(data,senc,count);
    }
    static byte[] bedrockStreamDecrypt(byte[] packet, byte[] key, byte[] iv) throws IOException {
        return decryptCtr(packet,0,packet.length,key,iv);
    }
''' + marker)
    dest=output/path
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(text,encoding='utf-8')
    report.append({'source':source.relative_to(root).as_posix(),'output':dest.relative_to(root).as_posix(),
                   'original_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                   'adapted_sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
    path='com/muoniumplayer/core/ncm/music/YouTubeMusicService.java'
    source=reference/'muonium-common/src/main/java'/path
    text=source.read_text(encoding='utf-8-sig')
    marker='    private static final class ChunkedAudioStream extends InputStream {'
    assert text.count(marker)==1
    text=text.replace(marker,'''    public static InputStream openProgressiveStream(String videoId, String url) throws IOException {
        ChunkedAudioStream stream=(ChunkedAudioStream)openStream(videoId,url);
        stream.progressive=true;
        return stream;
    }
''' + marker + '\n        private boolean progressive;')
    begin=text.index(marker)
    end=text.index('\n    }\n',begin)+len('\n    }\n')
    chunk=text[begin:end]
    assert chunk.count('windowHighFor(offset, total, windowStep)')==2
    chunk=chunk.replace('windowHighFor(offset, total, windowStep)','progressiveHigh()')
    chunk=chunk.replace('        private boolean nextWindow() throws IOException {','''        private long progressiveHigh() {
            long high=windowHighFor(offset,total,windowStep);
            return progressive?Math.min(high,offset+(windows<=1?256L*1024:1024L*1024)-1):high;
        }
        private boolean nextWindow() throws IOException {''')
    chunk=chunk.replace('exchange(request, AUDIO_EXCHANGE_MAX_BYTES,','exchange(request, progressive?(int)Math.min(AUDIO_EXCHANGE_MAX_BYTES,high-offset+1):AUDIO_EXCHANGE_MAX_BYTES,')
    text=text[:begin]+chunk+text[end:]
    from youtube_stream_overlay import apply as youtube_stream_overlay
    text=youtube_stream_overlay(text)
    from youtube_lyrics_overlay import apply_service, apply_loader
    text=apply_service(text)
    dest=output/path
    dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(text,encoding='utf-8')
    report.append({'source':source.relative_to(root).as_posix(),'output':dest.relative_to(root).as_posix(),
                   'original_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                   'adapted_sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
    path='com/muoniumplayer/core/ncm/music/LyricLoadService.java'
    source=reference/'muonium-common/src/main/java'/path
    dest=output/path
    dest.write_text(apply_loader(source.read_text(encoding='utf-8-sig')),encoding='utf-8')
    report.append({'source':source.relative_to(root).as_posix(),'output':dest.relative_to(root).as_posix(),
                   'original_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
                   'adapted_sha256':hashlib.sha256(dest.read_bytes()).hexdigest()})
