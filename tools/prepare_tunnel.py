"""Fetch one pinned optional room helper; no installation/service/game actions."""
import hashlib
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
VERSION='2026.10.7'
URL=f'https://github.com/cloudflare/cloudflared/releases/download/{VERSION}/cloudflared-windows-amd64.exe'
SHA256='2493dbc168923bb3e59e5e7716f71deced92992eea9acd6e9d74f84ac7751343'
SIZE=55390512

def verify(path):
    if path.stat().st_size!=SIZE:raise RuntimeError('Pinned cloudflared size mismatch')
    with path.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
    if actual!=SHA256:raise RuntimeError('Pinned cloudflared checksum mismatch')

def main():
    target=ROOT/'build/room-tunnel/cloudflared.exe'
    if target.exists():verify(target);print('Pinned cloudflared already verified');return
    target.parent.mkdir(parents=True,exist_ok=True)
    part=target.with_suffix('.download')
    try:
        with urllib.request.urlopen(URL,timeout=60) as incoming,part.open('xb') as outgoing:
            total=0
            while data:=incoming.read(1024*1024):
                total+=len(data)
                if total>SIZE:raise RuntimeError('Helper download exceeds pinned size')
                outgoing.write(data)
        verify(part);part.replace(target)
    finally:
        if part.exists():part.unlink()
    print('Pinned cloudflared downloaded and verified')

if __name__=='__main__':main()
