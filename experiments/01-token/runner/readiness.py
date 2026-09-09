"""Admission gates, not an assertion that passing offline tests makes live runs safe."""


def live_readiness():
    return {
        "liveReady": False,
        "assessment": "BLOCKED",
        "preparationOnly": "Disposable container with optional public-web-isolated-host-v1; no live CLI entry point",
        "acceptedLimitations": [
            "PUBLIC_REMOTE_SHARING: public services may relay answers; not a content/DLP guarantee",
            "HOST_ENGINE: Docker/kernel vulnerabilities and actual host power loss are not certified",
        ],
        "blockers": [
            "LIVE_CONTAINER_ADAPTER: real CLI must use the disposable boundary; legacy local runner is not eligible",
            "PRIVATE_PROTOCOL: pin private bundle hash and export identical worker-only packets before live runs",
            "PROXY_COMPATIBILITY: real CLI/provider/MCP HTTP(S) proxy behavior must pass the pilot",
            "RUNTIME_CONFIG_AUTH: real Codex/OMX/OML config, hooks and authentication isolation unverified",
            "SESSION_INVENTORY: actual OMX child inventory and usage schema unverified",
            "LIVE_POLICY_DELIVERY: real CLI must receive the pinned shared prompt and produce reviewable evidence",
            "PRODUCT_SUPPORT: OML new/multiple files and fixed build validation require issue #37",
        ],
    }


def require_live_ready():
    assessment = live_readiness()
    if not assessment["liveReady"]:
        raise RuntimeError("Live experiment blocked: " + "; ".join(assessment["blockers"]))
