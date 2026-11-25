#!/usr/bin/env python3
"""Run the real native-drama JVM sources and offline tests without configuring Android."""
import argparse
import os
from pathlib import Path
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[2]
SETTINGS = "rootProject.name = 'native-drama-unit-tests'\n"
BUILD = r'''
buildscript {
    repositories {
        maven { url = uri('https://maven.aliyun.com/repository/gradle-plugin') }
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies { classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${project.property('dramaKotlinVersion')}" }
}
apply plugin: 'org.jetbrains.kotlin.jvm'
repositories { mavenCentral() }
def source = file(project.property('dramaSourceRoot'))
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin {
            srcDirs "$source/libraries/base-core/src/main/kotlin", "$source/libraries/playback-core/src/main/kotlin"
            include 'com/wuming/tv/core/base/Hex.kt', 'com/wuming/tv/core/base/ByteRanges.kt'
            include 'com/wuming/tv/core/playback/Drama*.kt', 'com/wuming/tv/core/playback/Cenc*.kt'
        }
        resources { srcDir "$source/libraries/playback-core/src/main/resources"; include '**/drama-wire-tables.json' }
    }
    test {
        kotlin { srcDir "$source/libraries/playback-core/src/test/kotlin"; include '**/Drama*Test.kt' }
        resources { srcDir "$source/libraries/playback-core/src/test/resources"; include '**/drama-signing-fixtures.json' }
    }
}
dependencies {
    implementation "com.google.code.gson:gson:${project.property('dramaGsonVersion')}"
    implementation "com.squareup.okhttp3:okhttp:${project.property('dramaOkhttpVersion')}"
    testImplementation "junit:junit:${project.property('dramaJunitVersion')}"
}
test { maxParallelForks = 1; maxHeapSize = '256m' }
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--online', action='store_true', help='Allow downloading missing JVM build dependencies; tests still use synthetic responses')
    args = parser.parse_args()
    versions = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions']
    harness = ROOT / 'build/drama-unit-tests'
    harness.mkdir(parents=True, exist_ok=True)
    (harness / 'settings.gradle').write_text(SETTINGS)
    (harness / 'build.gradle').write_text(BUILD)
    command = [str(ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), '--project-dir', str(harness),
               '--no-daemon', '--max-workers=1',
               '-Dorg.gradle.jvmargs=-Xmx1g -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED',
               '-Pkotlin.compiler.execution.strategy=in-process', '-PdramaSourceRoot=' + str(ROOT)]
    if not args.online:
        command.append('--offline')
    command += ['-Pdrama' + name + 'Version=' + versions[key] for name, key in
                [('Kotlin', 'kotlin'), ('Gson', 'gson'), ('Okhttp', 'okhttp'), ('Junit', 'junit')]]
    return subprocess.call(command + ['test'], cwd=ROOT)


if __name__ == '__main__':
    raise SystemExit(main())
