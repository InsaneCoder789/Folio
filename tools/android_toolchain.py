"""Select an installed JDK supported by Folio's pinned Gradle 8.13; no installs or global changes."""
import os
from pathlib import Path
import re
import subprocess


def java_major(home):
    try:
        result=subprocess.run([str(Path(home)/'bin/java'),'-version'],capture_output=True,text=True,timeout=5)
        match=re.search(r'version "(?:1\.)?(\d+)',result.stderr+result.stdout)
        return int(match.group(1)) if result.returncode==0 and match else None
    except (OSError,subprocess.TimeoutExpired):return None


def build_environment():
    env=os.environ.copy()
    explicit=env.get('FOLIO_JAVA_HOME') or env.get('JAVA_HOME')
    if explicit:
        if java_major(explicit) not in range(17,24):
            raise RuntimeError('Folio Gradle 8.13 needs JDK 17-23. Set FOLIO_JAVA_HOME to an installed compatible JDK.')
        env['JAVA_HOME']=explicit;return env
    candidates=[Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')]
    for base in (Path('/Library/Java/JavaVirtualMachines'),Path.home()/'Library/Java/JavaVirtualMachines'):
        if base.is_dir():candidates.extend(base.glob('*/Contents/Home'))
    supported=[(java_major(p),p) for p in candidates if (p/'bin/java').is_file()]
    supported=[(version,p) for version,p in supported if version in range(17,24)]
    priority={17:0,21:1,22:2,23:3,20:4,19:5,18:6}
    if not supported:raise RuntimeError('No compatible installed JDK. Set FOLIO_JAVA_HOME to JDK 17-23.')
    env['JAVA_HOME']=str(min(supported,key=lambda item:priority[item[0]])[1]);return env
