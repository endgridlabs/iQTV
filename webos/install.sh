#!/usr/bin/env bash
# Installs EndGrid iQTV+ on an LG TV in Developer Mode, over your Wi-Fi. Needs bash, curl and OpenSSH (ssh, ssh-keygen):
# on Windows, Git Bash has them.
#
#   ./webos/install.sh 192.168.1.50              # install or update, then open it
#   ./webos/install.sh 192.168.1.50 --uninstall  # remove it
#
# Before the first install, on the TV: install "Developer Mode" from the LG Content Store, sign in with a free LG
# developer account (webostv.developer.lge.com), turn on Dev Mode Status (the TV restarts), then turn on Key Server.
# The app shows the TV's IP address and a passphrase.
#
# The first time, this downloads the TV's key from its Key Server and asks for that passphrase (type it here; it is
# not stored). The unlocked key is kept in ~/.ssh/iqtv_webos_<IP>, so updates need neither. The app is copied over
# SSH (port 9922) and installed with the TV's own installer (com.webos.appInstallService), the way LG's tools do it.
set -euo pipefail
cd "$(dirname "$0")/.."

IP="${1:-}"
[[ "$IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
ACTION="${2:-}"
command -v ssh >/dev/null && command -v ssh-keygen >/dev/null || { echo "OpenSSH (ssh and ssh-keygen) is needed."; exit 1; }
ID="$(sed -n 's/.*"id": *"\([^"]*\)".*/\1/p' webos/appinfo.json | head -1)"
IPK=iQTV-webos.ipk
TEMP=/media/developer/temp/iqtv.ipk
KEY="$HOME/.ssh/iqtv_webos_${IP//./_}"
SSH_OPTS=(-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR
          -o HostKeyAlgorithms=+ssh-rsa -o PubkeyAcceptedKeyTypes=+ssh-rsa -p 9922)

# The TV's SSH key from its Key Server, unlocked with the passphrase the TV shows.
if [ ! -f "$KEY" ]; then
  echo "Getting the TV's key from http://$IP:9991/webos_rsa ..."
  tmp="$(mktemp)"
  if ! curl -fsS --max-time 6 -o "$tmp" "http://$IP:9991/webos_rsa"; then
    rm -f "$tmp"
    echo "Couldn't reach the Key Server. In the TV's Developer Mode app, turn on Key Server."; exit 1
  fi
  chmod 600 "$tmp"
  read -r -s -p "Passphrase shown in Developer Mode on the TV: " PASS; echo
  if ! ssh-keygen -p -P "$PASS" -N '' -f "$tmp" >/dev/null 2>&1; then
    rm -f "$tmp"
    echo "That passphrase didn't unlock the key. Check it on the TV (Developer Mode) and try again."; exit 1
  fi
  mkdir -p "$HOME/.ssh"
  mv "$tmp" "$KEY"
  chmod 600 "$KEY"
fi

# A command on the TV: tv [-tt] '<command>'.
tv() {
  local tty=()
  if [ "$1" = -tt ]; then tty=(-tt); shift; fi
  ssh ${tty[@]+"${tty[@]}"} "${SSH_OPTS[@]}" -i "$KEY" "prisoner@$IP" "$1"
}

# One call on the TV's service bus. luna-send-pub prints nothing without a terminal (-tt), and the installer streams
# several replies. Older webOS ships BusyBox before 1.30, whose timeout only takes -t SECS. Some (webOS 6) refuse
# the terminal; there -i streams the replies without one, until the timeout.
luna() {   # luna <uri> <json> <replies> <seconds>
  local t="if timeout -t 1 true 2>/dev/null; then T='timeout -t $4'; else T='timeout $4'; fi; "
  local out
  out="$(tv -tt "$t\$T luna-send-pub -n $3 $1 '$2'" 2>/dev/null || true)"
  [ -n "${out//[[:space:]]/}" ] || out="$(tv "$t\$T luna-send-pub -i $1 '$2' </dev/null" 2>/dev/null || true)"
  printf '%s' "$out"
}

if [ "$ACTION" = "--uninstall" ]; then
  out="$(luna luna://com.webos.appInstallService/dev/remove "{\"id\":\"$ID\"}" 3 30)"
  case "$out" in *'"returnValue":true'*) echo "Removed." ;; *) echo "The TV said: $out" ;; esac
  exit 0
fi

[ -f "$IPK" ] || ./webos/build.sh
echo "Copying the app to the TV ..."
if ! tv "mkdir -p /media/developer/temp && cat > $TEMP" < "$IPK"; then
  echo "Couldn't copy it. Is Developer Mode still on? If the key changed, delete $KEY and try again."; exit 1
fi
echo "Installing ..."
out="$(luna luna://com.webos.appInstallService/dev/install "{\"id\":\"$ID\",\"ipkUrl\":\"$TEMP\",\"subscribe\":true}" 30 60)"
case "$out" in
  *'"state":"installed"'*) ;;
  *) echo "The TV didn't finish installing. It said:"; echo "$out"; exit 1 ;;
esac
tv "rm -f $TEMP" || true
luna luna://com.webos.applicationManager/launch "{\"id\":\"$ID\"}" 1 20 >/dev/null
echo "Installed and open on the TV."
echo "Tip: while EndGrid iQTV+ is open, hold a number key on the remote to open it with that key from anywhere."
echo "Developer Mode lasts about 40 days: open the Developer Mode app now and then and press EXTEND."
