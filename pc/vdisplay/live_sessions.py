"""Print the gateway's live session ids as JSON (run with the hermes-agent venv python).

Used by hermes-vdisplay to close an agent screen once its session is open on neither the phone nor the PC.
Exit 0 + {"sessions": [...]} on success; non-zero when the gateway can't be reached (caller keeps the screens).
"""
import asyncio
import json
import sys
from pathlib import Path


async def main(home):
    sys.path.insert(0, str(Path(sys.executable).resolve().parents[2]))   # <hermes-agent>/venv/Scripts/python.exe
    from hermes_cli.gateway_client import GATEWAY_WS_PROTOCOL, GatewayClient, _session_ticket, gateway_ws_target
    from hermes_cli.gateway_runtime import ensure_gateway_runtime
    from websockets.asyncio.client import connect
    async with asyncio.timeout(30):
        ready = await asyncio.to_thread(ensure_gateway_runtime, Path(home).resolve())
        if ready.state != "ready" or ready.endpoint is None:
            raise SystemExit(f"gateway {ready.state}")
        ticket = await asyncio.to_thread(_session_ticket, Path(home).resolve(), ready.endpoint)
        url, protocols = gateway_ws_target(ready.endpoint, ticket)
        async with connect(url, subprotocols=protocols, open_timeout=10, proxy=None) as ws:
            if ws.subprotocol != GATEWAY_WS_PROTOCOL:
                raise SystemExit("gateway protocol mismatch")
            async with GatewayClient(ws) as client:
                r = await client.rpc("session.active_list")
    print(json.dumps({"sessions": [s["id"] for s in r.get("sessions", [])]}))


if __name__ == "__main__":
    import os
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("HERMES_HOME", "")))
