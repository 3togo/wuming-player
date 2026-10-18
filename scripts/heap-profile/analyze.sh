#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo "Usage: $0 dump.hprof output-prefix" >&2
  exit 2
fi
heap_source_dir="$(cd "$(dirname "$0")" && pwd)"
heap_build_dir="$2-analyzer-classes"
mkdir -p "$heap_build_dir"
heap_classpath="$(python3 - <<'PY'
from pathlib import Path
import os
root=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
files=[]
for artifact in ['shark','shark-graph','shark-hprof','shark-log','shark-android']:
    found=list((root/'com.squareup.leakcanary'/artifact/'2.14').glob('*/*.jar'))
    if not found: raise SystemExit('Missing cached Shark 2.14 artifact: '+artifact)
    files.append(found[0])
for group,artifact in [('org.jetbrains.kotlin','kotlin-stdlib'),('com.squareup.okio','okio-jvm')]:
    versions=sorted((root/group/artifact).glob('*'),key=lambda p:tuple(int(v) for v in p.name.split('.') if v.isdigit()))
    if not versions: raise SystemExit('Missing cached dependency: '+artifact)
    jars=list(versions[-1].glob('*/*.jar'))
    if not jars: raise SystemExit('Missing jar: '+artifact)
    files.append(jars[0])
print(':'.join(map(str,files)))
PY
)"
javac -J-XX:-UsePerfData --release 17 -cp "$heap_classpath" -d "$heap_build_dir" "$heap_source_dir/HeapProfile.java"
java -XX:-UsePerfData -Xmx3g -cp "$heap_build_dir:$heap_classpath" HeapProfile "$1" "$2"
