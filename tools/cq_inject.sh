#!/bin/bash
# cq_inject.sh "f7 0c 0c 0d 00 00 31 62" ... : send messages to the desk through a running cq_mitm.py (port 51399)
for m in "$@"; do echo "$m"; done | nc -w 1 127.0.0.1 51399
