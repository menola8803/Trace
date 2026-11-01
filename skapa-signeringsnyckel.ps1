# Skapar en signeringsnyckel för release-versionen av Cellkarta (Windows).
# Kör i PowerShell:  powershell -ExecutionPolicy Bypass -File .\skapa-signeringsnyckel.ps1
# Nyckeln och lösenordet sparas lokalt och är undantagna från git.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$keystore = "nycklar/cellkarta-release.jks"
$alias = "cellkarta"

if ((Test-Path $keystore) -or (Test-Path "keystore.properties")) {
    Write-Host "Det finns redan en signeringsnyckel. Den skrivs inte över."
    Write-Host "Ta bort $keystore och keystore.properties själv om du verkligen vill börja om."
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
Write-Host "Säkerhetskopiera båda filerna, till exempel i en lösenordshanterare."
Write-Host "Utan dem kan du inte släppa uppdateringar av appen med samma signatur."
