#!/usr/bin/env bash
#
# Walks the definition of done against a running EkoAlert instance.
#
#   docker run -d --name ekoalert-db \
#     -e POSTGRES_DB=ekoalert -e POSTGRES_USER=ekoalert -e POSTGRES_PASSWORD=ekoalert \
#     -p 5433:5432 postgis/postgis:16-3.4
#   ./mvnw -DskipTests package
#   EKOALERT_DB_URL='jdbc:postgresql://localhost:5433/ekoalert?stringtype=unspecified' \
#     java -jar app/target/app-0.1.0-SNAPSHOT.jar --seed --demo-users \
#          --ekoalert.de-escalation-sweep=PT5S
#   ./demo.sh
#
# The shorter sweep interval is only to keep the demo brisk. Production uses the
# default of one minute.

set -euo pipefail

BASE="${EKOALERT_BASE:-http://localhost:8080/api/v1}"
PASSWORD="${EKOALERT_DEMO_PASSWORD:-ekoalert-demo}"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '  %s\n' "$*"; }

jqp() { python3 -c "$1"; }

login() {
  curl -sS -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}" \
    | jqp 'import sys,json; print(json.load(sys.stdin)["token"])'
}

report() {
  # report <token> <level> <observedAt>
  curl -sS -X POST "$BASE/reports" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' \
    -d "{\"level\":\"$2\",\"observedAt\":\"$3\"}"
}

show_report() {
  jqp 'import sys,json
d=json.load(sys.stdin)
print("  escalated:", d["escalated"], " quorum:", d.get("quorumLevel", "none"))
for a in d["alerts"]:
    print("   ", a["originZone"], "->", a["targetZone"], a["level"],
          "eta", a["etaMinutes"], "min   ",
          "DELIVERED" if a.get("suppressedBy") is None else "suppressed: " + a["suppressedBy"])'
}

counts() {
  curl -sS "$BASE/graph" | jqp 'import sys,json
c=json.load(sys.stdin)["counts"]
print("  zones=%d edges=%d inferred=%d confirmed=%d rejected=%d blocked=%d"
      % (c["zones"], c["edges"], c["inferred"], c["confirmed"], c["rejected"], c["blocked"]))'
}

wait_until_clear() {
  for _ in $(seq 1 30); do
    local active
    active=$(curl -sS "$BASE/zones/Z01" | jqp 'import sys,json; print(json.load(sys.stdin)["zone"]["status"]["active"])')
    [ "$active" = "False" ] && { note "Z01 has cleared, all-clear sent to everyone who heard from it"; return 0; }
    sleep 2
  done
  note "Z01 did not clear. Start the app with --ekoalert.de-escalation-sweep=PT5S to speed this up."
  return 1
}

curl -sS -o /dev/null "$BASE/graph" 2>/dev/null || { echo "No instance at $BASE"; exit 1; }

# The seed runs after the web server is already accepting requests, so a
# reachable /graph is not the same as a seeded database, and the zones land a
# moment before the demo users do. Wait on the login, which is the real
# precondition and the last thing the seed command does.
ADA=""
for _ in $(seq 1 60); do
  ADA=$(login ada 2>/dev/null || true)
  [ -n "$ADA" ] && break
  sleep 1
done
if [ -z "$ADA" ]; then
  echo "No demo users at $BASE. Start the app with --seed --demo-users."
  exit 1
fi
BOLA=$(login bola); ADMIN=$(login admin)

say "1. The map is complete on day one"
counts
note "Every seeded edge is inferred. The map is full and the system is silent."

say "2. One report does not escalate"
report "$ADA" IMPASSABLE "2026-06-15T12:00:00Z" | show_report

say "3. A second report from a different reporter escalates, and still delivers nothing"
report "$BOLA" IMPASSABLE "2026-06-15T12:10:00Z" | show_report
note "Rows are written so the record is complete. No inferred edge may fire an alert."

say "4. Residents correct the graph, one tap each"
E1=$(curl -sS "$BASE/zones/Z01" | jqp 'import sys,json; print(json.load(sys.stdin)["outbound"][0]["id"])')
E2=$(curl -sS "$BASE/zones/Z02" | jqp 'import sys,json; print(json.load(sys.stdin)["outbound"][0]["id"])')
for E in "$E1" "$E2"; do
  for T in "$ADA" "$BOLA"; do
    curl -sS -X POST "$BASE/edges/$E/confirm" -H "Authorization: Bearer $T" \
      | jqp 'import sys,json
d=json.load(sys.stdin)
print("  edge %s -> %s   %d of %d voices   %s"
      % (d["fromZone"], d["toZone"], d["distinctVoices"], d["threshold"],
         d["edge"]["confidence"]))'
  done
done
counts

say "5. Waiting for Z01 to go quiet"
wait_until_clear || true

say "6. The same escalation, now that the path is confirmed"
report "$ADA"  IMPASSABLE "2026-06-15T14:00:00Z" > /dev/null
report "$BOLA" IMPASSABLE "2026-06-15T14:10:00Z" | show_report
note "This is the transition the pilot exists to produce."

say "7. The kill switch"
curl -sS -X POST "$BASE/admin/kill-switch" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"enabled":false}' | jqp 'import sys,json; print("  ", json.load(sys.stdin))'
printf '  reporter trying the kill switch: '
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/admin/kill-switch" \
  -H "Authorization: Bearer $ADA" -H 'Content-Type: application/json' -d '{"enabled":true}'
printf '  anonymous trying to file a report: '
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$BASE/reports" \
  -H 'Content-Type: application/json' -d '{"level":"KNEE"}'

wait_until_clear || true
say "8. An escalation with alerting halted"
report "$ADA"  IMPASSABLE "2026-06-15T16:00:00Z" > /dev/null
report "$BOLA" IMPASSABLE "2026-06-15T16:10:00Z" | show_report
note "Every row written and marked. Nothing delivered. The halt is auditable."
curl -sS -X POST "$BASE/admin/kill-switch" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"enabled":true}' > /dev/null
note "Alerting re-enabled."

say "9. Replay a past event against the graph"
curl -sS -X POST "$BASE/replay" -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{
    "reports": [
      {"zoneId":"Z01","reporterId":1,"level":"IMPASSABLE","observedAt":"2025-07-08T06:00:00Z"},
      {"zoneId":"Z01","reporterId":2,"level":"IMPASSABLE","observedAt":"2025-07-08T06:20:00Z"},
      {"zoneId":"Z04","reporterId":3,"level":"KNEE","observedAt":"2025-07-08T07:10:00Z"},
      {"zoneId":"Z04","reporterId":4,"level":"IMPASSABLE","observedAt":"2025-07-08T07:25:00Z"}
    ]}' | jqp 'import sys,json
d=json.load(sys.stdin); s=d["summary"]
print("  replayed %d reports, %d zones escalated, %d alerts predicted, %d deliverable, %d held back by an unconfirmed path"
      % (s["reportsReplayed"], s["zonesEscalated"], s["alertsPredicted"],
         s["alertsDeliverable"], s["suppressedByUnconfirmedPath"]))
for a in d["alerts"]:
    print("   ", a["originZone"], "->", a["targetZone"], a["level"],
          "arriving", a["expectedArrival"], "deliver:", a["wouldDeliver"])
for c in d["allClears"]:
    print("    all-clear", c["originZone"], "->", c["targetZone"], "at", c["at"])'
note "Nothing was written and nothing was sent."
