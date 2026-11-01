# Skapar en signeringsnyckel för release-versionen av Cellkarta (Windows).
# Kör i PowerShell:  powershell -ExecutionPolicy Bypass -File .\skapa-signeringsnyckel.ps1
# Nyckeln och lösenordet sparas lokalt och är undantagna från git.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$keystore = "nycklar/cellkarta-release.jks"
$alias = "cellkarta"

$secrets = "nycklar/github-hemligheter.txt"

function Write-Secrets {
    $pass = ((Get-Content "keystore.properties" | Where-Object { $_ -like "storePassword=*" }) -replace "^storePassword=", "")
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $keystore)))
    @"
Lägg in de här två i GitHub: repot > Settings > Secrets and variables > Actions > New repository secret

Namn:  KEYSTORE_PASSWORD
Värde: $pass

Namn:  KEYSTORE_B64
Värde (en enda lång rad):
$b64
"@ | Set-Content -Encoding UTF8 $secrets
    Write-Host ""
    Write-Host "Värdena för GitHub ligger i $secrets"
    Write-Host "Radera den filen när du har lagt in hemligheterna."
}

if ((Test-Path $keystore) -and (Test-Path "keystore.properties")) {
    Write-Host "Signeringsnyckeln finns redan. Den skrivs inte över."
    Write-Secrets
    exit 0
}
if ((Test-Path $keystore) -or (Test-Path "keystore.properties")) {
    Write-Host "Bara en av $keystore och keystore.properties finns. Ta bort den och kör skriptet igen."
    exit 1
}

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $candidates = @(
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
    )
    $keytool = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $keytool) {
    Write-Host "Hittar inte keytool. Installera Android Studio eller en Java JDK och kör skriptet igen."
    exit 1
}

New-Item -ItemType Directory -Force -Path "nycklar" | Out-Null

$chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".ToCharArray()
$bytes = New-Object byte[] 28
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })

& $keytool -genkeypair `
    -keystore $keystore -storetype PKCS12 `
    -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $pass -keypass $pass `
    -dname "CN=Cellkarta, O=Privat, C=SE" | Out-Null

@"
storeFile=$keystore
storePassword=$pass
keyAlias=$alias
keyPassword=$pass
"@ | Set-Content -Encoding ASCII "keystore.properties"

Write-Host "Klart."
Write-Host "  Nyckel:   $keystore"
Write-Host "  Lösenord: keystore.properties"
Write-Host ""
Write-Secrets
Write-Host ""
Write-Host "Säkerhetskopiera nyckeln och keystore.properties, till exempel i en lösenordshanterare."
Write-Host "Utan dem kan du inte släppa uppdateringar av appen med samma signatur."
