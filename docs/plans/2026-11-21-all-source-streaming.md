# Progressive playback across supported online sources

Extend the existing session-only decoder, not the queue or DSP. Admit clear HTTP
audio by bytes, retaining source-specific Bilibili headers and YouTube's windowed
transport. Local files and authorized Apple native playback keep their original
route. Reuse Qishui's original authorized per-sample CTR operation for its
supported whole-sample, eight-byte-IV layout; unsupported encryption retains
the original fallback. Never feed encrypted bytes into an ordinary decoder.

Add incremental AAC/ADTS, sample-table MP4/M4A, fragmented MP4, WAV PCM/float and
OGG Vorbis beside MP3/FLAC. Decode on workers; callbacks read local PCM only.
Use bounded MP4 metadata and per-sample fragment reads, including a single large
mdat. Range metadata/seek reads must retain object identity. Unsupported ranges
use sequential buffering; cancellation must abort a blocked socket. Preserve
default-off prefetch/retention, cleanup, queue ownership and automix semantics.

Validation: owned sine fixtures, loopback HTTP with its tail held, actual
AudioPlayer admission and preparation; every codec must be ready before release.
Cover all provider labels independently from codec, wrong reported formats,
Bilibili headers/redirects, YouTube transport, tail-moov, malformed and encrypted
payloads, cancellation and seek routing. Run existing streaming and complete
JVM/native campaigns; package vanilla four-version and separate LL update/full
archives with source and hashes. No automatic install or GitHub publication.
