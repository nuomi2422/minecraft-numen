[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)] [string]$Module,
    [Parameter(Mandatory)] [string]$Version,
    [string]$Root = (Join-Path (Get-Location) "config\numen\selfcompile")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$rootPath = [IO.Path]::GetFullPath($Root)
$stablePath = [IO.Path]::GetFullPath((Join-Path $rootPath "stable\$Module\$Version"))
$manifestPath = Join-Path $stablePath "manifest.json"
$activePath = Join-Path $rootPath "active.json"

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Verified module version not found: $stablePath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ([string]$manifest.state -ne "VERIFIED") {
    throw "Refusing rollback: manifest state is '$($manifest.state)', expected VERIFIED"
}
if ([string]$manifest.id -eq "") {
    throw "Refusing rollback: manifest has no id"
}

$current = [ordered]@{}
if (Test-Path -LiteralPath $activePath -PathType Leaf) {
    $loaded = Get-Content -LiteralPath $activePath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($property in $loaded.PSObject.Properties) {
        $current[$property.Name] = $property.Value
    }
}
$current[$Module] = [ordered]@{
    version = $Version
    manifest = $manifest.id
    path = $stablePath
    updatedAt = [DateTime]::UtcNow.ToString("O")
}

$json = $current | ConvertTo-Json -Depth 8
$tmp = "$activePath.$PID.tmp"
if ($PSCmdlet.ShouldProcess($activePath, "activate verified $Module/$Version")) {
    $parent = Split-Path -Parent $activePath
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText($tmp, "$json`n", [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tmp -Destination $activePath -Force
    Write-Output "Activated verified module: $Module/$Version"
}
