#!/usr/bin/env bash
# Skapar en signeringsnyckel för release-versionen av Cellkarta (macOS och Linux).
# Nyckeln och lösenordet sparas lokalt och är undantagna från git.
set -euo pipefail
cd "$(dirname "$0")"

KEYSTORE="nycklar/cellkarta-release.jks"
ALIAS="cellkarta"

if [ -e "$KEYSTORE" ] || [ -e keystore.properties ]; then
  echo "Det finns redan en signeringsnyckel. Den skrivs inte över."
  echo "Ta bort $KEYSTORE och keystore.properties själv om du verkligen vill börja om."
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
  -dname "CN=Cellkarta, O=Privat, C=SE" >/dev/null

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
echo "Säkerhetskopiera båda filerna, till exempel i en lösenordshanterare."
echo "Utan dem kan du inte släppa uppdateringar av appen med samma signatur."
