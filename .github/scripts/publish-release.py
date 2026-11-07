"""Publish immutable APKs from a successful, tag-matching Android CI run."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(command('gh', 'api', path))


def inputs():
    if os.environ.get('INSPECT_RUN_ID'):
        raise ValueError('Choose publication or report inspection, not both')
    repo = os.environ['GH_REPO']
    tag = os.environ['RELEASE_TAG']
    run_id = os.environ['BUILD_RUN_ID']
    if not re.fullmatch(r'[\w.-]+/[\w.-]+', repo):
        raise ValueError('Invalid repository')
    if not re.fullmatch(r'v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', tag):
        raise ValueError('Release tag must be a version such as v1.3.0')
    if not re.fullmatch(r'[1-9]\d{0,19}', run_id):
        raise ValueError('A completed Android CI run ID is required')
    return repo, tag, run_id


def validate_run(run, repo, sha):
    if run['repository']['full_name'] != repo or run.get('head_repository', {}).get('full_name') != repo:
        raise ValueError('Build must belong to this repository')
    if run['status'] != 'completed' or run['conclusion'] != 'success':
        raise ValueError('Build and device tests must finish successfully before publication')
    if run['event'] not in ('workflow_dispatch', 'push'):
        raise ValueError('Use a branch/tag build, not a pull request merge build')
    if run['head_sha'] != sha:
        raise ValueError('Build commit does not match the release tag')
    if run['path'] != '.github/workflows/android.yml':
        raise ValueError('Build must come from Android CI')


def validate():
    repo, tag, run_id = inputs()
    ref = api(f'repos/{repo}/git/ref/tags/{tag}')['object']
    for _ in range(5):
        if ref['type'] != 'tag':
            break
        ref = api(f'repos/{repo}/git/tags/{ref["sha"]}')['object']
    if ref['type'] != 'commit' or not re.fullmatch(r'[0-9a-f]{40}', ref['sha']):
        raise ValueError('Tag must resolve to a commit')
    sha = ref['sha']
    run = api(f'repos/{repo}/actions/runs/{run_id}')
    validate_run(run, repo, sha)
    jobs = api(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100&filter=latest')['jobs']
    for name in ('Build, test and lint', 'Android 35 device tests'):
        if not any(j['name'] == name and j['status'] == 'completed' and j['conclusion'] == 'success' for j in jobs):
            raise ValueError(f'Missing successful job: {name}')
    artifacts = api(f'repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100')['artifacts']
    expected = ('WebShortcuts-debug', 'WebShortcuts-release-unsigned', 'verification-reports', 'device-test-reports')
    selected = []
    for name in expected:
        matches = [a for a in artifacts if a['name'] == name and not a['expired']]
        if len(matches) != 1:
            raise ValueError(f'Missing or ambiguous artifact: {name}')
        selected.append({k: matches[0].get(k) for k in ('id', 'name', 'digest')})
    value = {'repository': repo, 'tag': tag, 'commit': sha, 'buildRunId': int(run_id),
             'buildAttempt': run['run_attempt'], 'buildUrl': run['html_url'], 'artifacts': selected}
    Path('release-validation.json').write_text(json.dumps(value, indent=2) + '\n')
    print(f'Validated {tag} -> {sha}, Android CI run {run_id}')


def validation():
    value = json.loads(Path('release-validation.json').read_text())
    repo, tag, run_id = inputs()
    if (value['repository'], value['tag'], str(value['buildRunId'])) != (repo, tag, run_id):
        raise ValueError('Publication inputs changed after validation')
    return value


def test_counts(directory):
    files = list(Path(directory).rglob('TEST-*.xml'))
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    for path in files:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == 'testsuite' else list(root.iter('testsuite'))
        for suite in suites:
            for key in counts:
                counts[key] += int(suite.get(key, '0'))
    if counts['tests'] == 0 or counts['failures'] or counts['errors']:
        raise ValueError(f'Missing or failing test reports in {directory}: {counts}')
    return counts


def package():
    value = validation()
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    paths = {'debug': Path('downloaded-apks/WebShortcuts-debug/app-debug.apk'),
             'release-unsigned': Path('downloaded-apks/WebShortcuts-release-unsigned/app-release-unsigned.apk')}
    with zipfile.ZipFile(paths['release-unsigned']) as apk:
        embedded = apk.read('META-INF/version-control-info.textproto').decode()
        if value['commit'] not in re.findall(r'revision:\s*"([0-9a-f]{40})"', embedded):
            raise ValueError('Release APK embeds a different source commit')
    value['tests'] = {'jvm': test_counts('downloaded-reports/jvm'), 'android35': test_counts('downloaded-reports/device')}
    value['publicationRunId'] = os.environ['GITHUB_RUN_ID']
    value['publicationWorkflowCommit'] = os.environ['GITHUB_SHA']
    value['publicationUrl'] = f'https://github.com/{value["repository"]}/actions/runs/{value["publicationRunId"]}'
    prefix = f'WebShortcuts-{value["tag"]}-{value["commit"][:7]}-ci-{value["buildRunId"]}-{value["buildAttempt"]}'
    output = Path('release-assets')
    output.mkdir(exist_ok=False)
    value['apks'] = []
    for mode, path in paths.items():
        badging = command(str(tools / 'aapt'), 'dump', 'badging', str(path))
        version = re.search(r"versionName='([^']+)'", badging).group(1)
        version_code = int(re.search(r"versionCode='(\d+)'", badging).group(1))
        if version != value['tag'].removeprefix('v') or "name='io.github.homura251.webshortcuts'" not in badging:
            raise ValueError('APK package/version does not match the requested release')
        name = f'{prefix}-{mode}.apk'
        shutil.copyfile(path, output / name)
        value['apks'].append({'file': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                              'bytes': path.stat().st_size, 'versionName': version, 'versionCode': version_code})
    certificates = command(str(tools / 'apksigner'), 'verify', '--print-certs', str(paths['debug']))
    value['debugCertificateSha256'] = re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})', certificates).group(1)
    value['releaseSigned'] = False
    (output / f'{prefix}-BUILD-INFO.json').write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    hashes = ''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in sorted(output.iterdir()))
    (output / f'{prefix}-SHA256SUMS.txt').write_text(hashes)
    debug_name = value['apks'][0]['file']
    jvm, device = value['tests']['jvm'], value['tests']['android35']
    notes = f'''WebShortcuts Android 构建产物，来源版本、提交及验证记录如下。\n\n版本：{version}（{version_code}） · 提交：`{value['commit']}`\n\n### 下载\n\n- **直接安装**：`{debug_name}`，由 GitHub Actions 构建并使用 CI 测试签名。\n- `release-unsigned.apk`：R8 未签名包，需要自行正式签名。\n- `SHA256SUMS.txt` 与 `BUILD-INFO.json`：实际文件校验值、来源提交及验证记录。\n\nCI 测试签名与此前本地交付包不同。更换签名前，请先在旧版导出 ZIP 备份，再卸载旧版安装；如签名相同可直接覆盖安装。\n\n### 验证\n\n[Android CI]({value['buildUrl']}) 已成功：JVM/Robolectric/Compose {jvm['tests']} 项测试，Android 35 模拟器 {device['tests']} 项测试（跳过 {device['skipped']} 项），失败和错误均为 0。Debug、R8 Release、Lint 及权限检查通过。模拟器结果不代表 OEM 真机验收。\n'''
    Path('RELEASE-NOTES.md').write_text(notes)
    print(f'Prepared {len(list(output.iterdir()))} immutable assets for {value["tag"]}')


def publish():
    value = validation()
    repo, tag, run_id = inputs()
    # Recheck the tag and source run immediately before any remote release write.
    validate()
    if validation() != value:
        raise ValueError('Release tag, build attempt or artifacts changed during publication')
    found = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'isDraft,assets,url'],
                           capture_output=True, text=True)
    if found.returncode:
        if 'not found' not in found.stderr.lower() and '404' not in found.stderr:
            raise RuntimeError(found.stderr)
        command('gh', 'release', 'create', tag, '--repo', repo, '--verify-tag', '--draft', '--prerelease',
                '--title', f'WebShortcuts {tag}', '--notes-file', 'RELEASE-NOTES.md')
        found = subprocess.run(['gh', 'release', 'view', tag, '--repo', repo, '--json', 'isDraft,assets,url'],
                               capture_output=True, text=True, check=True)
    release = json.loads(found.stdout)
    existing = {a['name']: a for a in release['assets']}
    files = sorted(Path('release-assets').iterdir())
    existing_downloads = Path('existing-downloads')
    existing_downloads.mkdir(exist_ok=False)
    for path in files:
        if path.name in existing:
            command('gh', 'release', 'download', tag, '--repo', repo, '--pattern', path.name, '--dir', str(existing_downloads))
            if hashlib.sha256((existing_downloads / path.name).read_bytes()).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise ValueError(f'Refusing to replace existing asset: {path.name}')
        else:
            command('gh', 'release', 'upload', tag, str(path), '--repo', repo)
    verify = Path('verified-downloads')
    verify.mkdir(exist_ok=False)
    for path in files:
        command('gh', 'release', 'download', tag, '--repo', repo, '--pattern', path.name, '--dir', str(verify))
        if hashlib.sha256((verify / path.name).read_bytes()).digest() != hashlib.sha256(path.read_bytes()).digest():
            raise ValueError(f'Release download checksum mismatch: {path.name}')
    if release['isDraft']:
        command('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false', '--prerelease', '--notes-file', 'RELEASE-NOTES.md')
    url = command('gh', 'release', 'view', tag, '--repo', repo, '--json', 'url', '--jq', '.url')
    print(f'Published and verified {url}')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'Published and downloaded back all {len(files)} assets: {url}\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['validate', 'package', 'publish'])
    {'validate': validate, 'package': package, 'publish': publish}[parser.parse_args().phase]()
