#!/usr/bin/env python3
"""Compare actual old/current range Part buffering with synthetic reads."""
import datetime,hashlib,json,os,platform,re,statistics,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SOURCE='app/src/main/java/com/fongmi/android/tv/player/exo/ParallelRangeDataSource.java'
def run(args,**kwargs):
    result=subprocess.run(args,cwd=ROOT,text=True,**kwargs)
    if result.returncode: raise RuntimeError((result.stderr or result.stdout or 'See build.log')[-6000:])
    return result

def main():
    root=ROOT/'app/build/reports/range-profile';out=root/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ');out.mkdir(parents=True)
    with (out/'build.log').open('w') as log:run(['./gradlew','--offline','--no-configuration-cache','--max-workers=2','-I','scripts/range-profile/runtime.gradle',':app:writeRangeProfileRuntime','--console=plain'],stdout=log,stderr=subprocess.STDOUT)
    runtime=json.loads((root/'runtime.json').read_text());java=Path(runtime['javaHome'])/'bin'
    baseline=run(['git','rev-parse','0c0bcf9db9c4192ada0d37480d8a01d0618cadae'],capture_output=True).stdout.strip()
    legacy=run(['git','show',baseline+':'+SOURCE],capture_output=True).stdout
    generated=out/'LegacyParallelRangeDataSource.java';generated.write_text(re.sub(r'\bParallelRangeDataSource\b','LegacyParallelRangeDataSource',legacy))
    classes=out/'classes';classes.mkdir();run([str(java/'javac'),'-cp',runtime['classpath'],'-d',str(classes),str(generated),'scripts/range-profile/RangeProgressBenchmark.java'],capture_output=True)
    tracked=[SOURCE,'scripts/range-profile/RangeProgressBenchmark.java','scripts/range-profile/run.py','scripts/range-profile/runtime.gradle']
    metadata=dict(baseline=baseline,baselineSourceSha256=hashlib.sha256(legacy.encode()).hexdigest(),sourceSha256={p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in tracked},host=platform.platform(),cpus=os.cpu_count(),javaHome=runtime['javaHome'],method='Fresh JVM per chunk/algorithm/fork; 2 warmups + 4 samples; 16 x 128KiB Parts; synthetic reads and checksum included; -Xms64m -Xmx512m; caller-thread allocations')
    (out/'metadata.json').write_text(json.dumps(metadata,indent=2)+'\n');rows=[]
    for chunk in [64,8192]:
        for fork in range(2):
            for algorithm in (['legacy','lazy'] if fork==0 else ['lazy','legacy']):
                name='LegacyParallelRangeDataSource' if algorithm=='legacy' else 'ParallelRangeDataSource'
                print(chunk,algorithm,fork,flush=True)
                result=run([str(java/'java'),'-Xms64m','-Xmx512m','-cp',str(classes)+os.pathsep+runtime['classpath'],'com.fongmi.android.tv.player.exo.RangeProgressBenchmark',name,str(chunk)],capture_output=True,timeout=60)
                (out/f'{chunk}-{algorithm}-{fork}.log').write_text(result.stdout+result.stderr)
                samples=[json.loads(l) for l in result.stdout.splitlines() if l.startswith('{')];assert len(samples)==4
                for r in samples:r.update(chunkBytes=chunk,algorithm=algorithm,fork=fork)
                rows.extend(samples)
                (out/'samples.json').write_text(json.dumps(rows,indent=2)+'\n')
    assert len({r['checksum'] for r in rows})==1
    summary=[]
    for chunk in [64,8192]:
        for algorithm in ['legacy','lazy']:
            group=[r for r in rows if (r['chunkBytes'],r['algorithm'])==(chunk,algorithm)]
            row=dict(chunkBytes=chunk,algorithm=algorithm)
            for key in ['elapsedMs','allocatedBytes']:
                values=[r[key] for r in group];row[key]=dict(median=statistics.median(values),min=min(values),max=max(values))
            summary.append(row)
    (out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print('Report:',out,flush=True)
if __name__=='__main__':main()
