#!/usr/bin/env python3
"""Extract the bundled SRD 5.2.1 RAG corpus from the official SRD 5.2.1 PDF.

Usage:
    python tools/extract-srd.py path/to/SRD_CC_v5.2.1.pdf

Writes ``src/main/resources/srd/srd-5.2.1.json`` — a JSON array of
``{"key", "title", "type", "content"}`` objects consumed by
``SrdContent`` (which embeds them into pgvector via ``SrdSeeder`` and
injects monster lore on the combat path via ``DmAiService``).

The script shells out to the ``pdftotext`` CLI (poppler); no Python PDF
libraries are required. Two passes are used:

* **raw** mode (``pdftotext file -``) gives clean single-column reading
  order for spells, the rules glossary, magic items, species, and prose
  rules sections.
* **-layout** mode is used only for the two-column monster stat blocks,
  which are split at the page gutter and reflowed (left column fully,
  then right column) before parsing.

Monster entries are reduced to **behavioural lore only**: every numeric
combat stat (AC / HP / Speed / ability scores / CR / to-hit / damage) is
stripped so the lore can never contradict the engine's authoritative
combat math.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

MAX_CONTENT = 800

SCHOOLS = "Abjuration|Conjuration|Divination|Enchantment|Evocation|Illusion|Necromancy|Transmutation"
FOOTER = re.compile(
    r"^\d+\s+System Reference Document 5\.2\.1$"
    r"|^System Reference Document 5\.2\.1\s+\d+$"
    r"|^System Reference Document 5\.2\.1$"
)

# ── pdftotext helpers ───────────────────────────────────────────────


def pdftotext(pdf_path, layout=False):
    args = ["pdftotext"]
    if layout:
        args.append("-layout")
    args += [str(pdf_path), "-"]
    out = subprocess.run(args, capture_output=True)
    if out.returncode != 0:
        raise RuntimeError("pdftotext failed: " + out.stderr.decode("utf-8", "replace"))
    # normalise newlines — pdftotext emits CRLF on Windows, which breaks
    # $-anchored / exact-newline matches that the parsers rely on
    text = out.stdout.decode("utf-8", "replace")
    return text.replace("\r\n", "\n").replace("\r", "\n")


def strip_footers(lines):
    return [l for l in lines if not FOOTER.match(l.strip())]


# ── shared text utilities ───────────────────────────────────────────


def slugify(text):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return s or "x"


def cap(text):
    t = re.sub(r"\s+", " ", text).strip()
    if len(t) <= MAX_CONTENT:
        return t
    cut = t.rfind(" ", 0, MAX_CONTENT)
    if cut < MAX_CONTENT // 2:
        cut = MAX_CONTENT
    return t[:cut].rstrip(" ,;:.") + "…"


def dehyphenate_join(lines):
    """Join lines, healing words split across line breaks (``Slash-\\ning``)."""
    out = ""
    for raw in lines:
        l = raw.strip()
        if not l:
            continue
        if out.endswith("-") and re.search(r"[A-Za-z]-$", out):
            out = out[:-1] + l
        else:
            out = (out + " " + l) if out else l
    return out


def is_table_row(line):
    """Heuristic: drop dice/table rows that add noise to flavour text."""
    s = line.strip()
    if not s:
        return True
    if s.startswith("|"):
        return True
    digits = sum(c.isdigit() for c in s)
    return len(s) > 0 and digits / len(s) > 0.4


# ── entry collection (with unique keys) ─────────────────────────────


class Corpus:
    def __init__(self):
        self.entries = []
        self._keys = set()

    def add(self, type_, title, body):
        title = re.sub(r"\s+", " ", title).strip()
        body = body.strip()
        if not title or not body:
            return
        base = "SRD:%s:%s" % (type_, slugify(title))
        key = base
        n = 2
        while key.upper() in self._keys:
            key = "%s-%d" % (base, n)
            n += 1
        self._keys.add(key.upper())
        content = cap(title + ": " + body)
        self.entries.append(
            {"key": key, "title": title, "type": type_, "content": content}
        )


# ── SPELLS ──────────────────────────────────────────────────────────

SPELL_HDR = re.compile(
    r"^(Level [1-9]\d* (?:%s)|(?:%s) Cantrip) \(.+\)$" % (SCHOOLS, SCHOOLS)
)


def parse_spells(pages, corpus):
    text = "\n".join(pages[106:175])
    compact = [l.strip() for l in strip_footers(text.splitlines()) if l.strip()]
    hpos = [i for i, l in enumerate(compact) if SPELL_HDR.match(l)]
    for idx, i in enumerate(hpos):
        name = compact[i - 1]
        nxt_name = hpos[idx + 1] - 1 if idx + 1 < len(hpos) else len(compact)
        body_lines = compact[i:nxt_name]  # header + casting line + description
        body = dehyphenate_join(body_lines)
        corpus.add("SPELL", name, body)


# ── RULES GLOSSARY (incl. CONDITIONS) ───────────────────────────────

GLOSS_TAG = re.compile(r"\s*\[(Condition|Hazard|Action|Area of Effect|Attitude)\]\s*$")
SMALL_WORDS = {"of", "the", "and", "or", "to", "a", "per", "in", "with",
               "at", "on", "from", "for", "by", "an"}


def is_glossary_heading(line, nxt):
    s = line.strip()
    if not s:
        return False
    if GLOSS_TAG.search(s):
        return True
    if len(s) > 55 or s[-1] in ".,:;)":
        return False
    if not s[0].isupper():
        return False
    words = s.split()
    if len(words) > 7:
        return False
    for w in words:
        if w[0].isupper() or w[0].isdigit() or w.lower() in SMALL_WORDS:
            continue
        return False
    # an entry heading is immediately followed by its definition prose
    return bool(nxt) and len(nxt.strip()) >= 40 and nxt.strip()[0].isupper()


def parse_glossary(pages, corpus):
    lines = strip_footers("\n".join(pages[175:191]).splitlines())
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == "Rules Definitions")
        lines = lines[start + 1:]
    except StopIteration:
        pass
    heads = []
    for i, l in enumerate(lines):
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        if is_glossary_heading(l, nxt):
            heads.append(i)
    for idx, i in enumerate(heads):
        raw_title = lines[i].strip()
        m = GLOSS_TAG.search(raw_title)
        tag = m.group(1) if m else None
        title = GLOSS_TAG.sub("", raw_title).strip()
        end = heads[idx + 1] if idx + 1 < len(heads) else len(lines)
        body_lines = [l for l in lines[i + 1:end] if not is_table_row(l)]
        body = dehyphenate_join(body_lines).replace("•", " ").replace("�", " ")
        body = re.sub(r"\s+", " ", body)
        type_ = "CONDITION" if tag == "Condition" else "RULE"
        corpus.add(type_, title, body)


# ── generic prose RULE sections ─────────────────────────────────────


def parse_prose_rules(pages, page_range, corpus):
    lines = strip_footers("\n".join(pages[page_range[0]:page_range[1]]).splitlines())
    heads = []
    for i, l in enumerate(lines):
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        if is_glossary_heading(l, nxt):
            heads.append(i)
    for idx, i in enumerate(heads):
        title = lines[i].strip()
        end = heads[idx + 1] if idx + 1 < len(heads) else len(lines)
        body_lines = [l for l in lines[i + 1:end] if not is_table_row(l)]
        body = dehyphenate_join(body_lines).replace("•", " ").replace("�", " ")
        body = re.sub(r"\s+", " ", body)
        if len(body) < 40:
            continue
        corpus.add("RULE", title, body)


# ── MAGIC ITEMS (rarity-line anchor) ────────────────────────────────

ITEM_TYPE = re.compile(
    r"^(Armor|Wondrous Item|Potion|Ring|Rod|Scroll|Staff|Wand|Weapon)\b.*\b"
    r"(Common|Uncommon|Rare|Very Rare|Legendary|Artifact|Varies)\b",
)


def parse_magic_items(pages, corpus):
    lines = strip_footers("\n".join(pages[209:253]).splitlines())
    lines = [l for l in lines if l.strip()]
    anchors = [i for i, l in enumerate(lines) if ITEM_TYPE.match(l.strip())]
    for idx, i in enumerate(anchors):
        name = lines[i - 1].strip() if i > 0 else ""
        if not name or not name[0].isalpha():
            continue
        end = anchors[idx + 1] - 1 if idx + 1 < len(anchors) else len(lines)
        body_lines = [lines[i].strip()] + [
            l for l in lines[i + 1:end] if not is_table_row(l)
        ]
        body = dehyphenate_join(body_lines)
        body = re.sub(r"\s+", " ", body)
        corpus.add("MAGIC_ITEM", name, body)


# ── SPECIES ("Creature Type:" anchor) ───────────────────────────────

SPECIES_ANCHOR = re.compile(r"^Creature Type:")


def parse_species(pages, corpus):
    lines = strip_footers("\n".join(pages[82:86]).splitlines())
    lines = [l for l in lines if l.strip()]
    anchors = [i for i, l in enumerate(lines) if SPECIES_ANCHOR.match(l.strip())]
    for idx, i in enumerate(anchors):
        name = lines[i - 1].strip() if i > 0 else ""
        if not name or not name[0].isalpha() or len(name) > 30:
            continue
        end = anchors[idx + 1] - 1 if idx + 1 < len(anchors) else len(lines)
        body_lines = [l for l in lines[i:end] if not is_table_row(l)]
        body = dehyphenate_join(body_lines).replace("�", " ")
        body = re.sub(r"\s+", " ", body)
        corpus.add("SPECIES", name, body)


# ── CLASSES (known headings, coarse) ────────────────────────────────

CLASSES = ["Barbarian", "Bard", "Cleric", "Druid", "Fighter", "Monk",
           "Paladin", "Ranger", "Rogue", "Sorcerer", "Warlock", "Wizard"]


def parse_classes(pages, corpus):
    lines = strip_footers("\n".join(pages[27:82]).splitlines())
    lines = [l for l in lines if l.strip()]
    # first occurrence of each class as a standalone heading line
    positions = []
    for ci, cname in enumerate(CLASSES):
        for i, l in enumerate(lines):
            if l.strip() == cname or l.strip() == cname + " Features":
                positions.append((i, cname))
                break
    positions.sort()
    for idx, (i, cname) in enumerate(positions):
        end = positions[idx + 1][0] if idx + 1 < len(positions) else len(lines)
        body_lines = [l for l in lines[i + 1:end] if not is_table_row(l)]
        body = dehyphenate_join(body_lines).replace("�", " ")
        body = re.sub(r"\s+", " ", body)
        corpus.add("CLASS", cname, body)


# ── FEATS / EQUIPMENT / BACKGROUNDS (generic heading chunk) ──────────


def parse_generic(pages, page_range, type_, corpus, skip_titles=()):
    lines = strip_footers("\n".join(pages[page_range[0]:page_range[1]]).splitlines())
    heads = []
    for i, l in enumerate(lines):
        nxt = lines[i + 1] if i + 1 < len(lines) else ""
        if is_glossary_heading(l, nxt):
            heads.append(i)
    for idx, i in enumerate(heads):
        title = lines[i].strip()
        if title in skip_titles:
            continue
        end = heads[idx + 1] if idx + 1 < len(heads) else len(lines)
        body_lines = [l for l in lines[i + 1:end] if not is_table_row(l)]
        body = dehyphenate_join(body_lines).replace("•", " ").replace("�", " ")
        body = re.sub(r"\s+", " ", body)
        if len(body) < 40:
            continue
        corpus.add(type_, title, body)


# ── MONSTERS (two-column -layout split, numbers stripped) ───────────

MON_IDENT = re.compile(
    r"^(Tiny|Small|Medium|Large|Huge|Gargantuan)"
    r"( or (Tiny|Small|Medium|Large|Huge|Gargantuan))? "
    r"[A-Z][a-zA-Z]+( \([^)]+\))?, [A-Z]"
)
MON_SECTIONS = {"Traits", "Actions", "Bonus Actions", "Reactions", "Legendary Actions"}
MON_STAT = re.compile(
    r"^(AC |HP |Speed |Initiative|MOD ?SAVE|Skills |Senses |Gear |Languages "
    r"|Resistances |Immunities |Vulnerabilities |CR |Saving Throws |Passive Perception|XP )",
    re.I,
)


def mon_is_stat(s):
    if MON_STAT.match(s):
        return True
    if re.match(r"^(Str|Dex|Con|Int|Wis|Cha)\b", s) and re.search(r"[+-]\d", s):
        return True
    if s.startswith("MOD SAVE"):
        return True
    return False


def find_gutter(plines):
    """Pick the page's column gutter.

    The whitespace band between the two columns spans several columns; choosing
    the *rightmost* high-whitespace column keeps a left-column word that bleeds
    up to the gutter on the left side, instead of leaking a stray char into the
    right column (the root cause of ``"s  HP 52"`` / ``"E Bite"`` artifacts).
    """
    ratios = {}
    for c in range(48, 70):
        sp = tot = 0
        for l in plines:
            if not l.strip():
                continue
            tot += 1
            if l[c - 1:c + 2].strip() == "":
                sp += 1
        if tot:
            ratios[c] = sp / tot
    if not ratios:
        return None
    mx = max(ratios.values())
    if mx < 0.55:
        return None
    thr = max(0.85, mx - 0.05)
    cand = [c for c, r in ratios.items() if r >= thr]
    return max(cand) if cand else max(ratios, key=ratios.get)


def _clean_right_column(s):
    """Strip column-bleed artifacts from a reflowed right-column line."""
    s = s.rstrip()
    s = re.sub(r"^\s*MOD ?SAVE\s+", "", s)      # ability-table header bleed
    s = re.sub(r"^\s*[A-Za-z]\s{2,}", "", s)    # single bled left-column char
    return s.strip()


def reflow_columns(page):
    plines = page.split("\n")
    g = find_gutter(plines)
    if g is None:
        seq = list(plines)
    else:
        left = [l[:g].rstrip() for l in plines]
        right = [_clean_right_column(l[g:]) for l in plines]
        seq = [l for l in left if l.strip()] + [l for l in right if l.strip()]
    return [l for l in seq if l.strip() and not FOOTER.match(l.strip())]


def mon_clean_name(lines, i):
    """Return (name, name_index) for the stat block whose identity is at i."""
    j = i - 1
    while j >= 0:
        s = re.sub(r"^MOD ?SAVE\s*", "", lines[j].strip()).strip()
        if (not s or mon_is_stat(s) or s in MON_SECTIONS
                or not s[0].isalpha() or s[0].islower()):
            j -= 1
            continue
        return s, j
    return "", i


def strip_monster_numbers(text):
    """Remove every numeric combat stat / mechanic, leaving behavioural prose.

    Belt-and-suspenders: position-independent sweeps so a stat fragment still
    gets removed even when a column-split artifact severs it from its label.
    """
    t = text.replace("�", " ")  # PDF replacement char (e.g. "Recharge 4�6")
    # whole attack-roll mechanic up to its sentence end (handles parentheticals
    # like "+5 (with Advantage…), reach 5 ft." and "feet"/"ft." alike)
    t = re.sub(r"(?:Melee|Ranged|Melee or Ranged) Attack Roll:[^.]*?\.", "", t)
    # "Hit: 7 (1d6 + 4) Piercing damage plus 17 (5d6) Poison damage"
    t = re.sub(
        r"Hit: ?\d+ ?\([^)]*\)\s*\w+ damage(?:[ ,]*plus \d+ ?\([^)]*\)\s*\w+ damage)*",
        "", t)
    # flat (no-parens) damage, e.g. "Hit: 1 Slashing damage"
    t = re.sub(r"Hit: ?\d+ \w+ damage(?:[ ,]*plus \d+ \w+ damage)*", "", t)
    t = re.sub(r"(?:,? plus )?\d+ ?\([^)]*\)\s*\w+ damage", "", t)
    t = re.sub(r"\b[A-Z][a-z]+ Saving Throw: ?DC \d+,?", "Saving Throw:", t)
    t = re.sub(r"\bDC \d+\b", "a DC", t)
    t = re.sub(r"\+\d+ to hit", "", t)
    t = re.sub(r"Failure: ?\d+ ?\([^)]*\)?\s*\w+ damage", "Failure:", t)
    # Recharge range, tolerant of any separator between the two digits
    t = re.sub(r"\bRecharge[^)\w]*\d+(?:[^)\w]*\d+)?", "Recharge", t)
    t = re.sub(r"\b\d+ ?\(XP[^)]*\)", "", t)
    # any remaining numeric "Hit:" lead-in
    t = re.sub(r"Hit: ?\d*", "", t)
    # inline armour-class / hit-point references that survive in prose
    t = re.sub(r"\b(AC|HP) \d+\b", "", t)
    # inline Passive Perception (reflow puts "Senses … Passive Perception 15"
    # mid-line, so the line-start stat filter can miss it)
    t = re.sub(r"\bPassive Perception \d+\b", "", t)
    # inline ability-table fragments merged into prose by the column split
    t = re.sub(r"\bMOD ?SAVE\b", "", t)
    t = re.sub(r"\b(Str|Dex|Con|Int|Wis|Cha) \d+(?: [+-]\d+){0,2}", "", t)
    # bare dice expressions, then bare ability-modifier pairs ("-4 -4") left
    # behind when the column split severs them from their Str/Dex/… label
    t = re.sub(r"\b\d+\s*d\s*\d+(?: ?[+-] ?\d+)?\b", "", t)
    t = re.sub(r"(?<![\w/.])[+-]?\d+ +[+-]?\d+(?![\w/])", " ", t)
    # leftover broken-paren damage debris and emptied parentheses
    t = re.sub(r"[+-] ?\d+\)", "", t)
    t = re.sub(r"\(\s*[+-]?\s*\)", "", t)
    t = re.sub(r"\([\s,]*\)", "", t)
    # tidy debris left behind by removed fragments
    t = re.sub(r"\bplus +(?=[.,;]|$)", "", t)
    t = re.sub(r"\s+([.,;:)])", r"\1", t)
    t = re.sub(r"\(\s+", "(", t)
    t = re.sub(r"([.,;:])\s*,", r"\1", t)
    t = re.sub(r"\.\s*\.", ".", t)
    t = re.sub(r"\s{2,}", " ", t)
    t = re.sub(r"\s+([.,;:])", r"\1", t)
    return t.strip()


def _is_heading_like(s):
    """A short, periodless, title-style line — a monster name or group header."""
    s = s.strip()
    if not s or not s[0].isalpha() or len(s) > 40:
        return False
    if s[-1] in ".,:;":
        return False
    return not mon_is_stat(s)


def parse_monsters(layout_pages, corpus):
    lines = []
    for page in layout_pages[257:]:
        lines += reflow_columns(page)
    idpos = [i for i, l in enumerate(lines) if MON_IDENT.match(l.strip())]
    resolved = [mon_clean_name(lines, i) for i in idpos]
    for idx, i in enumerate(idpos):
        name, _ = resolved[idx]
        if len(name) < 3 or not name[0].isupper():
            continue  # phantom block (identity regex matched inside prose)
        ident = lines[i].strip()
        if idx + 1 < len(idpos):
            body_end = resolved[idx + 1][1]  # next monster's resolved name line
            # back up over any duplicate name / group-heading lines that precede
            # it, so they don't leak into this monster's content (finding 5)
            while body_end > i + 1 and _is_heading_like(lines[body_end - 1]):
                body_end -= 1
        else:
            body_end = len(lines)
        body_lines = []
        for k in range(i + 1, body_end):
            s = lines[k].strip()
            if mon_is_stat(s) or s in MON_SECTIONS:
                continue
            body_lines.append(s)
        prose = dehyphenate_join(body_lines)
        prose = strip_monster_numbers(prose)
        body = ident + ". " + prose if prose else ident + "."
        corpus.add("MONSTER", name, body)


# ════════════════════════════════════════════════════════════════════
#  STRUCTURED character-creation dataset (typed fields, separate file)
# ════════════════════════════════════════════════════════════════════

ABILITY_NAMES = ["Strength", "Dexterity", "Constitution",
                 "Intelligence", "Wisdom", "Charisma"]


def _norm_ws(s):
    return re.sub(r"\s+", " ", s).strip()


def _split_and(s):
    """Split 'A and B' / 'A, B, and C' / 'A or B' into a clean list."""
    s = re.sub(r",?\s+(?:and|or)\s+", ", ", s)
    return [p.strip() for p in s.split(",") if p.strip()]


# ── backgrounds (4) ─────────────────────────────────────────────────

BG_NAMES = ["Acolyte", "Criminal", "Sage", "Soldier"]


def parse_backgrounds(raw_pages):
    text = "\n".join(raw_pages[82:84])
    i = text.find("Background Descriptions")
    if i >= 0:
        text = text[i:]
    text = "\n".join(l for l in text.splitlines() if not FOOTER.match(l.strip()))
    out = []
    for k, name in enumerate(BG_NAMES):
        start = text.find("\n" + name + "\n")
        if start < 0:
            continue
        nxt = BG_NAMES[k + 1] if k + 1 < len(BG_NAMES) else "Character Species"
        end = text.find("\n" + nxt + "\n", start + 1)
        block = _norm_ws(text[start + len(name) + 2: end if end > 0 else None])

        def field(label, nextlabels):
            m = re.search(label + r":\s*(.*?)\s*(?:" + nextlabels + r"):", block)
            return m.group(1).strip() if m else ""

        abilities = field("Ability Scores", "Feat")
        feat = field("Feat", "Skill Proficiencies")
        feat = re.sub(r"\s*\(see \"Feats\"\)", "", feat).strip()
        skills = field("Skill Proficiencies", "Tool Proficiency")
        tool = field("Tool Proficiency", "Equipment")
        tool = re.sub(r"\s*\(see \"[^\"]+\"\)", "", tool).strip()
        meq = re.search(r"Equipment:\s*(.*)$", block)
        equip = meq.group(1).strip() if meq else ""
        ma = re.search(r"\(A\)\s*(.*?)\s*;\s*or\s*\(B\)\s*(.*)$", equip)
        optA = _norm_ws(ma.group(1)) if ma else equip
        optB = _norm_ws(ma.group(2)) if ma else ""
        out.append({
            "index": slugify(name),
            "name": name,
            "abilityScores": _split_and(abilities),
            "feat": feat,
            "skillProficiencies": _split_and(skills),
            "toolProficiency": tool,
            "equipment": {"optionA": optA, "optionB": optB},
        })
    return out


# ── species (9, typed traits, no ability bonuses) ───────────────────

SPECIES_NAMES = ["Dragonborn", "Dwarf", "Elf", "Gnome", "Goliath",
                 "Halfling", "Human", "Orc", "Tiefling"]

# Title-Case rules terms that appear inside trait descriptions but are NOT
# species traits (built from inspecting all 9 species' detected headers).
TRAIT_DENY = {
    "Long Rest", "Short Rest", "Bonus Action", "Magic Action", "Proficiency Bonus",
    "Hit Point", "Hit Points", "Armor Class", "Challenge Rating", "Difficult Terrain",
    "Tremorsense", "Speed", "Giants", "White Cold Breath Weapon",
} | set(ABILITY_NAMES) | {"Large", "Small", "Medium", "Huge", "Tiny", "Gargantuan"}

_TRAIT_HDR = re.compile(r"([A-Z][A-Za-z']+(?: [A-Z][A-Za-z']+){0,3})\.\s+(?=[A-Z])")


def _parse_traits(body):
    cands = [(m.start(), m.group(1)) for m in _TRAIT_HDR.finditer(body)]
    cands = [(s, n) for s, n in cands if n not in TRAIT_DENY]
    traits = []
    for k, (s, n) in enumerate(cands):
        ds = s + len(n) + 2
        de = cands[k + 1][0] if k + 1 < len(cands) else len(body)
        desc = _norm_ws(body[ds:de]).replace("�", "-")
        traits.append({"name": n, "desc": desc})
    return traits


def parse_species_structured(raw_pages):
    lines = [l for l in "\n".join(raw_pages[82:86]).splitlines()
             if l.strip() and not FOOTER.match(l.strip())]
    out = []
    for i, l in enumerate(lines):
        name = l.strip()
        if name not in SPECIES_NAMES or i + 1 >= len(lines):
            continue
        head = lines[i + 1].strip()
        if not head.startswith("Creature Type:"):
            continue
        ct = re.search(r"Creature Type:\s*(.*?)\s*Size:", head)
        sz = re.search(r"Size:\s*(.*?)\s*Speed:", head)
        sp = re.search(r"Speed:\s*(.*)$", head)
        body = []
        for j in range(i + 2, len(lines)):
            s = lines[j].strip()
            if s in SPECIES_NAMES and j + 1 < len(lines) \
                    and lines[j + 1].strip().startswith("Creature Type:"):
                break
            body.append(s)
        btext = _norm_ws(" ".join(body))
        btext = re.sub(r"^As an? [\w]+, you have these special traits\.\s*", "", btext)
        out.append({
            "index": slugify(name),
            "name": name,
            "creatureType": (ct.group(1).strip() if ct else ""),
            "size": (sz.group(1).strip().replace("�", "-") if sz else ""),
            "speed": (sp.group(1).strip().replace("�", "-") if sp else ""),
            "traits": _parse_traits(btext),
        })
    return out


# ── classes (12, typed core traits) ─────────────────────────────────

CLASS_PAGE = {"Barbarian": 27, "Bard": 30, "Cleric": 35, "Druid": 40,
              "Fighter": 46, "Monk": 48, "Paladin": 52, "Ranger": 56,
              "Rogue": 60, "Sorcerer": 63, "Warlock": 69, "Wizard": 76}
CORE_LABELS = ["Primary Ability", "Hit Point Die", "Saving Throw Proficiencies",
               "Skill Proficiencies", "Weapon Proficiencies", "Tool Proficiencies",
               "Armor Training", "Starting Equipment"]


def parse_classes_structured(raw_pages):
    out = []
    for name in CLASSES:
        page = raw_pages[CLASS_PAGE[name]]
        i = page.find("Core %s Traits" % name)
        if i < 0:
            continue
        j = page.find("Becoming a", i)
        block = _norm_ws(page[i:j if j > 0 else None]).replace("Core %s Traits" % name, "")
        parts = re.split(r"(" + "|".join(re.escape(x) for x in CORE_LABELS) + r")", block)
        vals, cur = {}, None
        for seg in parts:
            s = seg.strip()
            if s in CORE_LABELS:
                cur = s
            elif cur:
                vals[cur] = (vals.get(cur, "") + " " + s).strip()

        hd = re.search(r"D(\d+)", vals.get("Hit Point Die", ""))
        skill_raw = vals.get("Skill Proficiencies", "")
        msel = re.search(r"Choose (?:any )?(\d+)", skill_raw)
        choose = int(msel.group(1)) if msel else 0
        if "any" in skill_raw.lower():
            from_list = []
        else:
            mfrom = re.search(r"Choose \d+:\s*(.*)", skill_raw)
            from_list = _split_and(mfrom.group(1)) if mfrom else []
        armor = vals.get("Armor Training", "")
        armor_list = [] if armor.strip().lower().startswith("none") else \
            [a for a in ["Light", "Medium", "Heavy", "Shields"] if a in armor]
        out.append({
            "index": slugify(name),
            "name": name,
            "primaryAbility": vals.get("Primary Ability", "").strip(),
            "hitDie": int(hd.group(1)) if hd else 0,
            "savingThrows": _split_and(vals.get("Saving Throw Proficiencies", "")),
            "skillProficiencies": {"choose": choose, "from": from_list},
            "weaponProficiencies": vals.get("Weapon Proficiencies", "").strip(),
            "armorTraining": armor_list,
            "startingEquipment": vals.get("Starting Equipment", "").strip(),
        })
    return out


# ── feats (real names + categories) ─────────────────────────────────

FEAT_CAT = re.compile(
    r"^(Origin|General|Fighting Style|Epic Boon) Feat(?: \(Prerequisite: (.+)\))?$")
FEAT_CAT_NORM = {"Origin": "Origin", "General": "General",
                 "Fighting Style": "FightingStyle", "Epic Boon": "EpicBoon"}
FEAT_SECTION_HDRS = {"Origin Feats", "General Feats", "Fighting Style Feats",
                     "Epic Boon Feats", "Feat Descriptions", "Parts of a Feat"}


def parse_feats_list(raw_pages):
    lines = [l.strip() for l in "\n".join(raw_pages[86:88]).splitlines()
             if l.strip() and not FOOTER.match(l.strip())]
    anchors = [i for i, l in enumerate(lines) if FEAT_CAT.match(l)]
    out = []
    for k, a in enumerate(anchors):
        m = FEAT_CAT.match(lines[a])
        name = lines[a - 1].strip()
        if name in FEAT_SECTION_HDRS or not name or not name[0].isalpha():
            continue
        end = anchors[k + 1] - 1 if k + 1 < len(anchors) else len(lines)
        desc_lines = [l for l in lines[a + 1:end] if l not in FEAT_SECTION_HDRS]
        desc = _norm_ws(dehyphenate_join(desc_lines)).replace("�", "-")
        out.append({
            "index": slugify(name),
            "name": name,
            "category": FEAT_CAT_NORM[m.group(1)],
            "prerequisite": m.group(2),
            "desc": desc,
        })
    return out


# ── spells (339, typed) ─────────────────────────────────────────────


def parse_spells_structured(raw_pages):
    text = "\n".join(raw_pages[106:175])
    compact = [l.strip() for l in strip_footers(text.splitlines()) if l.strip()]
    hpos = [i for i, l in enumerate(compact) if SPELL_HDR.match(l)]
    out = []
    for idx, i in enumerate(hpos):
        name = compact[i - 1]
        header = compact[i]
        if "Cantrip" in header:
            level = 0
            ms = re.match(r"(\w+) Cantrip \((.*)\)", header)
            school = ms.group(1) if ms else ""
            classes = ms.group(2) if ms else ""
        else:
            ml = re.match(r"Level (\d+) (\w+) \((.*)\)", header)
            level = int(ml.group(1)) if ml else -1
            school = ml.group(2) if ml else ""
            classes = ml.group(3) if ml else ""
        nxt_name = hpos[idx + 1] - 1 if idx + 1 < len(hpos) else len(compact)
        # accumulate the casting-info line(s) until Duration is captured
        buf, k = "", i + 1
        while k < nxt_name and "Duration:" not in buf:
            buf = (buf + " " + compact[k]).strip()
            k += 1
        cm = re.search(
            r"Casting Time:\s*(.*?)\s*Range:\s*(.*?)\s*Components?:\s*(.*?)\s*Duration:\s*(.*)$",
            buf)
        casting_time = cm.group(1).strip() if cm else ""
        rng = cm.group(2).strip() if cm else ""
        components = cm.group(3).strip() if cm else ""
        duration = cm.group(4).strip() if cm else ""
        desc_lines = compact[k:nxt_name]
        desc_full = dehyphenate_join(desc_lines)
        higher = None
        for marker in ("Using a Higher-Level Spell Slot.", "Cantrip Upgrade."):
            mi = desc_full.find(marker)
            if mi >= 0:
                higher = desc_full[mi + len(marker):].strip()
                desc_full = desc_full[:mi].strip()
                break
        out.append({
            "index": slugify(name),
            "name": name,
            "level": level,
            "school": school,
            "classes": [c.strip() for c in classes.split(",") if c.strip()],
            "castingTime": casting_time,
            "range": rng,
            "components": components,
            "duration": duration,
            "concentration": "Concentration" in duration,
            "ritual": "Ritual" in casting_time,
            "desc": _norm_ws(desc_full),
            "higherLevel": _norm_ws(higher) if higher else None,
        })
    return out


# ── equipment (weapons / armor / gear) ──────────────────────────────

WEAPON_CATS = {"Simple Melee Weapons": "Simple Melee",
               "Simple Ranged Weapons": "Simple Ranged",
               "Martial Melee Weapons": "Martial Melee",
               "Martial Ranged Weapons": "Martial Ranged"}
_DMG_FULL = re.compile(r"^(.+?)\s+(\d+(?:d\d+)?)\s+(Bludgeoning|Piercing|Slashing)$")
_DMG_ONLY = re.compile(r"^(\d+(?:d\d+)?)\s+(Bludgeoning|Piercing|Slashing)$")


def _parse_weapons(page):
    seg = page[page.find("Simple Melee Weapons"):page.find("Properties")]
    lines = [l.strip() for l in seg.splitlines() if l.strip()]
    cat, pend, rows = None, None, []
    for l in lines:
        if l in WEAPON_CATS:
            cat, pend = WEAPON_CATS[l], None
            continue
        m = _DMG_FULL.match(l)
        if m:
            rows.append([m.group(1).strip(), m.group(2), m.group(3), cat])
            pend = None
            continue
        m2 = _DMG_ONLY.match(l)
        if m2 and pend:
            rows.append([pend, m2.group(1), m2.group(2), cat])
            pend = None
            continue
        pend = l

    def col(label, nextlabel):
        a = page.find(label)
        b = page.find(nextlabel, a + 1) if nextlabel else len(page)
        return page[a + len(label): b if b > 0 else None]

    mastery = col("Mastery", "Weight").split()
    weight = re.findall(r"\d+(?:/\d+)? lb\.|--", col("Weight", "Cost"))
    cost = re.findall(r"[\d,]+ [GSCE]P|--", col("Cost", None))
    n = len(rows)
    aligned = len(mastery) == n and len(weight) == n and len(cost) == n
    out = []
    for k, (name, dmg, dtype, wcat) in enumerate(rows):
        item = {
            "index": slugify(name), "name": name, "category": "weapon",
            "weaponType": wcat, "damage": dmg, "damageType": dtype,
        }
        if aligned:
            item["mastery"] = mastery[k]
            item["weight"] = weight[k]
            item["cost"] = cost[k]
        out.append(item)
    return out


_ARMOR_AC = re.compile(r"^(.+?)\s+(\d+|\+\d+)( \+ Dex modifier(?: \(max \d+\))?)?$")


def _parse_armor(page):
    seg = page[page.find("Light Armor (1 Minute"): page.find("\nStrength\n")]
    lines = [l.strip() for l in seg.splitlines() if l.strip()]
    cat, pend, rows = None, None, []

    def emit(name, ac):
        base = re.search(r"\d+", ac)
        rows.append({
            "index": slugify(name), "name": name, "category": "armor",
            "armorCategory": cat, "ac": ac,
            "baseAc": int(base.group(0)) if base else None,
        })

    for l in lines:
        mc = re.match(r"^(Light|Medium|Heavy) Armor \(", l)
        if mc:
            cat, pend = mc.group(1), None
            continue
        if l.startswith("Shield (") and "Action" in l:
            cat, pend = "Shield", None
            continue
        m = _ARMOR_AC.match(l)
        if m and m.group(1).strip() not in ("Armor",):
            emit(m.group(1).strip(), (m.group(2) + (m.group(3) or "")).strip())
            pend = None
            continue
        m2 = re.match(r"^(\d+|\+\d+)( \+ Dex modifier(?: \(max \d+\))?)?$", l)
        if m2 and pend:
            emit(pend, (m2.group(1) + (m2.group(2) or "")).strip())
            pend = None
            continue
        pend = l
    return rows


_GEAR = re.compile(r"^(.{2,40}?) \((\d[\d,]*) (GP|SP|CP|EP|PP)\)$", re.M)


def _parse_gear(raw_pages):
    gtext = "\n".join(raw_pages[94:100])
    out, seen = [], set()
    for m in _GEAR.finditer(gtext):
        name = m.group(1).strip()
        if name in seen or not name[0].isalpha():
            continue
        seen.add(name)
        out.append({
            "index": slugify(name), "name": name, "category": "gear",
            "cost": m.group(2) + " " + m.group(3),
        })
    return out


def parse_equipment_structured(raw_pages):
    return _parse_weapons(raw_pages[90]) + _parse_armor(raw_pages[91]) \
        + _parse_gear(raw_pages)


# ════════════════════════════════════════════════════════════════════
#  COMBAT mechanics: machine-readable spell effects + monster stat blocks
#  (consumed by the backend SpellCatalog / MonsterCatalog at runtime)
# ════════════════════════════════════════════════════════════════════

WORD_NUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
            "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
ABIL_FULL = {"strength": "STR", "dexterity": "DEX", "constitution": "CON",
             "intelligence": "INT", "wisdom": "WIS", "charisma": "CHA"}
DMG_TYPES = ("Acid|Bludgeoning|Cold|Fire|Force|Lightning|Necrotic|"
             "Piercing|Poison|Psychic|Radiant|Slashing|Thunder")
CONDITIONS = ("blinded|charmed|deafened|frightened|grappled|incapacitated|"
              "paralyzed|petrified|poisoned|prone|restrained|stunned|unconscious")


def _dice_norm(s):
    """'2d6 + 5' → '2d6+5'; '1d4 + 1' → '1d4+1'."""
    return re.sub(r"\s*([+-])\s*", r"\1", s.strip())


# ── spell combat parsing (post-process the typed spell dataset) ──────

_AOE = re.compile(
    r"(\d+)-foot(?:-radius)?(?:[ -](?:radius|wide|tall|long|high))? ?"
    r"(Sphere|Cube|Cone|Line|Emanation|Cylinder)", re.I)
_DMG = re.compile(
    r"(\d+d\d+(?:\s*\+\s*\d+)?)\s+(" + DMG_TYPES + r")\s+damage")
_HEAL = re.compile(r"(?:regain[s]?|Hit Points equal to)\b[^.]*?(\d+d\d+)", re.I)


def parse_spell_combat(sp):
    """Derive machine-readable combat fields from a spell's prose. Best-effort:
    ``parsed`` is True only when the engine can resolve it mechanically
    (damage / heal / a recognized buff or save-based control)."""
    desc = sp.get("desc") or ""
    higher = sp.get("higherLevel") or ""
    rng = (sp.get("range") or "").lower()
    low = desc.lower()
    c = {
        "effectType": None, "targetType": None, "resolution": None,
        "saveAbility": None, "damageDice": None, "damageType": None,
        "healDice": None, "addCastingMod": False, "halfOnSave": False,
        "scaling": {"perSlotAbove": None, "cantripDie": None},
        "aoe": None, "maxTargets": 1, "projectiles": 1,
        "condition": None, "parsed": False,
    }

    # resolution
    if re.search(r"make a (?:ranged|melee) spell attack", low):
        c["resolution"] = "SPELL_ATTACK"
    msave = re.search(
        r"(strength|dexterity|constitution|intelligence|wisdom|charisma) "
        r"saving throw", low)
    if msave:
        c["resolution"] = "SAVE"
        c["saveAbility"] = ABIL_FULL[msave.group(1)]

    # damage
    mdmg = _DMG.search(desc)
    if mdmg:
        c["damageDice"] = _dice_norm(mdmg.group(1))
        c["damageType"] = mdmg.group(2)
    if "half as much damage" in low or "half damage" in low:
        c["halfOnSave"] = True

    # heal (exclude spells that merely mention regaining HP as a restriction)
    if re.search(r"regain[s]? (?:a number of )?hit points", low) or \
            "hit points equal to" in low:
        mheal = _HEAL.search(desc)
        if mheal:
            c["healDice"] = mheal.group(1)
            c["addCastingMod"] = "spellcasting ability modifier" in low

    # scaling
    mslot = re.search(r"increases by (\d+d\d+) for each spell slot level above",
                      higher, re.I)
    if mslot:
        c["scaling"]["perSlotAbove"] = mslot.group(1)
    mcan = re.search(r"increases by (\d+d\d+) when you reach", higher, re.I)
    if mcan:
        c["scaling"]["cantripDie"] = mcan.group(1)

    # multi-projectile (Magic Missile darts / Eldritch Blast beams / rays)
    mproj = re.search(
        r"\b(one|two|three|four|five)\b (?:glowing )?(?:darts?|rays?|beams?)", low)
    if mproj:
        c["projectiles"] = WORD_NUM.get(mproj.group(1), 1)

    # area of effect
    maoe = _AOE.search(desc)
    if maoe:
        c["aoe"] = {"shape": maoe.group(2).lower(), "size": int(maoe.group(1))}

    # imposed condition (for control/debuff)
    mcond = re.search(r"\b(" + CONDITIONS + r")\b", low)
    if mcond:
        c["condition"] = mcond.group(1)

    # effect type
    if c["healDice"]:
        c["effectType"] = "HEAL"
    elif c["damageDice"]:
        c["effectType"] = "DAMAGE"
    elif re.search(r"bonus to ac|base ac becomes|temporary hit points|"
                   r"adds 1d\d+|you have (?:advantage|resistance)", low):
        c["effectType"] = "BUFF"
    elif c["condition"] and c["resolution"] == "SAVE":
        c["effectType"] = "CONTROL"
    else:
        c["effectType"] = "UTILITY"

    # target type
    if c["aoe"]:
        c["targetType"] = "AREA"
    elif c["effectType"] == "HEAL":
        c["targetType"] = "ALLY"
    elif c["effectType"] == "BUFF":
        c["targetType"] = "SELF" if ("self" in rng or "yourself" in low) else "ALLY"
    elif c["effectType"] in ("DAMAGE", "CONTROL"):
        c["targetType"] = "ENEMY"
    else:
        c["targetType"] = "SELF" if "self" in rng else "ANY"

    if c["resolution"] is None:
        c["resolution"] = "AUTO"   # e.g. Magic Missile (auto-hit)

    # max targets
    if c["aoe"]:
        c["maxTargets"] = None     # everything in the area
    elif c["projectiles"] > 1:
        c["maxTargets"] = c["projectiles"]
    else:
        mup = re.search(r"up to (one|two|three|four|five|six) "
                        r"(?:creatures|targets)", low)
        c["maxTargets"] = WORD_NUM.get(mup.group(1)) if mup else 1

    c["parsed"] = c["effectType"] in ("DAMAGE", "HEAL", "BUFF", "CONTROL")
    return c


# ── monster stat-block parsing (full stats, NOT stripped) ───────────

_CR_MAP = {"1/8": 0.125, "1/4": 0.25, "1/2": 0.5}
MON_ATTACK = re.compile(
    r"(?P<name>[A-Z][A-Za-z0-9 '/()+-]{1,38}?)\.\s+"
    r"(?P<kind>Melee or Ranged|Melee|Ranged) Attack Roll:\s*\+(?P<tohit>\d+)"
    r"(?P<mid>.*?)\bHit:\s*"
    r"(?:\d+\s*\((?P<dice>\d+d\d+(?:\s*[+-]\s*\d+)?)\)|(?P<flat>\d+))\s*"
    r"(?P<dtype>" + DMG_TYPES + r")\s+damage", re.I)


def _cr_val(s):
    return _CR_MAP.get(s) if "/" in s else float(s)


def _monster_key(name):
    return re.sub(r"[^A-Z0-9]+", "_", name.upper()).strip("_")


def parse_monsters_structured(layout_pages):
    """Parse the two-column monster stat blocks into full combat stats. Unlike
    ``parse_monsters`` (which strips numbers for stat-free lore), this keeps
    AC / HP / abilities / attacks for the combat engine."""
    lines = []
    for page in layout_pages[257:]:
        lines += reflow_columns(page)
    idpos = [i for i, l in enumerate(lines) if MON_IDENT.match(l.strip())]
    resolved = [mon_clean_name(lines, i) for i in idpos]
    out, seen = [], set()
    for idx, i in enumerate(idpos):
        name, _ = resolved[idx]
        # a stat fragment (e.g. "Initiative +2 (12)") sometimes bleeds onto the
        # name line during column reflow — cut it back to the bare name
        name = re.split(r"\s+Initiative\b", name)[0].strip()
        if len(name) < 3 or not name[0].isupper():
            continue
        ident = lines[i].strip()
        if idx + 1 < len(idpos):
            body_end = resolved[idx + 1][1]
            while body_end > i + 1 and _is_heading_like(lines[body_end - 1]):
                body_end -= 1
        else:
            body_end = len(lines)
        block = lines[i:body_end]
        btext = " ".join(block)

        mid = re.match(r"^(Tiny|Small|Medium|Large|Huge|Gargantuan)"
                       r"(?: or \w+)? ([A-Z][a-z]+)", ident)
        mac = re.search(r"\bAC (\d+)", btext)
        mhp = re.search(r"\bHP (\d+)(?:\s*\(([^)]+)\))?", btext)
        mspeed = re.search(r"\bSpeed (\d+)", btext)
        mcr = re.search(r"\bCR (\d+/\d+|\d+)", btext)
        abilities = {}
        for ab in ("Str", "Dex", "Con", "Int", "Wis", "Cha"):
            am = re.search(r"\b" + ab + r"\s+(\d+)\b", btext)
            if am:
                abilities[ab.upper()] = int(am.group(1))
        dex = abilities.get("DEX", 10)

        # attacks live in the Actions section (skip Legendary Actions)
        sec = {l.strip(): k for k, l in enumerate(block) if l.strip() in MON_SECTIONS}
        action_text = ""
        if "Actions" in sec:
            start = sec["Actions"]
            end = len(block)
            if "Legendary Actions" in sec and sec["Legendary Actions"] > start:
                end = sec["Legendary Actions"]
            action_text = dehyphenate_join(block[start + 1:end])

        attacks = []
        for m in MON_ATTACK.finditer(action_text):
            midtxt = m.group("mid")
            reach = re.search(r"reach (\d+)", midtxt)
            arange = re.search(r"range (\d+)(?:/(\d+))?", midtxt)
            attacks.append({
                "name": re.sub(r"\s+", " ", m.group("name")).strip(),
                "kind": "RANGED" if m.group("kind").lower() == "ranged" else "MELEE",
                "toHit": int(m.group("tohit")),
                "reach": int(reach.group(1)) if reach else None,
                "range": int(arange.group(1)) if arange else None,
                "damageDice": _dice_norm(m.group("dice") or m.group("flat")),
                "damageType": m.group("dtype").title(),
            })

        multiattack = None
        mm = re.search(r"Multiattack\.\s*([^.]*\.)", action_text)
        if mm:
            mc = re.search(r"makes (\w+) (\w[\w ]*?) attacks", mm.group(1).lower())
            if mc and mc.group(1) in WORD_NUM:
                multiattack = {"count": WORD_NUM[mc.group(1)],
                               "attack": mc.group(2).strip().title()}

        key = _monster_key(name)
        if not key or key in seen:
            continue
        seen.add(key)
        out.append({
            "key": key, "name": name,
            "size": mid.group(1) if mid else None,
            "type": mid.group(2) if mid else None,
            "cr": _cr_val(mcr.group(1)) if mcr else None,
            "ac": int(mac.group(1)) if mac else None,
            "hp": int(mhp.group(1)) if mhp else None,
            "hpDice": _dice_norm(mhp.group(2)) if (mhp and mhp.group(2)) else None,
            "speed": int(mspeed.group(1)) if mspeed else None,
            "dexMod": (dex - 10) // 2,
            "abilities": abilities,
            "attacks": attacks,
            "multiattack": multiattack,
        })
    return out


# ── manual overrides (deep-merge after parsing) ─────────────────────


def _deep_merge(base, over):
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            _deep_merge(base[k], v)
        else:
            base[k] = v
    return base


def apply_overrides(spells, monsters):
    path = Path(__file__).resolve().parent / "srd-overrides.json"
    if not path.is_file():
        return 0, 0
    ov = json.loads(path.read_text(encoding="utf-8"))
    ns = nm = 0
    by_index = {s["index"]: s for s in spells}
    for idx, patch in (ov.get("spells") or {}).items():
        sp = by_index.get(idx)
        if sp is not None:
            sp.setdefault("combat", {})
            _deep_merge(sp["combat"], patch)
            ns += 1
    by_key = {m["key"]: m for m in monsters}
    for key, patch in (ov.get("monsters") or {}).items():
        mon = by_key.get(key)
        if mon is not None:
            _deep_merge(mon, patch)
        else:
            monsters.append(patch)
        nm += 1
    return ns, nm


def build_structured(raw_pages):
    spells = parse_spells_structured(raw_pages)
    for sp in spells:
        sp["combat"] = parse_spell_combat(sp)
    return {
        "source": "SRD 5.2.1 (CC-BY-4.0)",
        "backgrounds": parse_backgrounds(raw_pages),
        "species": parse_species_structured(raw_pages),
        "classes": parse_classes_structured(raw_pages),
        "feats": parse_feats_list(raw_pages),
        "spells": spells,
        "equipment": parse_equipment_structured(raw_pages),
    }


# ── main ────────────────────────────────────────────────────────────


def main():
    if len(sys.argv) < 2:
        print("usage: python tools/extract-srd.py <SRD-5.2.1.pdf>", file=sys.stderr)
        sys.exit(2)
    pdf = Path(sys.argv[1])
    if not pdf.is_file():
        print("PDF not found: " + str(pdf), file=sys.stderr)
        sys.exit(2)

    raw_pages = pdftotext(pdf, layout=False).split("\f")
    layout_pages = pdftotext(pdf, layout=True).split("\f")

    # structured dataset (also reused to fix the RAG feat / background gaps)
    structured = build_structured(raw_pages)

    # combat stat blocks (full numbers — kept OUT of the lore corpus below)
    monsters = parse_monsters_structured(layout_pages)
    n_sp_ov, n_mon_ov = apply_overrides(structured["spells"], monsters)

    corpus = Corpus()
    parse_spells(raw_pages, corpus)
    parse_glossary(raw_pages, corpus)
    parse_prose_rules(raw_pages, (4, 18), corpus)
    parse_prose_rules(raw_pages, (191, 203), corpus)
    parse_species(raw_pages, corpus)
    parse_classes(raw_pages, corpus)
    parse_generic(raw_pages, (88, 103), "EQUIPMENT", corpus)
    parse_magic_items(raw_pages, corpus)
    parse_monsters(layout_pages, corpus)

    # FEAT entries — real feat names + descriptions (replaces the old generic
    # parser that emitted duplicate "Origin Feat" placeholder titles)
    for f in structured["feats"]:
        label = {"Origin": "Origin", "General": "General",
                 "FightingStyle": "Fighting Style", "EpicBoon": "Epic Boon"}[f["category"]]
        body = "%s feat." % label
        if f["prerequisite"]:
            body += " Prerequisite: %s." % f["prerequisite"]
        body += " " + f["desc"]
        corpus.add("FEAT", f["name"], body)

    # BACKGROUND entries — previously missing from the RAG corpus
    for b in structured["backgrounds"]:
        body = ("Ability Scores: %s. Feat: %s. Skill Proficiencies: %s. "
                "Tool Proficiency: %s. Starting equipment option A: %s") % (
            ", ".join(b["abilityScores"]), b["feat"],
            ", ".join(b["skillProficiencies"]), b["toolProficiency"],
            b["equipment"]["optionA"])
        corpus.add("BACKGROUND", b["name"], body)

    # key uniqueness invariant
    keys = [e["key"] for e in corpus.entries]
    assert len(keys) == len(set(keys)), "duplicate keys generated"

    res = Path(__file__).resolve().parent.parent / "src" / "main" / "resources"
    out_path = res / "srd" / "srd-5.2.1.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(corpus.entries, ensure_ascii=False, indent=1), encoding="utf-8"
    )

    struct_path = res / "dnd5e" / "srd-5.2.1-structured.json"
    struct_path.parent.mkdir(parents=True, exist_ok=True)
    struct_path.write_text(
        json.dumps(structured, ensure_ascii=False, indent=1), encoding="utf-8"
    )

    monsters_path = res / "dnd5e" / "monsters.json"
    monsters_path.write_text(
        json.dumps(monsters, ensure_ascii=False, indent=1), encoding="utf-8"
    )

    counts = {}
    for e in corpus.entries:
        counts[e["type"]] = counts.get(e["type"], 0) + 1
    print("Wrote %d RAG entries to %s" % (len(corpus.entries), out_path))
    for t in sorted(counts):
        print("  %-12s %d" % (t, counts[t]))
    print("Wrote structured dataset to %s" % struct_path)
    for key in ("backgrounds", "species", "classes", "feats", "spells", "equipment"):
        print("  %-12s %d" % (key, len(structured[key])))

    # ── combat coverage report ──────────────────────────────────────
    spells = structured["spells"]
    by_eff = {}
    for s in spells:
        by_eff[s["combat"]["effectType"]] = by_eff.get(s["combat"]["effectType"], 0) + 1
    parsed = sum(1 for s in spells if s["combat"]["parsed"])
    print("Spell combat coverage: %d/%d parsed (mechanical)" % (parsed, len(spells)))
    for eff in sorted(by_eff):
        print("  %-9s %d" % (eff, by_eff[eff]))

    with_ac = sum(1 for m in monsters if m["ac"] is not None)
    with_hp = sum(1 for m in monsters if m["hp"] is not None)
    with_atk = sum(1 for m in monsters if m["attacks"])
    print("Wrote %d monster stat blocks to %s" % (len(monsters), monsters_path))
    print("  with AC %d / with HP %d / with >=1 attack %d"
          % (with_ac, with_hp, with_atk))
    if n_sp_ov or n_mon_ov:
        print("  overrides applied: %d spells, %d monsters" % (n_sp_ov, n_mon_ov))


if __name__ == "__main__":
    main()
