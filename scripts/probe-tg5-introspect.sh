#!/bin/sh
# introspects TOK (obtained via port-forward) in-cluster; prints active flag
echo "tok-len:${#TOK}"
curl -s -X POST http://eim-keycloak:8080/realms/eim/protocol/openid-connect/token/introspect \
  -d "token=$TOK&client_id=eim-gateway&client_secret=$SEC" | grep -o '"active":[a-z]*'
sleep 10
