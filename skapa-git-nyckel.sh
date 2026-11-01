#!/usr/bin/env bash
# Skapar en SSH-nyckel så att den här datorn kan hämta och pusha till GitHub (macOS och Linux).
# Den privata nyckeln stannar på datorn. Bara den publika delen läggs in på GitHub.
set -euo pipefail

KEY="$HOME/.ssh/id_ed25519"
URL="https://github.com/settings/ssh/new"

if ! command -v ssh-keygen >/dev/null; then
  echo "ssh-keygen saknas. Installera OpenSSH och kör skriptet igen."
  exit 1
fi

if [ -f "$KEY" ]; then
  echo "Du har redan en nyckel: $KEY.pub"
else
  mkdir -p "$HOME/.ssh"
  chmod 700 "$HOME/.ssh"
  echo "Skapar ny nyckel. Välj gärna ett lösenord när du blir tillfrågad."
  ssh-keygen -t ed25519 -C "trace-$(hostname)" -f "$KEY"
fi

PUB="$(cat "$KEY.pub")"
copied=no
if command -v pbcopy >/dev/null; then printf %s "$PUB" | pbcopy && copied=yes
elif command -v wl-copy >/dev/null; then printf %s "$PUB" | wl-copy && copied=yes
elif command -v xclip >/dev/null; then printf %s "$PUB" | xclip -selection clipboard && copied=yes
fi

echo
echo "Din publika nyckel:"
echo "$PUB"
echo
if [ "$copied" = yes ]; then echo "Den är kopierad till urklipp."; fi
echo "Klistra in den på GitHub och spara: $URL"
if [ "$(uname)" = "Darwin" ]; then open "$URL" || true
elif command -v xdg-open >/dev/null; then xdg-open "$URL" >/dev/null 2>&1 || true
fi

echo
read -r -p "Tryck Enter när nyckeln är sparad på GitHub… " _
ssh -o StrictHostKeyChecking=accept-new -T git@github.com || true

echo
echo "Står det 'successfully authenticated' ovan är du klar. Hämta projektet med:"
echo "  git clone git@github.com:menola8803/Trace.git"
