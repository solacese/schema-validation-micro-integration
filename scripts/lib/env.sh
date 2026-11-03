#!/usr/bin/env bash
# Load dotenv defaults while preserving values explicitly set by the caller.
# .env is a trusted local shell assignment file; multiline values are unsupported.
load_project_env() {
  local env_path="${ROOT_DIR}/.env" line key
  [[ -f "${env_path}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ "${line}" =~ ^[[:space:]]*(#|$) ]] && continue
    if [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)= ]]; then
      key="${BASH_REMATCH[1]}"
      if [[ "${key}" == USER || "${key}" == PWD ]]; then
        # Legacy broker aliases must never replace shell identity/directory.
        continue
      fi
      if [[ -z "${!key+x}" ]]; then
        eval "export ${line}"
      fi
    else
      echo "Unsupported assignment in ${env_path}: ${line%%=*}" >&2
      return 1
    fi
  done < "${env_path}"
}

# Translate only the local Docker endpoint. Remote SMF ports must remain intact.
host_smf_url() {
  local broker_url="$1"
  case "${broker_url}" in
    tcp://solace-broker:55555) printf 'tcp://localhost:%s\n' "${BROKER_SMF_HOST_PORT:-55554}" ;;
    *) printf '%s\n' "${broker_url}" ;;
  esac
}
