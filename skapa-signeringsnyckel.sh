#!/usr/bin/env bash
# Skapar en signeringsnyckel för release-versionen av Trace (macOS och Linux).
# Nyckeln och lösenordet sparas lokalt och är undantagna från git.
set -euo pipefail
cd "$(dirname "$0")"

KEYSTORE="nycklar/cellkarta-release.jks"
ALIAS="cellkarta"

SECRETS="nycklar/github-hemligheter.txt"

write_secrets() {
  local pass
  pass="$(grep '^storePassword=' keystore.properties | cut -d= -f2-)"
  {
    echo "Lägg in de här två i GitHub: repot > Settings > Secrets and variables > Actions > New repository secret"
    echo
    echo "Namn:  KEYSTORE_PASSWORD"
    echo "Värde: $pass"
    echo
    echo "Namn:  KEYSTORE_B64"
    echo "Värde (en enda lång rad):"
    base64 < "$KEYSTORE" | tr -d '\n'
    echo
  } > "$SECRETS"
  chmod 600 "$SECRETS"
  echo
  echo "Värdena för GitHub ligger i $SECRETS"
  echo "Radera den filen när du har lagt in hemligheterna."
}

if [ -e "$KEYSTORE" ] && [ -e keystore.properties ]; then
  echo "Signeringsnyckeln finns redan. Den skrivs inte över."
  write_secrets
  exit 0
fi
if [ -e "$KEYSTORE" ] || [ -e keystore.properties ]; then
  echo "Bara en av $KEYSTORE och keystore.properties finns. Ta bort den och kör skriptet igen."
  exit 1
fi

KEYTOOL="$(command -v keytool || true)"
if [ -z "$KEYTOOL" ]; then
  for c in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
    "$HOME/android-studio/jbr/bin/keytool" \
    "/opt/android-studio/jbr/bin/keytool"; do
    if [ -x "$c" ]; then KEYTOOL="$c"; break; fi
  done
fi
if [ -z "$KEYTOOL" ]; then
  echo "Hittar inte keytool. Installera Android Studio eller en Java JDK och kör skriptet igen."
  exit 1
fi

mkdir -p nycklar
PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-28)"

"$KEYTOOL" -genkeypair \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Trace, O=Privat, C=SE" >/dev/null

cat > keystore.properties <<EOF
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
chmod 600 keystore.properties "$KEYSTORE"

echo "Klart."
echo "  Nyckel:   $KEYSTORE"
echo "  Lösenord: keystore.properties"
echo
write_secrets
echo
echo "Säkerhetskopiera nyckeln och keystore.properties, till exempel i en lösenordshanterare."
echo "Utan dem kan du inte släppa uppdateringar av appen med samma signatur."
