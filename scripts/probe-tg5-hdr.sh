#!/bin/sh
# header sanity probe: prints the token length the pod received, then hits
# the gateway /me route with it.
echo "tok-len:${#TOK}"
curl -s -o /dev/null -w "me=%{http_code}\n" http://eim-apisix:9080/api/v1/me/payee \
  -H "Authorization: Bearer $TOK" \
  -H "X-Tenant-Id: 11111111-0000-0000-0000-000000000001" \
  -H "X-Payee-Id: a1b2c3d4-0000-0000-0000-000000000002" \
  -H "X-Actor: sarah" -H "X-Roles: payee"
sleep 15
