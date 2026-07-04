# -*- coding: utf-8 -*-
"""Хук-гейт qa_check.py для Claude Code.

Режимы:
  post — PostToolUse (Write|Edit): если менялся .kt — запустить qa_check.py
         из корня репозитория; при ошибках вывести их в stderr и выйти с кодом 2
         (блокирующий фидбэк: Claude видит ошибки и не продолжает молча).
  stop — Stop: финальный прогон qa_check.py, результат показывается пользователю
         через systemMessage (не блокирует завершение).
"""
import json
import os
import subprocess
import sys

# .claude/hooks/qa_gate.py -> hooks -> .claude -> КОРЕНЬ (C:\ss)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def run_qa():
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    r = subprocess.run(
        [sys.executable, "qa_check.py"],
        cwd=ROOT, capture_output=True, text=True,
        encoding="utf-8", errors="replace", env=env,
    )
    out = ((r.stdout or "") + (r.stderr or "")).strip()
    return r.returncode, out

def main():
    # Windows-консоль может быть cp1253 — кириллица в выводе роняла print
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    mode = sys.argv[1] if len(sys.argv) > 1 else "post"

    if mode == "post":
        try:
            payload = json.load(sys.stdin)
        except Exception:
            sys.exit(0)  # нет валидного stdin — не мешаем
        tool_input = payload.get("tool_input") or {}
        tool_resp = payload.get("tool_response") or {}
        path = str(tool_input.get("file_path") or tool_resp.get("filePath") or "")
        if not path.lower().endswith(".kt"):
            sys.exit(0)  # правка не .kt — qa не нужен
        rc, out = run_qa()
        if rc != 0:
            # exit 2 = блокирующая ошибка: stderr уходит Claude как фидбэк
            sys.stderr.write(out or "qa_check.py failed (no output)")
            sys.exit(2)
        sys.exit(0)

    if mode == "stop":
        rc, out = run_qa()
        if rc == 0:
            last = out.splitlines()[-1] if out else "OK"
            msg = "Финальный qa_check.py: " + last
        else:
            msg = "Финальный qa_check.py ПРОВАЛЕН:\n" + (out or "(нет вывода)")
        print(json.dumps({"systemMessage": msg}, ensure_ascii=False))
        sys.exit(0)

    sys.exit(0)

if __name__ == "__main__":
    main()
