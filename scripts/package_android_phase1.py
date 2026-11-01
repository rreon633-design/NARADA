#!/usr/bin/env python3
"""Package native Android source/evidence only. No builds, test retries or private keys."""
import ast,hashlib,json,posixpath,re,zipfile
from pathlib import Path
from urllib.parse import unquote,urlsplit
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT.parent
PREFIX='NARADA_Android_Phase1'
SKIP={'build','.cache','.gradle','.kotlin','.git','.idea','node_modules','__pycache__','.venv','target','out','dist','coverage'}
entries={};origins={}
def include(path):
 if not path.is_file() or path.is_symlink():return
 rel=path.relative_to(ROOT)
 if any(part in SKIP for part in rel.parts):return
 if path.name in {'local.properties','.DS_Store'} or path.suffix in {'.jks','.keystore','.apk','.idsig'}:return
 entries[rel.as_posix()]=path.read_bytes();origins[rel.as_posix()]=rel.as_posix()
for folder in ('apps/android','docs/android','artifacts/android-phase-1'):
 for path in sorted((ROOT/folder).rglob('*')):include(path)
for name in ('android-plan.md','ANDROID-PHASE1-HANDOFF.md','.github/workflows/android-foundation.yml','scripts/validate_android_phase1.py','scripts/android_memory_guard.py','scripts/audit_android_phase1.py','scripts/package_android_phase1.py'):
 include(ROOT/name)
for path in sorted((ROOT/'references').iterdir()):
 if path.suffix.lower() in {'.html','.png'}:include(path)
# Android-specific package index; original reference asset bytes are never edited.
entries['README.md']=('# NARADA — Android development source\n\nThis is the standalone Android Phase 1 source/evidence bundle. Open `apps/android` in Android Studio, or follow its README for JDK/SDK/Gradle setup. Application and instrumentation APKs are delivered separately to keep this source bundle small. Earlier desktop source archives remain separate.\n\n'+(ROOT/'ANDROID-PHASE1-HANDOFF.md').read_text()).encode()
origins['README.md']='generated Android package index + ANDROID-PHASE1-HANDOFF.md'
entries['references/README.md']='''# Supplied visual references — Android source bundle

[Android phase plan](../android-plan.md) · [Architecture](../docs/android/architecture.md)

All five original image/HTML assets are unchanged copies of the supplied inputs. Their old branding and example data remain for provenance, not as NARADA behavior. This package index is adapted to the standalone Android bundle; full original desktop documentation stays in the earlier desktop archive/workspace.

| Preserved file | Original input | Use |
|---|---|---|
| [Android screenshot](android-screens-reference.png) | `file_00000000320c82119359f32335a79e84.png` | Mobile visual direction |
| [Android HTML](original-android-prototype.html) | `IFB_Android_Mobile_All_Screens.html` | Mobile visual prototype, not app code |
| [Desktop Files](desktop-files-reference.png) | `file_000000002cb08211a872df64bfe722e4.png` | Original desktop reference |
| [Desktop overview](desktop-screens-reference.png) | `file_000000002a548211b56d49a8c0ec03f0.png` | Original desktop reference |
| [Desktop HTML](original-desktop-prototype.html) | `IFB_Desktop_All_16_Screens.html` | Original desktop prototype |

Do not mistake the prototype's devices, progress or message examples for live product state. The implemented native application uses truthful Phase 1 states.
'''.encode();origins['references/README.md']='generated standalone index; reference assets unchanged'
# Match every frozen application/test/build input to the validated source, independent of docs.
validated=json.loads((ROOT/'artifacts/android-phase-1/validated-input-hashes.json').read_text())
for name,digest in validated.items():assert hashlib.sha256(entries[name]).hexdigest()==digest,('validated source mismatch',name)
for name,data in entries.items():
 assert not name.startswith('/') and '..' not in Path(name).parts
 assert not re.search(r'(?i)(\.keystore|\.jks|\.p12|\.pfx|\.key)$',name),name
 if name.endswith('.py'):ast.parse(data.decode(),filename=name)
 if name.endswith(('.kt','.kts','.py','.md','.yml','.toml','.properties','.xml')):
  assert not re.search(rb'(?m)^-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\r?$',data),name
# All local Markdown links resolve inside this Android-only source package.
links=0;broken=[]
for name,data in entries.items():
 if not name.endswith('.md'):continue
 for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',data.decode()):
  target=target.split(' "')[0].strip('<>')
  if urlsplit(target).scheme or target.startswith('#'):continue
  relative=unquote(urlsplit(target).path)
  if not relative:continue
  resolved=posixpath.normpath(posixpath.join(posixpath.dirname(name),relative));links+=1
  if resolved not in entries and not any(n.startswith(resolved.rstrip('/')+'/') for n in entries):broken.append((name,target,resolved))
assert not broken,broken
reference_hashes=json.loads((ROOT/'artifacts/android-phase-1/validation-summary.json').read_text())['reference_sha256']
for name,digest in reference_hashes.items():assert hashlib.sha256(entries['references/'+name]).hexdigest()==digest
# Audit historical artifact preservation without executing any desktop validation.
historical=OUT/'NARADA_Desktop_5-9_Source.zip';desktop_hash=None
if historical.exists():
 desktop_hash=hashlib.sha256(historical.read_bytes()).hexdigest()
 assert desktop_hash=='d6b15bc1fbcc028bc50407453c5ad37bd8e381c9ae11fe4369610a728acd892d'
manifest={'format':1,'scope':'Android Phase 1 source/evidence; separate APK deliverables','validated_input_count':len(validated),'native_execution':False,'ci_executed':False,'files':{name:{'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data),'origin':origins[name]} for name,data in sorted(entries.items())}}
entries['SOURCE-MANIFEST.json']=(json.dumps(manifest,indent=2)+'\n').encode()
archive=OUT/'NARADA_Android_Phase1_Source.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for name,data in sorted(entries.items()):
  info=zipfile.ZipInfo(PREFIX+'/'+name,date_time=(2026,10,31,0,0,0));info.create_system=3
  mode=0o100755 if name.endswith(('.py','.sh')) or name.endswith('/gradlew') else 0o100644
  info.external_attr=mode<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
with zipfile.ZipFile(archive) as z:
 assert z.testzip() is None
 for name,data in entries.items():assert z.read(PREFIX+'/'+name)==data
zip_hash=hashlib.sha256(archive.read_bytes()).hexdigest()
archive.with_suffix('.zip.sha256').write_text(f'{zip_hash}  {archive.name}\n')
checksums=[]
for filename in ('NARADA_Android_Phase1_Source.zip','NARADA_Android_Phase1_Debug.apk','NARADA_Android_Phase1_Instrumentation.apk'):
 path=OUT/filename;checksums.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {filename}')
(OUT/'NARADA_Android_Phase1_SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n')
summary={'archive':archive.name,'bytes':archive.stat().st_size,'sha256':zip_hash,'files':len(entries),'markdown_documents':sum(n.endswith('.md') for n in entries),'local_markdown_links_checked':links,'validated_inputs_matched':len(validated),'original_reference_assets_matched':len(reference_hashes),'private_keys_included':False,'sdk_cache_build_directories_included':False,'zip_crc_and_member_bytes_verified':True,'historical_desktop_archive_sha256':desktop_hash,'desktop_tests_rerun':False,'native_tests_executed':False}
(OUT/'NARADA_Android_Phase1_Package_Check.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary,indent=2))
