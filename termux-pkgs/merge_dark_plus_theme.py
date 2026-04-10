"""Merge VSCode dark_vs.json + dark_plus.json into one self-contained
TextMate theme that sora-editor can load directly. The originals use
JSONC (comments + trailing commas) and dark_plus only contains a
delta with `"include": "./dark_vs.json"` — sora doesn't follow includes,
so we resolve the merge offline."""
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))


def strip_jsonc(text):
    """Strip // line comments and /* block comments while respecting strings."""
    out = []
    i = 0
    n = len(text)
    BACKSLASH = chr(92)  # avoid quoting headaches
    while i < n:
        c = text[i]
        if c == '"':
            out.append(c); i += 1
            while i < n:
                ch = text[i]
                out.append(ch)
                if ch == BACKSLASH and i + 1 < n:
                    out.append(text[i + 1]); i += 2
                    continue
                if ch == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == '/' and i + 1 < n:
            nxt = text[i + 1]
            if nxt == '/':
                while i < n and text[i] != '\n':
                    i += 1
                continue
            if nxt == '*':
                i += 2
                while i + 1 < n and not (text[i] == '*' and text[i + 1] == '/'):
                    i += 1
                i += 2
                continue
        out.append(c); i += 1
    cleaned = ''.join(out)
    cleaned = re.sub(r',(\s*[}\]])', r'\1', cleaned)
    return cleaned


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.loads(strip_jsonc(f.read()), strict=False)


def main():
    base = load(os.path.join(HERE, 'dark_vs.json'))
    ext = load(os.path.join(HERE, 'dark_plus.json'))
    merged = dict(base)
    merged['name'] = 'Dark+'
    merged['type'] = 'dark'
    merged['tokenColors'] = (
        (base.get('tokenColors') or []) + (ext.get('tokenColors') or [])
    )
    merged['colors'] = base.get('colors') or {}
    out_path = os.path.join(HERE, 'dark_plus_merged.json')
    with open(out_path, 'w', encoding='utf-8') as f:
        json.dump(merged, f, indent=2)
    print(f"merged: {len(merged['tokenColors'])} token rules, "
          f"{len(merged['colors'])} colors -> {out_path}")


if __name__ == '__main__':
    main()
