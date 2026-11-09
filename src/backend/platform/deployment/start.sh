#!/bin/sh
set -eu

fail_configuration() {
  printf '%s\n' 'Invalid or incomplete database configuration.' >&2
  exit 1
}

if [ -z "${MP4_AUTH_DB_USER:-}" ] || [ -z "${MP4_AUTH_DB_PASSWORD:-}" ]; then
  fail_configuration
fi

if [ -z "${MP4_AUTH_JDBC_URL:-}" ]; then
  host=${MP4_AUTH_DB_HOST:-}
  port=${MP4_AUTH_DB_PORT:-}
  database=${MP4_AUTH_DB_NAME:-}

  case "$host" in
    ''|*[!A-Za-z0-9.-]*) fail_configuration ;;
  esac
  case "$port" in
    ''|*[!0-9]*) fail_configuration ;;
  esac
  case "$database" in
    ''|*[!A-Za-z0-9_-]*) fail_configuration ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    fail_configuration
  fi

  MP4_AUTH_JDBC_URL="jdbc:postgresql://${host}:${port}/${database}"
  export MP4_AUTH_JDBC_URL
fi

case "$MP4_AUTH_JDBC_URL" in
  jdbc:postgresql://*/*) authority_and_database=${MP4_AUTH_JDBC_URL#jdbc:postgresql://} ;;
  *) fail_configuration ;;
esac
case "$MP4_AUTH_JDBC_URL" in
  *'@'*|*'?'*|*'#'*) fail_configuration ;;
esac
authority=${authority_and_database%%/*}
database=${authority_and_database#*/}
case "$authority" in
  *:*) host=${authority%:*}; port=${authority##*:} ;;
  *) fail_configuration ;;
esac
case "$host" in
  ''|*[!A-Za-z0-9.-]*) fail_configuration ;;
esac
case "$port" in
  ''|*[!0-9]*) fail_configuration ;;
esac
case "$database" in
  ''|*/*|*[!A-Za-z0-9_-]*) fail_configuration ;;
esac
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
  fail_configuration
fi

secret=${MP4_AUTH_RATE_LIMIT_SECRET:-}
if [ "${#secret}" -lt 32 ]; then
  fail_configuration
fi

port=${PORT:-10000}
case "$port" in
  ''|*[!0-9]*) fail_configuration ;;
esac
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
  fail_configuration
fi

exec java --add-modules=jdk.httpserver -cp 'classes:lib/*' mp4.platform.AuthApplication \
  --server.address=0.0.0.0 \
  --server.port="$port"
