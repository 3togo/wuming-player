#!/usr/bin/env python3
"""Offline JVM profile of legacy and indexed/prepared search policies."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
ROOT = Path(__file__).resolve().parents[2]
def run(args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout or 'See build.log')[-6000:])
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--forks',type=int,default=2)
    parser.add_argument('--warmups',type=int,default=2)
    parser.add_argument('--samples',type=int,default=3)
    args=parser.parse_args()
    if min(args.forks,args.samples)<1 or args.warmups<0: parser.error('Invalid sample counts')
    root=ROOT/'libraries/search-core/build/reports/search-profile'
    output=root/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ');output.mkdir(parents=True)
    with (output/'build.log').open('w') as log:
        run(['./gradlew','--offline','--no-configuration-cache','--max-workers=2','-I','scripts/search-profile/runtime.gradle',
             ':libraries:search-core:writeSearchProfileRuntime','--console=plain'],stdout=log,stderr=subprocess.STDOUT)
    runtime=json.loads((root/'runtime.json').read_text());java=Path(runtime['javaHome'])/'bin'
    classes=output/'classes';classes.mkdir()
    run([str(java/'javac'),'-cp',runtime['classpath'],'-d',str(classes),'scripts/search-profile/SearchBenchmark.java'],capture_output=True)
    tracked=['libraries/search-core/src/main/kotlin/com/fongmi/tv/core/search/'+name+'.kt' for name in
             ['SearchResultPolicy','SearchResultAccess','TitleNormalizer','SearchTextMatcher']]
    tracked += ['libraries/search-core/src/test/kotlin/com/fongmi/tv/core/search/LegacySearchResultPolicy.kt',
                'scripts/search-profile/SearchBenchmark.java','scripts/search-profile/run.py','scripts/search-profile/runtime.gradle']
    metadata=dict(host=platform.platform(),cpus=os.cpu_count(),arguments=vars(args),runtime=runtime,
                  javaVersion=run([str(java/'java'),'-version'],capture_output=True).stderr,
                  head=run(['git','rev-parse','HEAD'],capture_output=True).stdout.strip(),
                  sourceSha256={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in tracked},
                  method='Fresh JVM per work/shape/algorithm/fork, -Xms64m -Xmx512m, fixture/GC/fingerprint excluded; main-thread allocations; host not isolated')
    (output/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n')
    rows=[]
    for shape,count in [('unique',100),('unique',1000),('mixed',400)]:
        for work in ['merge','sort']:
            for fork in range(args.forks):
                for algorithm in (['legacy','prepared'] if fork%2==0 else ['prepared','legacy']):
                    print(shape,count,work,algorithm,fork,flush=True)
                    result=run([str(java/'java'),'-Xms64m','-Xmx512m','-cp',str(classes)+os.pathsep+runtime['classpath'],
                                'com.fongmi.tv.core.search.SearchBenchmark',algorithm,work,shape,str(count),str(args.warmups),str(args.samples)],
                               capture_output=True,timeout=120)
                    (output/f'{shape}-{count}-{work}-{algorithm}-{fork}.log').write_text(result.stdout+result.stderr)
                    samples=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
                    assert len(samples)==args.samples
                    for row in samples: row.update(shape=shape,count=count,work=work,algorithm=algorithm,fork=fork)
                    rows.extend(samples)
                    (output/'samples.json').write_text(json.dumps(rows,indent=2)+'\n')
    summary=[]
    for shape,count in [('unique',100),('unique',1000),('mixed',400)]:
        for work in ['merge','sort']:
            group=[r for r in rows if (r['shape'],r['count'],r['work'])==(shape,count,work)]
            assert len({r['fingerprint'] for r in group})==1, 'Output mismatch'
            for algorithm in ['legacy','prepared']:
                values=[r for r in group if r['algorithm']==algorithm]
                row=dict(shape=shape,count=count,work=work,algorithm=algorithm,samples=len(values))
                for metric in ['elapsedMs','allocatedBytes']:
                    nums=[r[metric] for r in values];row[metric]=dict(median=statistics.median(nums),min=min(nums),max=max(nums))
                summary.append(row)
    (output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print('Report:',output,flush=True)
if __name__=='__main__': main()
