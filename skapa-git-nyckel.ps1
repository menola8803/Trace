# Skapar en SSH-nyckel så att den här datorn kan hämta och pusha till GitHub (Windows).
# Kör i PowerShell:  powershell -ExecutionPolicy Bypass -File .\skapa-git-nyckel.ps1
# Den privata nyckeln stannar på datorn. Bara den publika delen läggs in på GitHub.

$sshDir = Join-Path $env:USERPROFILE ".ssh"
$key = Join-Path $sshDir "id_ed25519"
$url = "https://github.com/settings/ssh/new"

if (-not (Get-Command ssh-keygen -ErrorAction SilentlyContinue)) {
    Write-Host "ssh-keygen saknas. Slå på OpenSSH-klienten under"
    Write-Host "Inställningar > System > Valfria funktioner > OpenSSH Client, och kör skriptet igen."
    exit 1
}

if (Test-Path $key) {
    Write-Host "Du har redan en nyckel: $key.pub"
} else {
    New-Item -ItemType Directory -Force -Path $sshDir | Out-Null
    Write-Host "Skapar ny nyckel. Välj gärna ett lösenord när du blir tillfrågad."
    ssh-keygen -t ed25519 -C "trace-$env:COMPUTERNAME" -f $key
    if (-not (Test-Path "$key.pub")) { Write-Host "Nyckeln skapades inte."; exit 1 }
}

$pub = (Get-Content "$key.pub" -Raw).Trim()
Set-Clipboard -Value $pub

Write-Host ""
Write-Host "Din publika nyckel (kopierad till urklipp):"
Write-Host $pub
Write-Host ""
Write-Host "Klistra in den på GitHub och spara: $url"
Start-Process $url

Write-Host ""
Read-Host "Tryck Enter när nyckeln är sparad på GitHub"
ssh -o StrictHostKeyChecking=accept-new -T git@github.com

Write-Host ""
Write-Host "Står det 'successfully authenticated' ovan är du klar. Hämta projektet med:"
Write-Host "  git clone git@github.com:menola8803/Trace.git"
