"""
Unit tests for agent.py helpers.
Run: python -m pytest examples/test_agent.py -v
  or: python examples/test_agent.py
"""
import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from agent import parse_action


def test_parse_single_shell_action():
    reply = "Thought: 저장공간 확인\nAction: shell\nAction Input: df -h"
    assert parse_action(reply) == ("shell", "df -h")


def test_parse_done_action():
    reply = "Thought: 완료\nAction: done\nAction Input: 분석 결과입니다."
    assert parse_action(reply) == ("done", "분석 결과입니다.")


def test_parse_returns_FIRST_action_not_last():
    # Model generates multiple steps in one shot — must only use first Action
    reply = (
        "Thought: 저장공간 확인\n"
        "Action: shell\n"
        "Action Input: df -h\n"
        "Thought: 메모리 확인\n"
        "Action: shell\n"
        "Action Input: free -m\n"
        "Thought: 완료\n"
        "Action: done\n"
        "Action Input: 요약 결과\n"
    )
    action, action_input = parse_action(reply)
    assert action == "shell"
    assert action_input == "df -h", f"Expected 'df -h', got '{action_input}'"


def test_parse_returns_none_when_no_action():
    reply = "Thought: 뭔가 생각 중..."
    assert parse_action(reply) is None


def test_parse_multiline_action_input_uses_first_line():
    # "done" with multi-line answer — action_input is everything after "Action Input:"
    reply = (
        "Action: done\n"
        "Action Input: 첫 번째 줄\n"
        "두 번째 줄\n"
        "세 번째 줄\n"
    )
    action, action_input = parse_action(reply)
    assert action == "done"
    assert "첫 번째 줄" in action_input


if __name__ == "__main__":
    tests = [
        test_parse_single_shell_action,
        test_parse_returns_FIRST_action_not_last,
        test_parse_done_action,
        test_parse_returns_none_when_no_action,
        test_parse_multiline_action_input_uses_first_line,
    ]
    failed = 0
    for t in tests:
        try:
            t()
            print(f"  [PASSED] {t.__name__}")
        except AssertionError as e:
            print(f"  [FAILED] {t.__name__}: {e}")
            failed += 1
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(failed)
