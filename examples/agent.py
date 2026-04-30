#!/usr/bin/env python3
"""
LiteRT Tunnel — ReAct Agent example
Runs against the local server at localhost:8080.
No extra dependencies — pure stdlib only.

Usage (Termux):
    python agent.py
    python agent.py "현재 디바이스의 저장공간 상황을 분석해줘"
"""

import json
import subprocess
import sys
import urllib.request
from textwrap import dedent

BASE_URL  = "http://localhost:8080"
MODEL     = "local"
MAX_STEPS = 10
TIMEOUT   = 120  # seconds per LLM call


# ── System prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = dedent("""\
    You are a helpful on-device assistant running on an Android phone via LiteRT Tunnel.
    You answer user requests by reasoning step-by-step with tools.

    TOOLS
    -----
    shell  — run a single shell command and receive its output.
    done   — return your final answer to the user and stop.

    STRICT OUTPUT FORMAT — follow this exactly every turn:
    Thought: <one sentence of reasoning>
    Action: shell
    Action Input: <exactly one shell command>

    OR when finished:
    Thought: <reasoning>
    Action: done
    Action Input: <final answer in Korean>

    RULES
    -----
    - Output ONLY one Thought + one Action + one Action Input, then STOP.
    - Do NOT invent observations. Wait for real output.
    - Do NOT chain multiple actions in one response.
    - Safe read-only commands only: df, free, ps, date, uname, cat, ls, top, ...
    - Never run: rm, dd, mkfs, chmod, or any write/destructive command.
""")


# ── Shell tool ────────────────────────────────────────────────────────────────

def run_shell(cmd: str) -> str:
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=15
        )
        out = (result.stdout + result.stderr).strip()
        return (out[:1024] + "\n…[truncated]") if len(out) > 1024 else (out or "(no output)")
    except subprocess.TimeoutExpired:
        return "(timed out)"
    except Exception as e:
        return f"(error: {e})"


# ── LLM client ────────────────────────────────────────────────────────────────

def chat(messages: list[dict]) -> str:
    payload = json.dumps({
        "model": MODEL,
        "messages": messages,
        "stream": True,
    }).encode()

    req = urllib.request.Request(
        f"{BASE_URL}/v1/chat/completions",
        data=payload,
        headers={"Content-Type": "application/json"},
    )

    tokens: list[str] = []
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        for raw in resp:
            line = raw.decode().strip()
            if not line.startswith("data: "):
                continue
            data = line[6:]
            if data == "[DONE]":
                break
            try:
                token = json.loads(data)["choices"][0]["delta"].get("content") or ""
                if token:
                    print(token, end="", flush=True)
                    tokens.append(token)
            except (KeyError, json.JSONDecodeError):
                pass

    print()
    return "".join(tokens)


def check_server() -> bool:
    try:
        with urllib.request.urlopen(f"{BASE_URL}/health", timeout=5) as r:
            return json.loads(r.read()).get("ready", False)
    except Exception:
        return False


def reset_server() -> None:
    """Clear the server's conversation history before starting a new agent run."""
    try:
        req = urllib.request.Request(
            f"{BASE_URL}/reset",
            data=b"",
            method="POST",
        )
        urllib.request.urlopen(req, timeout=5)
    except Exception:
        pass  # non-fatal


# ── Parser ────────────────────────────────────────────────────────────────────

def parse_action(reply: str) -> tuple[str, str] | None:
    """
    Extract the FIRST Action / Action Input pair from the model reply.
    Returns (action, action_input) or None if not found.
    Multi-line Action Input is joined into a single string.
    """
    lines = reply.splitlines()
    action: str | None = None
    input_lines: list[str] = []
    collecting = False

    for line in lines:
        if line.startswith("Action:") and action is None:
            # First action found — record it and start collecting input
            action = line.split(":", 1)[1].strip().lower()
            collecting = False
        elif line.startswith("Action Input:") and action is not None and not input_lines:
            input_lines.append(line.split(":", 1)[1].strip())
            collecting = True
        elif collecting:
            # Stop collecting if we hit the next Thought/Action block
            if line.startswith("Thought:") or line.startswith("Action:"):
                break
            input_lines.append(line)

    if action and input_lines:
        return action, "\n".join(input_lines).strip()
    return None


# ── ReAct loop ────────────────────────────────────────────────────────────────

def run_agent(task: str) -> None:
    print(f"\n{'='*60}\nTask: {task}\n{'='*60}\n")

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user",   "content": task},
    ]

    for step in range(1, MAX_STEPS + 1):
        print(f"── Step {step} {'─'*48}")
        reply = chat(messages)
        messages.append({"role": "assistant", "content": reply})

        parsed = parse_action(reply)
        if parsed is None:
            print("\n[Agent] No action found in reply — stopping.")
            break

        action, action_input = parsed

        if action == "done":
            print(f"\n{'='*60}\n[Final Answer]\n{action_input}\n{'='*60}")
            break

        if action == "shell":
            print(f"\n$ {action_input}")
            observation = run_shell(action_input)
            print(f"{observation}\n")
            messages.append({
                "role": "user",
                "content": f"Observation:\n{observation}\n\nContinue with the next step.",
            })
        else:
            print(f"\n[Agent] Unknown action '{action}' — stopping.")
            break
    else:
        print("\n[Agent] Reached max steps.")


# ── Entry point ───────────────────────────────────────────────────────────────

DEFAULT_TASK = (
    "이 안드로이드 기기의 현재 상태를 분석해줘: "
    "저장공간, 메모리, 실행 중인 프로세스 수, 현재 시각을 확인하고 "
    "한국어로 요약해줘."
)

if __name__ == "__main__":
    if not check_server():
        print("[Error] 서버가 실행 중이 아닙니다. 앱에서 서버를 먼저 시작하세요.")
        sys.exit(1)

    reset_server()  # clear any leftover conversation state from previous runs
    task = " ".join(sys.argv[1:]) if len(sys.argv) > 1 else DEFAULT_TASK
    run_agent(task)
