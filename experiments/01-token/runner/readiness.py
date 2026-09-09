"""Admission gates, not an assertion that passing offline tests makes live runs safe."""


def live_readiness():
    return {
        "liveReady": False,
        "assessment": "BLOCKED",
        "preparationOnly": "Optional offline-container-v1 exists; it does not satisfy live network/auth gates",
        "blockers": [
            "OPEN_WORKER_HOST_RELAY: open network can reach a host HTTP/IPC relay",
            "HOST_FILESYSTEM: denylist is not a clean host/credential isolation boundary",
            "REMOTE_REFERENCES: public reference implementations can be re-downloaded",
            "MACOS_DETACHED_WORKER: process groups cannot contain detached worker descendants",
            "RESOURCE_ISOLATION: aggregate memory/process/disk quotas require a disposable environment",
            "RUNTIME_CONFIG_AUTH: real Codex/OMX/OML config, hooks and authentication isolation unverified",
            "SESSION_INVENTORY: actual OMX child inventory and usage schema unverified",
            "PRODUCT_SUPPORT: OML new/multiple files and fixed build validation require issue #37",
        ],
    }


def require_live_ready():
    assessment = live_readiness()
    if not assessment["liveReady"]:
        raise RuntimeError("Live experiment blocked: " + "; ".join(assessment["blockers"]))
