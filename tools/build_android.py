#!/usr/bin/env python3
"""Portable SDK/JDK/NDK build for source releases. Never installs or starts APKs."""
from pathlib import Path
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import zipfile

REPO=Path(__file__).resolve().parents[1]
SDK_JAR_API='android-35'

def digest(file):return hashlib.sha256(file.read_bytes()).hexdigest()
def version_key(path):return tuple(int(x) if x.isdigit() else 0 for x in path.name.split('.'))
def host_executable(directory,name):return directory/(name+'.exe' if os.name=='nt' else name)
def tool_path(path,work):
    # Windows aapt2 cannot open some non-ASCII absolute paths; cwd is Unicode-safe.
    if os.name=='nt':
        try:return os.path.relpath(path,work)
        except ValueError:pass  # A different drive requires an absolute path.
    return str(path)
def locate_sdk():
    candidates=[os.environ.get('ANDROID_SDK_ROOT'),os.environ.get('ANDROID_HOME'),'/root/Android',str(Path.home()/'Android/Sdk')]
    for item in candidates:
        if item and (Path(item)/'platforms'/SDK_JAR_API/'android.jar').exists():return Path(item)
    raise RuntimeError('Set ANDROID_SDK_ROOT with platforms;android-35 and build-tools;35.0.0 installed')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('variant',choices=['root','vpn','all'])
    parser.add_argument('--jobs',type=int,default=2)
    args=parser.parse_args()
    sdk=locate_sdk();tools=sdk/'build-tools/35.0.0';android=sdk/'platforms'/SDK_JAR_API/'android.jar'
    for file in (host_executable(tools,'aapt2'),tools/'lib/d8.jar',tools/'lib/apksigner.jar'):
        if not file.exists():raise RuntimeError('Missing '+str(file))
    for name in ['java','javac','keytool']:
        if not shutil.which(name):raise RuntimeError('Missing '+name+'; install JDK17')
    variants=['root','vpn'] if args.variant=='all' else [args.variant]
    for variant in variants:build(variant,sdk,tools,android,max(1,min(args.jobs,8)))

def build(variant,sdk,tools,android,jobs):
    source=REPO/('root/overlay' if variant=='root' else 'vpn')
    output=REPO/'dist';work=REPO/'build'/variant;temporary=REPO/'build/tmp'
    for directory in (output,work,temporary):directory.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,TMPDIR=str(temporary),TMP=str(temporary),TEMP=str(temporary),PYTHONDONTWRITEBYTECODE='1')
    log=work/'build.log';log.write_text('',encoding='utf-8')
    def run(label,cmd):
        command=[str(cmd[0]),*[tool_path(x,work) if isinstance(x,Path) else str(x) for x in cmd[1:]]]
        result=subprocess.run(command,cwd=work,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
        with log.open('a',encoding='utf-8') as f:f.write('\n=== '+label+' ===\n'+result.stdout+result.stderr)
        if result.returncode:raise RuntimeError(label+' failed; '+str(log)+'\n'+(result.stdout+result.stderr)[-4000:])
        return result.stdout
    java=[shutil.which('java'),'-Xmx384m','-Djava.io.tmpdir='+tool_path(temporary,work)]
    native=None
    if variant=='vpn':
        ndk=Path(os.environ['ANDROID_NDK_HOME']) if os.environ.get('ANDROID_NDK_HOME') else None
        if ndk is None:
            ndks=sorted((sdk/'ndk').glob('*'),key=version_key)
            if not ndks:raise RuntimeError('Install ndk;26.3.11579264 or set ANDROID_NDK_HOME')
            ndk=ndks[-1]
        prebuilts=list((ndk/'toolchains/llvm/prebuilt').glob('*'))
        if not prebuilts:raise RuntimeError('Missing NDK prebuilt directory')
        pre=prebuilts[0];sysroot=pre/'sysroot';lib=sysroot/'usr/lib/aarch64-linux-android/26'
        clang=host_executable(pre/'bin','clang');lld=None;usable=False
        try:usable=subprocess.run([str(clang),'--version'],env=env,capture_output=True,timeout=10).returncode==0
        except (OSError,subprocess.TimeoutExpired):pass
        if not usable:
            clang=Path(shutil.which('clang-18') or shutil.which('clang') or '')
            lld=shutil.which('ld.lld-18') or shutil.which('ld.lld')
            if not clang.is_file() or not lld:raise RuntimeError('NDK host compiler cannot run; on Linux ARM64 install clang-18 and lld-18')
        builtins=list((pre/'lib/clang').glob('*/lib/linux/libclang_rt.builtins-aarch64-android.a'))
        if not builtins:raise RuntimeError('Missing NDK Android ARM64 builtins')
        native_dir=source/'native';zstd=native_dir/'third_party/zstd/lib'
        native_sources=[native_dir/'vpn_bridge.c',native_dir/'third_party/zdtun/zdtun.c',native_dir/'third_party/zdtun/utils.c']
        native_sources+=sorted((zstd/'common').glob('*.c'))+sorted((zstd/'decompress').glob('*.c'))
        objdir=work/'native/obj';objdir.mkdir(parents=True,exist_ok=True)
        common=[clang,'--target=aarch64-linux-android26','--sysroot='+tool_path(sysroot,work)]
        def compile_one(pair):
            src,obj=pair
            run('cc-'+src.name,common+['-std=gnu11','-O2','-fPIC','-ffunction-sections','-fdata-sections','-DANDROID','-DNO_DEBUG','-D_LITTLE_ENDIAN',
                '-DZSTD_DISABLE_ASM=1','-DZSTD_LEGACY_SUPPORT=0','-I'+tool_path(zstd,work),'-I'+tool_path(native_dir/'third_party/zdtun',work),'-c',src,'-o',obj])
            return obj
        pairs=[(src,objdir/(str(i)+'-'+src.stem+'.o')) for i,src in enumerate(native_sources)]
        with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:objects=list(pool.map(compile_one,pairs))
        native=work/'native/libudpespvpn.so'
        linker=['-fuse-ld='+str(lld)] if lld else ['-fuse-ld=lld']
        run('native-link',common+linker+['-nostdlib','-shared','-Wl,--no-undefined','-Wl,--gc-sections','-Wl,-soname,libudpespvpn.so',
            '-Wl,-z,max-page-size=16384',lib/'crtbegin_so.o',*objects,'-L'+tool_path(lib,work),'-lc','-lm','-ldl','-llog',builtins[0],lib/'crtend_so.o','-o',native])
        if shutil.which('readelf'):
            elf=run('ELF',['readelf','-h','-d',native]);assert 'AArch64' in elf and 'libc.so' in elf
    for name in ('classes','dex'):
        directory=work/name
        if directory.exists():shutil.rmtree(directory)
        directory.mkdir()
    res=work/'resources.zip'
    if res.exists():res.unlink()
    run('resources',[host_executable(tools,'aapt2'),'compile','--dir',source/'res','-o',res])
    base=work/'base.apk';code='5' if variant=='root' else '1';version='0.5-box-tracer-hp-candidate' if variant=='root' else '0.1-vpn-no-root-candidate'
    run('resource-link',[host_executable(tools,'aapt2'),'link','-I',android,'--manifest',source/'AndroidManifest.xml','--min-sdk-version','26',
        '--target-sdk-version','35','--version-code',code,'--version-name',version,'-o',base,res])
    sources=sorted((source/'java').rglob('*.java'))
    run('javac',[shutil.which('javac'),'-J-Djava.io.tmpdir='+tool_path(temporary,work),'-encoding','UTF-8','-source','8','-target','8',
        '-classpath',android,'-d',work/'classes',*sources])
    jar=work/'classes.jar'
    with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as archive:
        for file in sorted((work/'classes').rglob('*.class')):archive.write(file,str(file.relative_to(work/'classes')))
    run('d8',java+['-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--lib',android,'--min-api','26','--output',work/'dex',jar])
    unsigned=work/'unsigned.apk';shutil.copyfile(base,unsigned)
    with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as archive:
        for file in sorted((work/'dex').glob('classes*.dex')):archive.write(file,file.name)
        for filename in ('LICENSE','NOTICE','THIRD_PARTY.md'):
            archive.write(REPO/filename,'assets/licenses/'+filename)
        if native:
            archive.write(native,'lib/arm64-v8a/libudpespvpn.so')
            for src,name in [(source/'native/third_party/zdtun/COPYING','zdtun-COPYING'),(source/'native/third_party/zstd/LICENSE','zstd-LICENSE'),(source/'native/third_party/zstd/COPYING','zstd-COPYING'),(REPO/'LICENSE','project-GPL-3.0')]:
                archive.write(src,'assets/licenses/'+name)
    signing=REPO/'build/signing';signing.mkdir(exist_ok=True)
    configured=os.environ.get('UDP_ESP_KEYSTORE')
    keystore=Path(configured).expanduser().resolve() if configured else signing/'local-debug.p12'
    alias=os.environ.get('UDP_ESP_KEY_ALIAS','release' if configured else 'local-debug')
    if not configured and not keystore.exists():
        run('create-local-key',[shutil.which('keytool'),'-J-Djava.io.tmpdir='+tool_path(temporary,work),'-genkeypair','-keystore',keystore,'-storetype','PKCS12',
            '-storepass','android','-keypass','android','-alias',alias,'-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=Local UDP ESP Development','-noprompt'])
    if configured:
        if not env.get('UDP_ESP_STOREPASS'):raise RuntimeError('Set UDP_ESP_STOREPASS for the private signing key')
        env.setdefault('UDP_ESP_KEYPASS',env['UDP_ESP_STOREPASS']);storepass='env:UDP_ESP_STOREPASS';keypass='env:UDP_ESP_KEYPASS'
    else:storepass=keypass='pass:android'
    apk=work/('udp-esp-'+variant+'.apk')
    run('sign',java+['-jar',tools/'lib/apksigner.jar','sign','--ks',keystore,'--ks-key-alias',alias,'--ks-pass',storepass,'--key-pass',keypass,'--out',apk,unsigned])
    run('verify',java+['-jar',tools/'lib/apksigner.jar','verify','--verbose',apk])
    package='local.udp.esp' if variant=='root' else 'local.udp.esp.vpn'
    assert package in run('badging',[host_executable(tools,'aapt2'),'dump','badging',apk])
    with zipfile.ZipFile(apk) as archive:assert archive.testzip() is None
    dest=output/apk.name;shutil.copyfile(apk,dest)
    result={'status':'source_release_build_passed_not_runtime_tested','variant':variant,'apk':str(dest.relative_to(REPO)),
        'sha256':digest(dest),'size':dest.stat().st_size,'package':package,'version_code':int(code),
        'time_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'signing':'user_supplied' if configured else 'local_development_new_key_not_original_release',
        'installed':False,'launched':False,'vpn_started':False,'game_started':False,
        'source_hashes':{str(f.relative_to(REPO)):digest(f) for f in sorted(source.rglob('*')) if f.is_file()}}
    (output/(variant+'-build.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/(apk.name+'.sha256')).write_text(digest(dest)+'  '+dest.name+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='source_hashes'},ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':
    try:main()
    except Exception as error:print('BUILD_FAILED',error,file=sys.stderr);sys.exit(1)
