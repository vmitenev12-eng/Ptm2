#!/usr/bin/env python3
"""Правки PtmFixes.java при сборке: PtmFixes.java слишком большой (около 470 КБ), поэтому маленькие изменения
делаются здесь: список (описание, что заменить, на что). Каждый фрагмент «что заменить» должен встречаться
в файле ровно один раз, иначе сборка падает (чтобы правка не пропадала молча, если текст PtmFixes изменится).
Usage: patch_fixes.py <путь к PtmFixes.java в проекте Gradle>
Важно: исходный PtmFixes.java в корне репозитория этими правками не меняется.
"""
import sys

PATCHES = [
    (
        "Парковка: машина стоит по центру полосы (между первой и второй стрелкой) не только у сквозных мест, но и у тупиковых коротких",
        "double at0 = through ? len / 2.0D : Math.max(0.0D, len - 2.2D);",
        "double at0 = (through || len <= cfgNum(\"spotcenterlen\", 9.0D)) ? len / 2.0D : Math.max(0.0D, len - 2.2D);",
    ),
]


def main():
    path = sys.argv[1]
    with open(path, encoding="utf-8") as f:
        text = f.read()
    for name, old, new in PATCHES:
        n = text.count(old)
        if n != 1:
            print("ERROR: patch '%s': fragment found %d times (expected 1)" % (name, n))
            sys.exit(1)
        text = text.replace(old, new)
        print("patched:", name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


if __name__ == "__main__":
    main()
