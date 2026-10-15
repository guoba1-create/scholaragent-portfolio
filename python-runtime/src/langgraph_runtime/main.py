"""Python LangGraph runtime entry point."""

import os
import time

from . import config
from .checkpoint_store import get_checkpointer
from .grpc_server import serve
from .mcp_tool_client import check_health
from .runtime_state_store import get_state_store


def main():
    """Check dependencies, clean durable state, and start the gRPC server."""
    print("=== Python LangGraph Runtime (DeepResearch + Evidence Workflow) ===")
    print(f"MCP Server: {config.MCP_SERVER_URL}")
    print(f"LLM: {config.DASHSCOPE_MODEL} @ {config.DASHSCOPE_BASE_URL}")
    print(f"gRPC port: {config.GRPC_PORT}")
    print()

    print("Checking MCP server health...")
    for attempt in range(3):
        health = check_health()
        if health.get("status") == "UP":
            print(f"  MCP Server is UP. Transport: {health.get('transport', 'unknown')}")
            break
        print(
            f"  Attempt {attempt + 1}/3: MCP Server not ready - "
            f"{health.get('error', 'unknown')}"
        )
        if attempt < 2:
            time.sleep(3)
    else:
        print("  WARNING: MCP Server not reachable. Tool calls will fail.")
        print("  Make sure the Java application is running on", config.MCP_SERVER_URL)

    print()
    try:
        deleted_states = get_state_store().cleanup_expired()
        if deleted_states:
            print(f"Cleaned up {deleted_states} expired runtime state file(s).")

        checkpointer = get_checkpointer()
        checkpoint_ttl = int(
            os.getenv("LANGGRAPH_CHECKPOINT_TTL_SECONDS", str(7 * 24 * 3600))
        )
        deleted_checkpoints = checkpointer.cleanup_expired(checkpoint_ttl)
        if deleted_checkpoints:
            print(f"Cleaned up {deleted_checkpoints} expired checkpoint thread(s).")

        temp_max_age = int(
            os.getenv("LANGGRAPH_CHECKPOINT_TEMP_MAX_AGE_SECONDS", "3600")
        )
        deleted_temps = checkpointer.cleanup_stale_temp_files(temp_max_age)
        if deleted_temps:
            print(f"Cleaned up {deleted_temps} stale checkpoint temp file(s).")
    except Exception as error:
        print(f"State cleanup warning: {error}")

    print(f"Starting gRPC server on port {config.GRPC_PORT}...")
    serve(port=config.GRPC_PORT)


if __name__ == "__main__":
    main()
