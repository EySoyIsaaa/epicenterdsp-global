param(
    [Parameter(Mandatory = $true)]
    [string]$AospRoot
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $AospRoot "hardware/interfaces/audio/aidl/default/epicenter"
$expected = Join-Path $AospRoot "hardware/interfaces/audio/aidl/default/Android.bp"

if (-not (Test-Path -LiteralPath $expected -PathType Leaf)) {
    throw "No parece ser un checkout AOSP Android 14+: falta $expected"
}
if (Test-Path -LiteralPath $target) {
    throw "El destino ya existe; revísalo y muévelo manualmente antes de volver a copiar: $target"
}

$source = Join-Path $repoRoot "integration/hardware/interfaces/audio/aidl/default/epicenter"
New-Item -ItemType Directory -Path $target | Out-Null
Copy-Item -LiteralPath (Join-Path $source "Android.bp") -Destination $target
Copy-Item -LiteralPath (Join-Path $source "EpicenterEffect.cpp") -Destination $target
Copy-Item -LiteralPath (Join-Path $source "EpicenterEffect.h") -Destination $target
Copy-Item -LiteralPath (Join-Path $source "audio_effects_config.fragment.xml") -Destination $target
Copy-Item -LiteralPath (Join-Path $repoRoot "engines") -Destination $target -Recurse
Write-Output "Prototipo copiado a: $target"
Write-Output "Siguiente: agrega libepicenter_effect al producto y fusiona el fragmento XML con la configuración de efectos existente."
