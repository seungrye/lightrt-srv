#!/usr/bin/env python3
"""
LiteRT Tunnel — Conversational chat with agentic tool use.
No extra dependencies — pure stdlib only.

Usage:
    python chat.py
    python chat.py --url http://192.168.0.37:8080
    python chat.py --system "You are a Python tutor."

Chat commands:
    /reset   — clear conversation history (system prompt kept)
    /system  — show current system prompt
    /quit    — exit
"""

import argparse
import json
import subprocess
import sys
import urllib.request
from textwrap import dedent

# ── Defaults ──────────────────────────────────────────────────────────────────

DEFAULT_URL = "http://localhost:8080"
TIMEOUT     = 300
MAX_STEPS   = 8

# ── ANSI colours ──────────────────────────────────────────────────────────────

if sys.stdout.isatty():
    RESET  = "\033[0m"
    BOLD   = "\033[1m"
    GRAY   = "\033[90m"
    CYAN   = "\033[36m"
    GREEN  = "\033[32m"
else:
    RESET = BOLD = GRAY = CYAN = GREEN = ""

# ── System prompt ─────────────────────────────────────────────────────────────

DEFAULT_SYSTEM = dedent("""\
    You are a helpful assistant running on an Android device via LiteRT Tunnel.

    WHEN TO USE TOOLS
    Use the shell tool only when you genuinely need to look up live device state
    (battery, storage, memory, running processes, files, network, etc.).

    For greetings, general knowledge questions, opinions, coding help, translation,
    or anything you can answer without checking the device — just reply directly.
    Do NOT use Thought/Action format for those.

    TOOL USE FORMAT (only when actually needed — one step at a time):
    Thought: <one sentence of reasoning>
    Action: shell
    Action Input: <single shell command>

    After receiving an Observation, continue until you have your answer, then write
    your final answer as plain text (no Action prefix needed).

    Safe commands: ls, cat, df, free, ps, date, uname, find, grep, wc, top -bn1
    Never run: rm, dd, mkfs, chmod, mv, cp, or any destructive command.
""")

# ── Tool: shell ───────────────────────────────────────────────────────────────

def run_shell(cmd: str) -> str:
    try:
        result = subprocess.run(
            cmd, shell=True, capture_output=True, text=True, timeout=15
        )
        out = (result.stdout + result.stderr).strip()
        return (out[:1500] + "\n…[truncated]") if len(out) > 1500 else (out or "(no output)")
    except subprocess.TimeoutExpired:
        return "(timed out after 15 s)"
    except Exception as e:
        return f"(error: {e})"

# ── Streaming with per-line colouring ─────────────────────────────────────────

class _LineState:
    __slots__ = ("last_action", "header_done")
    def __init__(self):
        self.last_action: str | None = None  # "shell", "done", or None
        self.header_done: bool = False


def _emit_line(line: str, state: _LineState) -> None:
    s = line.lstrip()

    if s.startswith("Thought:"):
        if not state.header_done:
            sys.stdout.write(f"{GREEN}Assistant:{RESET}\n")
            state.header_done = True
        content = s.split(":", 1)[1].lstrip() if ":" in s else s
        sys.stdout.write(GRAY + content.rstrip("\n") + RESET + "\n")

    elif s.startswith("Action:"):
        verb = (s.split(":", 1)[1].strip().lower()) if ":" in s else ""
        state.last_action = verb
        # suppress all Action: lines

    elif s.startswith("Action Input:"):
        content = (s.split(":", 1)[1].lstrip()) if ":" in s else s
        if state.last_action == "done":
            if not state.header_done:
                sys.stdout.write(f"{GREEN}Assistant:{RESET} ")
                state.header_done = True
            sys.stdout.write(content if content.endswith("\n") else content + "\n")
        # else: shell command — shown in code block after execution

    else:
        if not state.header_done:
            sys.stdout.write(f"{GREEN}Assistant:{RESET} ")
            state.header_done = True
        sys.stdout.write(line)

    sys.stdout.flush()


def stream_response(messages: list[dict], base_url: str) -> str:
    """Stream /v1/chat/completions with per-line colouring. Returns full text."""
    payload = json.dumps({
        "model": "local",
        "messages": messages,
        "stream": True,
    }).encode()

    req = urllib.request.Request(
        f"{base_url}/v1/chat/completions",
        data=payload,
        headers={"Content-Type": "application/json"},
    )

    collected: list[str] = []
    line_buf  = ""
    state     = _LineState()

    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        for raw in resp:
            sse = raw.decode().strip()
            if not sse.startswith("data: "):
                continue
            body = sse[6:]
            if body == "[DONE]":
                break
            try:
                token = json.loads(body)["choices"][0]["delta"].get("content") or ""
            except (KeyError, json.JSONDecodeError):
                continue
            if not token:
                continue

            collected.append(token)
            for ch in token:
                line_buf += ch
                if ch == "\n":
                    _emit_line(line_buf, state)
                    line_buf = ""

    if line_buf:
        _emit_line(line_buf, state)

    sys.stdout.write(RESET + "\n")
    sys.stdout.flush()
    return "".join(collected)

# ── Action parser ─────────────────────────────────────────────────────────────

def parse_action(text: str) -> tuple[str, str] | None:
    lines  = text.splitlines()
    action = None
    input_lines: list[str] = []
    collecting = False

    for line in lines:
        s = line.lstrip()
        if action is None and s.startswith("Action:"):
            action = s.split(":", 1)[1].strip().lower()
        elif action is not None and not input_lines and s.startswith("Action Input:"):
            input_lines.append(s.split(":", 1)[1].strip())
            collecting = True
        elif collecting:
            if s.startswith(("Thought:", "Action:")):
                break
            input_lines.append(line)

    if action and input_lines:
        return action, "\n".join(input_lines).strip()
    return None

# ── Server helpers ────────────────────────────────────────────────────────────

def check_server(base_url: str) -> dict | None:
    try:
        with urllib.request.urlopen(f"{base_url}/health", timeout=5) as r:
            info = json.loads(r.read())
            return info if info.get("ready") else None
    except Exception:
        return None

# ── Turn handler (chat + optional ReAct loop) ─────────────────────────────────

def handle_turn(
    user_text: str,
    history: list[dict],
    system_prompt: str,
    base_url: str,
) -> None:
    history.append({"role": "user", "content": user_text})

    for _ in range(MAX_STEPS):
        messages = [{"role": "system", "content": system_prompt}] + history

        reply = stream_response(messages, base_url)

        if not reply:
            history.pop()
            return

        history.append({"role": "assistant", "content": reply})

        parsed = parse_action(reply)
        if parsed is None:
            return  # plain reply or final answer — turn complete

        action, action_input = parsed

        if action == "done":
            return  # final answer was already printed by _emit_line

        if action == "shell":
            obs = run_shell(action_input)
            print(f"```\n$ {action_input}\n{obs}\n```\n")
            history.append({"role": "user", "content": f"Observation:\n{obs}"})
        else:
            print(f"{GRAY}[알 수 없는 액션: '{action}']{RESET}\n")
            return

    print(f"{GRAY}[최대 단계 수({MAX_STEPS}) 도달]{RESET}\n")

# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url",         default=DEFAULT_URL)
    parser.add_argument("--system",      default=None)
    parser.add_argument("--system-file", default=None, metavar="FILE")
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    if args.system_file:
        with open(args.system_file, encoding="utf-8") as f:
            system_prompt = f.read()
    else:
        system_prompt = args.system or DEFAULT_SYSTEM

    info = check_server(base_url)
    if info is None:
        print(f"[Error] 서버에 연결할 수 없습니다: {base_url}")
        print("        앱에서 서버를 먼저 시작하세요.")
        sys.exit(1)

    print(f"{BOLD}LiteRT Tunnel Chat{RESET}  "
          f"{GRAY}model={info.get('model','?')}  backend={info.get('backend','?')}{RESET}")
    print(f"{GRAY}툴 사용 가능 (shell) · /reset 초기화 · /quit 종료{RESET}\n")

    history: list[dict] = []

    while True:
        try:
            user_input = input(f"{CYAN}You:{RESET} ").strip()
        except (EOFError, KeyboardInterrupt):
            print(f"\n{GRAY}[종료]{RESET}")
            break

        if not user_input:
            continue

        match user_input.lower():
            case "/quit" | "/exit":
                print(f"{GRAY}[종료]{RESET}")
                break
            case "/reset" | "/clear":
                history.clear()
                print(f"{GRAY}[대화 기록 초기화됨]{RESET}\n")
                continue
            case "/system":
                print(f"{GRAY}{system_prompt}{RESET}\n")
                continue

        try:
            handle_turn(user_input, history, system_prompt, base_url)
        except KeyboardInterrupt:
            print(f"\n{GRAY}[생성 중단]{RESET}\n")
            # 미완성 turn 항목 제거
            while history and history[-1]["role"] != "user":
                history.pop()
            if history and history[-1]["role"] == "user":
                history.pop()
        except Exception as e:
            print(f"\n{GRAY}[오류: {e}]{RESET}\n")


if __name__ == "__main__":
    main()
