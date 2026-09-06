<#
.SYNOPSIS
  Agent Appstore 업로드용 zip을 만듭니다. package.py 의 PowerShell 판입니다.

.DESCRIPTION
  이 스크립트가 막으려는 것은 하나입니다: 폴더째 압축하는 것. 플랫폼은 앱 루트를
  기준으로 경로를 읽으므로 엔트리는 `main.py` 여야지 `backend/main.py` 가 되면
  안 됩니다. 전체 업로드는 그래도 멀쩡해 보이지만, 나중에 바뀐 파일만 올릴 때
  기존 파일을 덮어쓰지 못하고 트리 사본이 조용히 하나 더 생깁니다.

  **Compress-Archive 를 쓰지 않습니다.** Windows PowerShell 5.1 의 그것은 엔트리
  이름에 역슬래시를 씁니다(`lib\engine.py`). 플랫폼은 리눅스라 그 zip을 풀면
  `lib/` 디렉토리가 생기는 대신 역슬래시가 이름에 박힌 파일 하나가 루트에
  놓입니다 — 위에 적은 실패가 정확히 이 모양입니다. 그래서 .NET ZipArchive 로
  엔트리 이름을 직접 슬래시로 씁니다.

  검사(check_paths)와 번들 보정(patch_bundle)은 기존 파이썬을 그대로 부릅니다.
  같은 규칙을 PowerShell로 다시 구현하면 두 벌이 서로 어긋나기 시작합니다.

.PARAMETER Since
  이 git 리비전 이후 바뀐 파일만 담습니다 (부분 업로드용). 예: HEAD~1, main
  생략하면 전체 zip을 만듭니다.

.PARAMETER OutDir
  zip을 놓을 디렉토리. 기본값 dist

.EXAMPLE
  .\package.ps1
  전체 zip을 만듭니다.

.EXAMPLE
  .\package.ps1 -Since HEAD~1
  직전 커밋 이후 바뀐 파일만 담아 부분 업로드용 zip을 만듭니다.
#>
[CmdletBinding()]
param(
    [string]$Since,
    [string]$OutDir = "dist"
)

$ErrorActionPreference = "Stop"
$Here = $PSScriptRoot

# package.py 와 같은 목록입니다. 두 스크립트가 서로 다른 것을 담으면 어느 쪽으로
# 만들었느냐에 따라 배포 결과가 달라집니다.
$SkipDirs = @(".venv", "__pycache__", ".git", ".idea", ".pytest_cache",
              ".ruff_cache", "dist", ".appdata")
# .zip 은 손으로 만든 아카이브가 새로 만드는 것 안으로 들어가는 것을 막습니다.
# 앱 1MB가 제 안에 들어앉아도 전체 크기는 그럴듯해 보여서 놓치기 쉽습니다.
$SkipSuffixes = @(".pyc", ".pyo", ".zip")
$SkipNames = @(".env.local.sh", "uv.lock")

$MaxBytes = 200MB


function Get-AppName {
    <#
      pyproject.toml 의 project.name. 하드코딩하지 않는 이유는 이 파일이 실제로
      한 번 어긋났기 때문입니다 — 앱 이름을 바꿨는데 일부만 따라갔습니다.
    #>
    $path = Join-Path $Here "pyproject.toml"
    if (-not (Test-Path $path)) {
        throw "pyproject.toml 이 없습니다: $path"
    }
    $inProject = $false
    foreach ($line in Get-Content $path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if ($trimmed -match '^\[(.+)\]$') {
            # 다른 섹션에도 name 이 있으므로 [project] 안에서만 읽습니다.
            $inProject = ($Matches[1] -eq "project")
            continue
        }
        if ($inProject -and $trimmed -match '^name\s*=\s*"([^"]+)"') {
            return $Matches[1]
        }
    }
    throw "pyproject.toml 의 [project] 에서 name 을 찾지 못했습니다"
}


function Test-Excluded {
    param([string]$Relative)

    foreach ($part in $Relative -split '[\\/]') {
        if ($SkipDirs -contains $part) { return $true }
    }
    $leaf = Split-Path $Relative -Leaf
    if ($SkipNames -contains $leaf) { return $true }
    foreach ($suffix in $SkipSuffixes) {
        if ($leaf.EndsWith($suffix)) { return $true }
    }
    return $false
}


function Get-AllFiles {
    Get-ChildItem -Path $Here -Recurse -File |
        ForEach-Object { $_.FullName.Substring($Here.Length + 1) } |
        Where-Object { -not (Test-Excluded $_) } |
        Sort-Object
}


function Get-ChangedFiles {
    param([string]$Ref)

    # --relative 로 backend/ 기준 경로를 받습니다. 이 스크립트가 만드는 zip의
    # 루트가 backend/ 이므로, 리포지토리 루트 기준 경로를 받으면 전부 어긋납니다.
    $changed = & git -C $Here diff --name-only --relative $Ref
    if ($LASTEXITCODE -ne 0) {
        throw "git diff 실패. -Since 값이 올바른 리비전인지 확인하세요: $Ref"
    }
    # 아직 커밋되지 않은 새 파일도 배포 대상입니다.
    $untracked = & git -C $Here ls-files --others --exclude-standard
    if ($LASTEXITCODE -ne 0) { $untracked = @() }

    $all = @($changed) + @($untracked) |
        Where-Object { $_ } |
        Sort-Object -Unique

    # 삭제된 파일은 diff 에 나오지만 담을 것이 없습니다. zip 에 없는 파일은
    # 그대로 유지되므로, 삭제는 전체 교체로만 됩니다 (가이드 참조).
    $all | Where-Object { Test-Path (Join-Path $Here $_) -PathType Leaf } |
        Where-Object { -not (Test-Excluded $_) }
}


function Invoke-Check {
    param([string]$Script, [string]$Label)

    Write-Host "--- $Label ---"
    # Out-Host 로 보내는 이유: PowerShell 함수는 출력 스트림에 쓴 것을 전부
    # 반환합니다. 그냥 두면 파이썬이 찍은 줄들이 종료코드와 함께 배열로 돌아오고,
    # 호출부의 `-ne 0` 은 배열 비교가 되어 언제나 참입니다 — 검사가 통과해도
    # 실패로 읽힙니다.
    & uv run python $Script | Out-Host
    return $LASTEXITCODE
}


function Write-Zip {
    param([string[]]$Relatives, [string]$Destination)

    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    if (Test-Path $Destination) { Remove-Item $Destination -Force }

    $archive = [System.IO.Compression.ZipFile]::Open($Destination, "Create")
    try {
        foreach ($relative in $Relatives) {
            $source = Join-Path $Here $relative
            # 여기가 이 스크립트의 요점입니다. 엔트리 이름은 앱 루트 기준의
            # 슬래시 경로여야 합니다.
            $entry = $relative -replace '\\', '/'
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $source, $entry, "Optimal") | Out-Null
        }
    }
    finally {
        $archive.Dispose()
    }
}


# ── 실행 ───────────────────────────────────────────────────────────────────
$appName = Get-AppName
$partial = -not [string]::IsNullOrWhiteSpace($Since)

$outPath = Join-Path $Here $OutDir
if (-not (Test-Path $outPath)) {
    New-Item -ItemType Directory -Path $outPath -Force | Out-Null
}
$suffix = ""
if ($partial) { $suffix = "-changed" }
$zipPath = Join-Path $outPath "$appName$suffix.zip"

if ($partial) {
    Write-Host "부분 업로드 zip: $Since 이후 바뀐 파일만"
    $files = @(Get-ChangedFiles -Ref $Since)
    if ($files.Count -eq 0) {
        Write-Host "바뀐 파일이 없습니다. 만들 것이 없습니다."
        exit 0
    }
}
else {
    # 화면 없는 앱이 조용히 배포되는 것보다 여기서 멈추는 편이 낫습니다.
    if (-not (Test-Path (Join-Path $Here "web\index.html"))) {
        Write-Error ("web/index.html 이 없습니다. 프론트엔드를 먼저 빌드해 " +
                     "backend/web 에 넣으세요 (README 참고).")
        exit 1
    }
    $files = @(Get-AllFiles)
}

# 프론트엔드 빌드가 못 고치는 것을 고친 뒤에 검사합니다. 플랫폼이 업로드 시점에
# 같은 검사를 하고 거부하므로, 여기서 둘 다 하면 실패한 업로드가 출력 몇 줄로
# 바뀝니다. 부분 zip에 web/ 파일이 없으면 검사할 것도 없습니다.
$touchesWeb = $files | Where-Object { $_ -like "web*" }
if ($touchesWeb) {
    Invoke-Check "patch_bundle.py" "번들 보정" | Out-Null
    if ((Invoke-Check "check_paths.py" "절대 경로 검사") -ne 0) {
        Write-Error "위 경로들은 배포되면 앱 밖으로 나갑니다. 업로드 전에 고치세요."
        exit 1
    }
    # patch_bundle 이 파일을 고쳤을 수 있으므로 목록을 다시 읽습니다.
    if (-not $partial) { $files = @(Get-AllFiles) }
    Write-Host "---"
}

Write-Zip -Relatives $files -Destination $zipPath

$size = (Get-Item $zipPath).Length
Write-Host ("{0}  ({1} files, {2:N1}MB)" -f $zipPath, $files.Count, ($size / 1MB))

# 만들고 나서 실제 엔트리를 다시 읽어 확인합니다. 의도가 아니라 결과를 봅니다.
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
$check = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = $check.Entries | ForEach-Object { $_.FullName }
}
finally {
    $check.Dispose()
}

$backslashed = $names | Where-Object { $_.Contains("\") }
if ($backslashed) {
    Write-Error ("역슬래시가 든 엔트리가 있습니다. 리눅스에서 디렉토리가 되지 " +
                 "않습니다: " + ($backslashed -join ", "))
    exit 1
}

if ($partial) {
    Write-Host "담긴 파일:"
    $names | ForEach-Object { Write-Host "  $_" }
    Write-Host ("zip 루트 확인: OK (부분 업로드는 pyproject.toml 이 없어도 됩니다)")
}
else {
    $rootOk = ($names -contains "pyproject.toml") -and ($names -contains "main.py")
    $verdict = "실패"
    if ($rootOk) { $verdict = "OK" }
    Write-Host "zip 루트 확인: $verdict"
    if (-not $rootOk) {
        Write-Error "pyproject.toml 과 main.py 가 zip 루트에 있어야 합니다."
        exit 1
    }
}

if ($size -gt $MaxBytes) {
    Write-Error "플랫폼 상한 200MB를 넘었습니다."
    exit 1
}
exit 0
