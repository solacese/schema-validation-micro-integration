#!/usr/bin/env bash
# Provisions schema-validation/inbound as a partitioned queue via SEMP
# Usage: ./scripts/provision-partitioned-queue.sh [partition-count] [semp-url] [admin-user] [admin-pass] [vpn]
set -euo pipefail

PARTITION_COUNT="${1:-3}"
SEMP_URL="${2:-http://localhost:8080}"
ADMIN_USER="${3:-admin}"
ADMIN_PASS="${4:-admin}"
VPN="${5:-default}"
QUEUE_NAME="${6:-schema-validation/inbound}"
ENCODED_QUEUE_NAME=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${QUEUE_NAME}', safe=''))")

# Topic subscriptions to add (must match application.yml)
SUBSCRIPTIONS=(
  "Acme/*/*/DATASCIENCE/*/FromEqt/>"
  "Acme/*/*/DATASCIENCE/*/FromSI/>"
  "Acme/*/*/SHOPFLOOR/*/FromEqt/>"
  "Acme/*/*/SHOPFLOOR/*/FromSI/>"
  "Apex/*/*/DATASCIENCE/*/FromEqt/>"
  "Apex/*/*/DATASCIENCE/*/FromSI/>"
  "Apex/*/*/SHOPFLOOR/*/FromEqt/>"
  "Apex/*/*/SHOPFLOOR/*/FromSI/>"
)

echo "Provisioning partitioned queue: ${QUEUE_NAME}"
echo "  Broker SEMP:      ${SEMP_URL}"
echo "  VPN:              ${VPN}"
echo "  Partition count:  ${PARTITION_COUNT}"
echo ""

# Check if queue already exists
EXISTING=$(curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
  "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}" 2>/dev/null || true)

if echo "${EXISTING}" | grep -q '"queueName"'; then
  EXISTING_PARTITIONS=$(echo "${EXISTING}" | python3 -c "import sys,json; print(json.load(sys.stdin)['data'].get('partitionCount',0))" 2>/dev/null || echo "0")
  if [[ "${EXISTING_PARTITIONS}" == "${PARTITION_COUNT}" ]]; then
    echo "Queue already exists with ${PARTITION_COUNT} partitions — no action needed"
  else
    echo "Queue exists with ${EXISTING_PARTITIONS} partitions — deleting and recreating with ${PARTITION_COUNT}"
    # Shutdown queue first (disable ingress/egress)
    curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
      "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}" \
      -X PATCH -H 'Content-Type: application/json' \
      -d '{"ingressEnabled":false,"egressEnabled":false}' >/dev/null

    # Remove all subscriptions first
    SUBS=$(curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
      "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}/subscriptions" 2>/dev/null \
      | python3 -c "import sys,json; [print(s['subscriptionTopic']) for s in json.load(sys.stdin).get('data',[])]" 2>/dev/null || true)
    while IFS= read -r sub; do
      if [[ -n "${sub}" ]]; then
        ENCODED_SUB=$(python3 -c "import urllib.parse; print(urllib.parse.quote('${sub}', safe=''))")
        curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
          "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}/subscriptions/${ENCODED_SUB}" \
          -X DELETE >/dev/null 2>&1 || true
      fi
    done <<< "${SUBS}"

    # Delete queue
    curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
      "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}" \
      -X DELETE >/dev/null
    echo "Deleted existing queue"
  fi
fi

# Create queue with partitions
if ! echo "${EXISTING}" | grep -q '"queueName"' || [[ "${EXISTING_PARTITIONS:-0}" != "${PARTITION_COUNT}" ]]; then
  RESPONSE=$(curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
    "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues" \
    -X POST -H 'Content-Type: application/json' \
    -d "{
      \"queueName\": \"${QUEUE_NAME}\",
      \"accessType\": \"non-exclusive\",
      \"permission\": \"consume\",
      \"ingressEnabled\": true,
      \"egressEnabled\": true,
      \"partitionCount\": ${PARTITION_COUNT},
      \"partitionRebalanceDelay\": 5,
      \"partitionRebalanceMaxHandoffTime\": 3,
      \"maxMsgSpoolUsage\": 5000,
      \"maxBindCount\": 1000,
      \"respectMsgPriorityEnabled\": false
    }" 2>&1)

  if echo "${RESPONSE}" | grep -q '"queueName"'; then
    echo "Created partitioned queue with ${PARTITION_COUNT} partitions"
  else
    echo "ERROR creating queue:"
    echo "${RESPONSE}"
    exit 1
  fi

  # Add topic subscriptions
  for sub in "${SUBSCRIPTIONS[@]}"; do
    curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
      "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}/subscriptions" \
      -X POST -H 'Content-Type: application/json' \
      -d "{\"subscriptionTopic\": \"${sub}\"}" >/dev/null
    echo "  Added subscription: ${sub}"
  done
fi

# Verify
echo ""
echo "Verification:"
curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
  "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)['data']
print(f\"  Queue:            {d['queueName']}\")
print(f\"  Access type:      {d['accessType']}\")
print(f\"  Partition count:  {d.get('partitionCount', 0)}\")
print(f\"  Ingress enabled:  {d['ingressEnabled']}\")
print(f\"  Egress enabled:   {d['egressEnabled']}\")
print(f\"  Max spool (MB):   {d['maxMsgSpoolUsage']}\")
"

NSUBS=$(curl -sf -u "${ADMIN_USER}:${ADMIN_PASS}" \
  "${SEMP_URL}/SEMP/v2/config/msgVpns/${VPN}/queues/${ENCODED_QUEUE_NAME}/subscriptions" \
  | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('data',[])))")
echo "  Subscriptions:    ${NSUBS}"

echo ""
echo "Done. Queue ready for partitioned consumption."
