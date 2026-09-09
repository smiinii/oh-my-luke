"""Per-attempt public web gateway; worker itself always has network=none."""
import time

from recovery import LABEL

POLICY = {
    "name": "public-web-isolated-host-v1", "protocols": ["http", "https"], "ports": [80, 443],
    "privateNetworks": "deny", "dns": "gateway-vetted-numeric-connect",
    "remoteAnswerSharing": "NOT_PREVENTED", "requiresProxyCompatibleTools": True,
    "connectPayloadInspection": False,
    "gatewayLimits": {"cpus": 1, "memoryBytes": 402653184, "pids": 32, "socketVolumeBytes": 1048576},
}


def environment(enabled):
    proxy = "http://127.0.0.1:18080" if enabled else ""
    values = {key: proxy for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")}
    for key in ("NO_PROXY", "no_proxy"):
        values[key] = "localhost,127.0.0.1,::1" if enabled else ""
    if enabled:
        values["JAVA_TOOL_OPTIONS"] = ("-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 "
                                       "-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 "
                                       "-Dhttp.nonProxyHosts=localhost|127.*|[::1]")
    return [arg for key, value in values.items() for arg in ("-e", key + "=" + value)]


def gateway(docker, lease, image):
    name = lease.name + "-gateway"
    docker("volume", "create", "--label", LABEL + "=" + lease.id,
           "--driver", "local", "--opt", "type=tmpfs", "--opt", "device=tmpfs",
           "--opt", "o=size=1m,uid=1000,gid=1000,mode=755", name)
    cid = docker("create", "--name", name, "--label", LABEL + "=" + lease.id,
                 "--network", "bridge", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                 "--memory", "384m", "--memory-swap", "384m", "--cpus", "1", "--pids-limit", "32",
                 "--log-driver", "none", "--user", "1000:1000", *environment(False),
                 "--mount", f"type=volume,src={name},dst=/gateway,volume-nocopy",
                 "--entrypoint", "python3", image, "/opt/oml-preparation/egress.py", "gateway").decode().strip()
    docker("start", cid)
    wait_ready(docker, cid, "-S", "/gateway/proxy.sock")
    return ["--mount", f"type=volume,src={name},dst=/gateway,readonly,volume-nocopy"]


def wait_ready(docker, cid, check, path):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        try:
            docker("exec", cid, "test", check, path, timeout=5)
            return
        except RuntimeError:
            time.sleep(.05)
    raise RuntimeError("Gateway startup failed; no direct-network fallback")


def forward(docker, cid):
    docker("exec", "-d", cid, "python3", "/opt/oml-preparation/egress.py", "forward")
    wait_ready(docker, cid, "-f", "/work/proxy-ready")
