#!/usr/bin/env python3
"""Explicit GitHub source publication helper. Default: show plan, no network/write.
Run from a trusted Codex environment with git, gh and an authenticated account.
Never reads or prints credentials, rewrites history, or replaces existing remotes.
"""
from pathlib import Path
import argparse
import json
import re
import shutil
import subprocess
import sys

REPO=Path(__file__).resolve().parents[1]

def run(cmd,check=True,capture=False):
    return subprocess.run(cmd,cwd=REPO,check=check,text=True,capture_output=capture)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo',required=True,help='OWNER/REPOSITORY, explicitly chosen by the user')
    p.add_argument('--public',action='store_true',help='Explicitly authorize a new public repository')
    p.add_argument('--push',action='store_true',help='Create and push. Without this flag only prints the plan.')
    args=p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9][A-Za-z0-9._-]*',args.repo):p.error('Expected OWNER/REPOSITORY')
    plan={'source_directory':str(REPO),'github_repository':args.repo,'visibility':'public' if args.public else 'not_selected',
          'actions':['run source checks','initialize new main branch','commit reviewed sources','create NEW public GitHub repo','push main'],
          'upload_release_binaries':False,'overwrite_existing_repo':False,'dry_run':not args.push}
    print(json.dumps(plan,ensure_ascii=False,indent=2),flush=True)
    if not args.push:return
    if not args.public:p.error('--push requires --public: publication makes all reviewed sources public')
    for name in ('git','gh'):
        if not shutil.which(name):raise RuntimeError('Install '+name+' in the Codex workspace first')
    run([sys.executable,'-B','tools/check_release.py'])
    if (REPO/'.git').exists():raise RuntimeError('This helper only initializes a NEW checkout. For an existing checkout follow docs/CODEX_PUBLISH.md; no history was changed.')
    run(['gh','auth','status'],capture=True)
    for key in ('user.name','user.email'):
        if run(['git','config','--get',key],check=False,capture=True).returncode:
            raise RuntimeError('Configure your real git '+key+' in the publishing environment; no author identity is invented')
    existing=run(['gh','repo','view',args.repo,'--json','nameWithOwner'],check=False,capture=True)
    if existing.returncode==0:raise RuntimeError('Target repository already exists. This helper does not overwrite or merge it.')
    run(['git','init','-b','main'])
    # Add only tracked-source folders. .gitignore additionally rejects runtime files.
    allowed=['.gitignore','.gitattributes','.github','AGENTS.md','README.md','LICENSE','NOTICE','THIRD_PARTY.md','licenses','root','vpn','tools','docs','validation']
    run(['git','add','--',*allowed])
    staged=run(['git','diff','--cached','--name-only'],capture=True).stdout.splitlines()
    if any(Path(name).suffix.lower() in ('.apk','.pcap','.pcapng','.p12','.jks','.keystore','.jsonl','.dll','.so','.dex','.o','.class','.jar') for name in staged):
        raise RuntimeError('Unexpected binary/private file staged; inspect git diff --cached. Nothing was pushed.')
    run(['git','commit','-m','Open source passive UDP ESP: root r4, VPN r1 and development workflow'])
    run(['gh','repo','create',args.repo,'--public','--source','.','--remote','origin','--push',
         '--description','Android passive UDP ESP sources: root and local VPN variants, protocol evidence and development workflow'])
    result=run(['gh','repo','view',args.repo,'--json','url'],capture=True)
    print(result.stdout)
    print('Published main. Original APKs remain separate release assets; no private signing key was uploaded.')

if __name__=='__main__':
    try:main()
    except (RuntimeError,subprocess.CalledProcessError) as exc:print('PUBLISH_STOPPED:',exc,file=sys.stderr);sys.exit(1)