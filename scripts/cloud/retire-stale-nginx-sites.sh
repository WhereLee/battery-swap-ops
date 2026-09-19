#!/usr/bin/env bash
# Retire stale nginx site configs left behind by the 2026-09-11 project cleanup.
#
# Why: /etc/nginx/sites-enabled still contained club-agent (root points at the DELETED
# /opt/club-agent/frontend/dist) and grab-system (server_name 124.223.36.154, root also
# gone). club-agent also declares `server_name _`, which collides with swap-web and makes
# nginx log "conflicting server name" at every reload; grab-system wins by exact match for
# Host: 124.223.36.154, i.e. browsing the server by IP hits a dead site instead of the
# console.
#
# How: DISABLE, do not delete - the files move back to sites-available/ (Debian convention:
# installed but not enabled), so `ln -sf ../sites-available/<name> .` restores them.
set -e

STAMP=$(date +%Y%m%d_%H%M%S)
for SITE in club-agent grab-system; do
    if [ -e "/etc/nginx/sites-enabled/$SITE" ]; then
        if [ -L "/etc/nginx/sites-enabled/$SITE" ]; then
            # club-agent is a symlink; dropping the link IS the disable, the target stays
            sudo rm -f "/etc/nginx/sites-enabled/$SITE"
            echo "disabled (symlink removed): $SITE -> $(readlink -f /etc/nginx/sites-available/$SITE 2>/dev/null || echo '?')"
        else
            # grab-system is a plain file: keep a timestamped copy, then move it out
            sudo cp -a "/etc/nginx/sites-enabled/$SITE" "/etc/nginx/sites-available/$SITE.disabled-$STAMP"
            sudo rm -f "/etc/nginx/sites-enabled/$SITE"
            echo "disabled (archived to sites-available/$SITE.disabled-$STAMP): $SITE"
        fi
    else
        echo "already absent: $SITE"
    fi
done

echo "remaining enabled sites:"
ls -1 /etc/nginx/sites-enabled/

sudo nginx -t
sudo systemctl reload nginx
echo "nginx=$(systemctl is-active nginx)"

echo "== verification: Host-based routing no longer reaches dead sites =="
printf 'Host: 127.0.0.1   -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: 127.0.0.1' http://127.0.0.1/)"
printf 'Host: <server-ip> -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: 124.223.36.154' http://127.0.0.1/)"
printf 'console deep link -> %s\n' "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1/work-orders/1)"
