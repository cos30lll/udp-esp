#!/usr/bin/env python3
"""Copy the root backend into the existing Android layout, without executing it."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

REPO=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--destination',type=Path,required=True,help='A NEW empty directory; never point at a running backend')
    args=p.parse_args();dest=args.destination.expanduser().resolve()
    if dest.exists() and any(dest.iterdir()):p.error('Destination must be empty. Stop/update an existing deployment manually.')
    source=REPO/'root/backend';dest.mkdir(parents=True,exist_ok=True)
    for file in sorted(source.iterdir()):
        if file.is_file() and file.suffix in ('.py','.sh','.html','.js','.css'):
            shutil.copyfile(file,dest/file.name)
    # Preserve both public and historical names expected by the old Android layout.
    shutil.copyfile(dest/'start.sh',dest/'启动.sh');shutil.copyfile(dest/'stop.sh',dest/'停止.sh')
    manifest={'version':'root-r4-public-source-layout','deployed_by':'stage_root_backend.py',
        'started':False,'files':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(dest.iterdir()) if f.is_file()}}
    (dest/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print('Staged only; no capture or game started: '+str(dest))

if __name__=='__main__':main()