#!/usr/bin/env python3
"""Small release-source check: syntax, source bounds, required files and secrets.
Does not start Android, network forwarding, capture or a game.
"""
from pathlib import Path
import ast
import hashlib
import json
import re
import subprocess
import sys

REPO=Path(__file__).resolve().parents[1]
SKIP={'build','dist','.git','__pycache__'}
REQUIRED=['README.md','AGENTS.md','LICENSE','NOTICE','THIRD_PARTY.md','.gitignore','.gitattributes',
          'licenses/LGPL-3.0.txt','licenses/ZSTD-BSD-3-Clause.txt','docs/DEVELOPMENT.md','docs/BUILD.md',
          'docs/PROTOCOL.md','docs/VALIDATION.md','docs/PUBLISHING.md',
          'root/backend/server.py','root/backend/protocol.py','root/backend/buffered_log.py',
          'root/overlay/AndroidManifest.xml','vpn/AndroidManifest.xml','vpn/native/vpn_bridge.c',
          'tools/build_android.py','tools/publish_github.py','.github/workflows/build.yml']
FORBIDDEN_SUFFIXES={'.pcap','.pcapng','.jks','.keystore','.p12','.pem','.key','.apk','.dex','.so','.o','.class','.jar','.bin','.jsonl','.dll'}
SECRETS=[re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
         re.compile(r'gh[pousr]_[A-Za-z0-9]{30,}'),re.compile(r'github_pat_[A-Za-z0-9_]{30,}'),
         re.compile(r'AKIA[0-9A-Z]{16}')]

def sources():
    return [f for f in sorted(REPO.rglob('*')) if f.is_file() and not any(p in SKIP for p in f.relative_to(REPO).parts)]

def main():
    errors=[];files=sources();py_count=sh_count=0
    for relative in REQUIRED:
        if not (REPO/relative).is_file():errors.append('Missing '+relative)
    for file in files:
        rel=file.relative_to(REPO)
        if file.suffix.lower() in FORBIDDEN_SUFFIXES:errors.append('Non-source or private file '+str(rel));continue
        if file.name in ('.env','local.properties','current.txt','diagnostics.json') or file.name.startswith('hs_err_pid'):
            errors.append('Local state '+str(rel))
        text=file.read_text(encoding='utf-8',errors='replace')
        if any(pattern.search(text) for pattern in SECRETS):errors.append('Potential secret '+str(rel))
        if file.suffix=='.py':
            try:ast.parse(text,filename=str(rel));py_count+=1
            except SyntaxError as exc:errors.append(str(exc))
        if file.suffix=='.json':
            try:json.loads(text)
            except ValueError as exc:errors.append(str(rel)+': '+str(exc))
        if file.suffix=='.sh':
            result=subprocess.run(['sh','-n',str(file)],capture_output=True,text=True)
            if result.returncode:errors.append(str(rel)+': '+result.stderr)
            sh_count+=1
    # Important snapshot boundaries: loopback root server, per-app protected VPN.
    checks={
        'loopback_root_bind':"ThreadingHTTPServer(('127.0.0.1'" in (REPO/'root/backend/server.py').read_text(encoding='utf-8'),
        'immediate_capture':'tcpdump --immediate-mode' in (REPO/'root/backend/runner.sh').read_text(encoding='utf-8'),
        'vpn_per_app':'addAllowedApplication(GAME)' in (REPO/'vpn/java/local/udp/esp/vpn/GameVpnService.java').read_text(encoding='utf-8'),
        'vpn_protect':'protect(fd)' in (REPO/'vpn/java/local/udp/esp/vpn/GameVpnService.java').read_text(encoding='utf-8'),
        'hp_does_not_invent_max':'HP 未知' in (REPO/'vpn/java/local/udp/esp/vpn/Projection.java').read_text(encoding='utf-8'),
    }
    errors += [name for name,ok in checks.items() if not ok]
    print(json.dumps({'status':'passed' if not errors else 'failed','source_files':len(files),
        'python_syntax_files':py_count,'shell_syntax_files':sh_count,'boundary_checks':checks,
        'errors':errors,'dynamic_tests_run':False},ensure_ascii=False,indent=2))
    return 1 if errors else 0

if __name__=='__main__':sys.exit(main())
