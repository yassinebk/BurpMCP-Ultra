#!/bin/sh
set -eu

dashboard_url="${BURPMCP_DASHBOARD_URL:-http://127.0.0.1:9878/}"
sse_url="${BURPMCP_SSE_URL:-http://127.0.0.1:9876/}"
proxy_jar="${BURPMCP_PROXY_JAR:-/Users/fir3cr4ckers/.BurpSuite/mcp-proxy/mcp-proxy-all.jar}"
java_bin="${BURPMCP_JAVA:-/opt/homebrew/Cellar/openjdk@21/21.0.9/libexec/openjdk.jdk/Contents/Home/bin/java}"

headers=$(curl --fail --silent --show-error --max-time 5 --dump-header - --output /dev/null "$dashboard_url")
token=$(printf '%s\n' "$headers" | awk 'BEGIN{IGNORECASE=1} /^Set-Cookie: mcp_token=/{sub(/^[^=]*=/, ""); sub(/;.*/, ""); gsub("\\r", ""); print; exit}')

if [ -z "$token" ]; then
    echo "BurpMCP-Ultra did not provide a local authentication cookie" >&2
    exit 1
fi

export MCP_AUTH_TOKEN="$token"
exec "$java_bin" -jar "$proxy_jar" --sse-url "$sse_url"
