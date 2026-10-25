"""Snapshots what publishToMavenLocal produced for the library, to compare before and after a change.

Usage:
    python scripts/maven-local-snapshot.py <version> <out-file>
    python scripts/maven-local-snapshot.py --diff <before-file> <after-file>

The snapshot lists every published file (with the version replaced by <v>), the dependencies in
every POM, and the variants, attributes and dependencies in every Gradle module file. Checksums
and timestamps are left out, so two builds of the same code give identical snapshots.
"""
import json
import os
import re
import sys
import difflib

GROUP_DIR = os.path.join(os.path.expanduser('~'), '.m2', 'repository', 'io', 'github', 'asterx5')


def snapshot(version):
    lines = []
    for artifact in sorted(os.listdir(GROUP_DIR)):
        folder = os.path.join(GROUP_DIR, artifact, version)
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            if name.startswith('maven-metadata'):
                continue  # written by Maven Local for -SNAPSHOT versions only, never published
            norm = name.replace(version, '<v>')
            lines.append('file ' + artifact + '/' + norm)
            path = os.path.join(folder, name)
            if name.endswith('.pom'):
                text = open(path, encoding='utf-8').read()
                for dep in re.findall(r'<dependency>(.*?)</dependency>', text, flags=re.S):
                    fields = {k: v for k, v in re.findall(r'<(groupId|artifactId|version|scope|optional)>([^<]*)<', dep)}
                    lines.append('  pom-dep %s:%s:%s scope=%s optional=%s' % (
                        fields.get('groupId'), fields.get('artifactId'),
                        fields.get('version', '').replace(version, '<v>'),
                        fields.get('scope', 'compile'), fields.get('optional', 'false')))
            elif name.endswith('.module'):
                data = json.load(open(path, encoding='utf-8'))
                for variant in data.get('variants', []):
                    lines.append('  variant ' + variant['name'])
                    for k, v in sorted(variant.get('attributes', {}).items()):
                        lines.append('    attr %s=%s' % (k, v))
                    for dep in variant.get('dependencies', []):
                        req = dep.get('version', {})
                        ver = req.get('requires') or req.get('strictly') or req.get('prefers') or ''
                        lines.append('    dep %s:%s:%s' % (dep['group'], dep['module'], ver.replace(version, '<v>')))
                    for f in variant.get('files', []):
                        lines.append('    variant-file ' + f['name'].replace(version, '<v>'))
                    if 'available-at' in variant:
                        lines.append('    available-at ' + variant['available-at']['url'].replace(version, '<v>'))
    return lines


def main():
    if sys.argv[1] == '--diff':
        before = open(sys.argv[2], encoding='utf-8').read().splitlines()
        after = open(sys.argv[3], encoding='utf-8').read().splitlines()
        diff = list(difflib.unified_diff(before, after, 'before', 'after', lineterm='', n=0))
        print('\n'.join(diff) if diff else 'No differences.')
        return
    version, out = sys.argv[1], sys.argv[2]
    lines = snapshot(version)
    if not lines:
        sys.exit('Nothing published for version ' + version)
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('%d lines written to %s' % (len(lines), out))


if __name__ == '__main__':
    main()
