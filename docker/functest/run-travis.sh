#!/usr/bin/env bash

DIR=$( cd $(dirname $0) ; pwd -P )

echo "run-travis DIR: $DIR"

VINYLDNS_URL="http://localhost:9000"
echo "Waiting for API to be ready at ${VINYLDNS_URL} ..."
DATA=""
RETRY=40
while [ "$RETRY" -gt 0 ]
do
    DATA=$(wget -O - -q -t 1 "${VINYLDNS_URL}/ping")
    if [ $? -eq 0 ]
    then
        break
    else
        echo "Retrying Again" >&2

        let RETRY-=1
        sleep 1

        if [ "$RETRY" -eq 0 ]
        then
          echo "Exceeded retries waiting for VINYLDNS to be ready, failing"
          exit 1
        fi
    fi
done

DNS_IP="172.18.0.2"
DIG_IP=$(dig +short vinyldns-bind9)
echo "DIG IP: $DIG_IP"
echo "Running live tests against ${VINYLDNS_URL} and DNS server ${DNS_IP}"

docker ps

chmod +x "$DIR"/run-tests.py
#ls -l "$DIR"
#"$DIR"/run-tests.py "$DIR"/functional_test/live_tests/* -v --url="$VINYLDNS_URL" --dns-ip="$DNS_IP"
"$DIR"/run-tests.py "$DIR"/functional_test/live_tests/* -v --url="$VINYLDNS_URL" --dns-ip="$DNS_IP"
