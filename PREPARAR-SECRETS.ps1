$ErrorActionPreference = 'Stop'

$keystore = Read-Host 'Caminho completo da cofre-release.jks'
$googleServices = Read-Host 'Caminho completo do google-services.json'

if (-not (Test-Path $keystore)) { throw "Keystore não encontrada: $keystore" }
if (-not (Test-Path $googleServices)) { throw "google-services.json não encontrado: $googleServices" }

$keystore64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))
$google64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($googleServices))

Set-Content -Path 'COFRE_KEYSTORE_BASE64.txt' -Value $keystore64 -NoNewline
Set-Content -Path 'GOOGLE_SERVICES_JSON_BASE64.txt' -Value $google64 -NoNewline

Write-Host ''
Write-Host 'Foram criados:'
Write-Host '  COFRE_KEYSTORE_BASE64.txt'
Write-Host '  GOOGLE_SERVICES_JSON_BASE64.txt'
Write-Host 'Copie o conteúdo de cada ficheiro para o Secret correspondente no GitHub.'
Write-Host 'Apague estes ficheiros depois de criar os Secrets.'
