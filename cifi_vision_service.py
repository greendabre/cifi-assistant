import os
import re
import cv2
import json
import base64
import zlib
import numpy as np
from http.server import HTTPServer, BaseHTTPRequestHandler
from rapidocr_onnxruntime import RapidOCR

ocr = RapidOCR(use_angle_cls=False)

def get_ocr_boxes(img):
    res, _ = ocr(img)
    boxes = []
    if not res:
        return boxes
    for item in res:
        pts = item[0]
        text = str(item[1]).strip()
        score = float(item[2])
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        left, right = min(xs), max(xs)
        top, bottom = min(ys), max(ys)
        boxes.append({
            "text": text,
            "left": left,
            "right": right,
            "top": top,
            "bottom": bottom,
            "centerX": (left + right) / 2.0,
            "centerY": (top + bottom) / 2.0,
            "width": right - left,
            "height": bottom - top,
            "score": score
        })
    return boxes

def recognize_crop(img_crop):
    if img_crop is None or img_crop.size == 0:
        return ""
    h, w = img_crop.shape[:2]
    if h < 32:
        scale = 32.0 / h
        img_crop = cv2.resize(img_crop, (max(32, int(w * scale)), 32), interpolation=cv2.INTER_CUBIC)
    if len(img_crop.shape) == 2:
        img_bgr = cv2.cvtColor(img_crop, cv2.COLOR_GRAY2BGR)
    elif img_crop.shape[2] == 4:
        img_bgr = cv2.cvtColor(img_crop, cv2.COLOR_BGRA2BGR)
    else:
        img_bgr = img_crop

    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    norm = cv2.normalize(gray, None, 0, 255, cv2.NORM_MINMAX)
    norm_bgr = cv2.cvtColor(norm, cv2.COLOR_GRAY2BGR)

    rec_res, _ = ocr.text_recognizer([norm_bgr])
    if rec_res and rec_res[0]:
        text = rec_res[0][0].replace('\u3002', '0').replace('o', '0').replace('O', '0')
        return text.strip()
    return ""

def clean_cifi_number(raw):
    if not raw:
        return "0"
    s = str(raw).strip().replace('\u3002', '0')
    s = re.sub(r'^[Oo0](?=[1-9][.:,])', '', s)
    s = s.replace(':', '.').replace(',', '.')
    s = re.sub(r'\.{2,}', '.', s)

    # Normalize OCR misread of 'l' (lowercase L) between digits e.g. 1.5le7572 -> 1.51e7572
    s = re.sub(r'(\d)\s*[lI|]\s*([eE\d])', r'\g<1>1\g<2>', s)
    s = re.sub(r'(\d)\s*[lI|]$', r'\g<1>1', s)

    # Scientific notation
    m_exp = re.search(r'([0-9.]+)[eE](\d+)', s)
    if m_exp:
        return f"{m_exp.group(1)}e{m_exp.group(2)}"

    # Multiplier suffix
    m_suf = re.search(r'([0-9.]+)\s*([a-zA-Z]+)', s)
    if m_suf:
        num = m_suf.group(1)
        suf = m_suf.group(2).lower()
        if suf in ['k', 'm', 'b', 't', 'qa', 'qu', 'sx', 'sp', 'o', 'n', 'd']:
            return f"{num}{suf}"

    clean = re.sub(r'[^0-9.]', '', s)
    return clean or "0"

def clean_rank_level(raw, max_rank=999):
    if not raw:
        return 0
    s = str(raw).strip().replace(' ', '').replace('\u3002', '0')
    s = re.sub(r'[-–—|\\]', '/', s)
    m = re.search(r'(\d+)\s*/\s*(\d+)', s)
    if m:
        val = int(m.group(1))
        if val <= max_rank:
            return val
    digits = re.sub(r'\D', '', s)
    if digits:
        val = int(digits)
        if val <= max_rank:
            return val
    return 0

def classify_page(boxes, W, H):
    full_text = " ".join(b["text"].upper() for b in boxes)
    if "PRIMARY" in full_text and ("SPECIAL" in full_text or "ACADEMY" in full_text or "HUNTER" in full_text):
        return "profile_inventory"
    if "OUROBOROS" in full_text or "CURRENT RUN" in full_text or "ALL TIME HIGHEST" in full_text:
        return "profile_stats"
    if "EXODUS" in full_text:
        return "gem_exodus"
    if "TEMPORALITY" in full_text:
        return "gem_temp"
    if "INNOVATION" in full_text:
        return "gem_inno"
    if "CREATION" in full_text:
        return "gem_creat"
    if "ATTRACTION" in full_text or "STELLAR FORCES" in full_text:
        return "gem_attr"
    if "GEM OF POWER" in full_text or "FOUNDATION OF REALITY" in full_text or ("POWER" in full_text and "CATCH" not in full_text):
        return "gem_pwr"
    if "EVOLUTION" in full_text:
        return "gem_evo"
    return "unknown"

def sort_grid_boxes(boxes, row_tol=25):
    rows = []
    for b in sorted(boxes, key=lambda x: x["centerY"]):
        matched = False
        for r in rows:
            if abs(r[0]["centerY"] - b["centerY"]) < row_tol:
                r.append(b)
                matched = True
                break
        if not matched:
            rows.append([b])
    res = []
    for r in rows:
        r.sort(key=lambda x: x["centerX"])
        res.extend(r)
    return res


def parse_inventory(img, boxes):
    H, W = img.shape[:2]
    data = {
        "player": "---",
        "level": 0,
        "capturedDate": "---",
        "primaryResources": [
            {"key": "diamonds", "label": "Diamonds", "val": "0"},
            {"key": "tokens", "label": "Tokens", "val": "0"},
            {"key": "cells", "label": "Cells", "val": "0"},
            {"key": "mps", "label": "MPs", "val": "0"},
            {"key": "shards", "label": "Shards", "val": "0"},
            {"key": "rps", "label": "RPs", "val": "0"},
            {"key": "aps", "label": "APs", "val": "0"},
            {"key": "orbs", "label": "Orbs", "val": "0"}
        ],
        "specialResources": [
            {"key": "cores", "label": "Cores", "val": "0"},
            {"key": "blueprints", "label": "Blueprints", "val": "0"},
            {"key": "dark_cores", "label": "Dark Cores", "val": "0"},
            {"key": "fragments", "label": "Fragments", "val": "0"}
        ],
        "academyMaterials": [
            {"key": "difar", "label": "Difar", "val": "0"},
            {"key": "kento", "label": "Kento", "val": "0"},
            {"key": "chromium", "label": "Chromium", "val": "0"},
            {"key": "exon", "label": "Exon", "val": "0"},
            {"key": "organium", "label": "Organium", "val": "0"},
            {"key": "adamorphium", "label": "Adamorphium", "val": "0"},
            {"key": "moskom", "label": "Moskom", "val": "0"},
            {"key": "darkseid", "label": "Darkseid", "val": "0"},
            {"key": "tokenium", "label": "Tokenium", "val": "0"}
        ],
        "hunterLoot": [
            {"key": "obsidian", "label": "Obsidian", "val": "0"},
            {"key": "behlium", "label": "Behlium", "val": "0"},
            {"key": "hbm", "label": "HBM", "val": "0"},
            {"key": "farahyte", "label": "Farahyte", "val": "0"},
            {"key": "galvarium", "label": "Galvarium", "val": "0"},
            {"key": "vectid", "label": "Vectid", "val": "0"},
            {"key": "glacium", "label": "Glacium", "val": "0"},
            {"key": "quartz", "label": "Quartz", "val": "0"},
            {"key": "tesseracts", "label": "Tesseracts", "val": "0"}
        ]
    }

    # Player Name
    prof_b = next((b for b in boxes if "PLAYER" in b["text"].upper() and "PROFILE" in b["text"].upper()), None)
    if prof_b:
        name_cands = [b for b in boxes if b["centerY"] < prof_b["top"] and b["centerY"] >= H * 0.02 and "PLAYER" not in b["text"].upper()]
        name_cands.sort(key=lambda b: b["bottom"], reverse=True)
        if name_cands:
            data["player"] = name_cands[0]["text"].strip()
    else:
        top_cands = [b for b in boxes if b["centerY"] <= H * 0.08 and "PLAYER" not in b["text"].upper()]
        if top_cands:
            data["player"] = top_cands[0]["text"].strip()

    # Player Level
    lv_b = next((b for b in boxes if b["centerY"] <= H * 0.15 and ("LV." in b["text"].upper() or "LEVEL" in b["text"].upper())), None)
    if lv_b:
        m = re.search(r'(\d+)', lv_b["text"])
        if m:
            data["level"] = int(m.group(1))

    # Captured Date
    date_b = next((b for b in boxes if re.search(r'\d{2}/\d{2}/\d{4}', b["text"])), None)
    if date_b:
        data["capturedDate"] = date_b["text"].strip()

    # Section headers (exclude "Emblems are primary progress indicators")
    prim_h = next((b for b in boxes if "PRIMARY" in b["text"].upper() and "INDICATOR" not in b["text"].upper() and b["centerY"] > H * 0.30), None)
    spec_h = next((b for b in boxes if "SPECIAL" in b["text"].upper()), None)
    acad_h = next((b for b in boxes if "ACADEMY" in b["text"].upper()), None)
    hunt_h = next((b for b in boxes if "HUNTER" in b["text"].upper()), None)
    ret_b = next((b for b in boxes if "RETURN" in b["text"].upper()), None)

    y_prim = prim_h["bottom"] if prim_h else int(H * 0.40)
    y_spec = spec_h["top"] if spec_h else int(H * 0.51)
    y_spec_b = spec_h["bottom"] if spec_h else int(H * 0.53)
    y_acad = acad_h["top"] if acad_h else int(H * 0.57)
    y_acad_b = acad_h["bottom"] if acad_h else int(H * 0.585)
    y_hunt = hunt_h["top"] if hunt_h else int(H * 0.755)
    y_hunt_b = hunt_h["bottom"] if hunt_h else int(H * 0.77)
    y_end = ret_b["top"] if ret_b else int(H * 0.95)

    # 1. Primary Resources (8 boxes)
    prim_boxes = [b for b in boxes if b["centerY"] > y_prim and b["centerY"] < y_spec and re.search(r'[0-9]', b["text"])]
    prim_sorted = sort_grid_boxes(prim_boxes, 25)
    for i, b in enumerate(prim_sorted[:8]):
        data["primaryResources"][i]["val"] = clean_cifi_number(b["text"])

    # 2. Special Resources (4 columns)
    spec_cols = [int(W * 0.28), int(W * 0.50), int(W * 0.72)]
    spec_row = [b for b in boxes if b["centerY"] > y_spec_b and b["centerY"] < y_acad and re.search(r'[0-9]', b["text"])]
    for b in spec_row:
        c = clean_cifi_number(b["text"])
        if b["centerX"] < spec_cols[0]:
            data["specialResources"][0]["val"] = c
        elif b["centerX"] < spec_cols[1]:
            data["specialResources"][1]["val"] = c
        elif b["centerX"] < spec_cols[2]:
            data["specialResources"][2]["val"] = c
        else:
            data["specialResources"][3]["val"] = c

    # Fallback crop for Blueprints (col 1) and Dark Cores (col 2)
    if data["specialResources"][1]["val"] == "0":
        bp_crop = img[int(y_spec_b + 5):int(y_acad - 5), int(W * 0.40):int(W * 0.49)]
        val = recognize_crop(bp_crop)
        if val and re.search(r'\d+', val):
            data["specialResources"][1]["val"] = re.search(r'\d+', val).group(0)

    if data["specialResources"][2]["val"] == "0":
        dc_crop = img[int(y_spec_b + 5):int(y_acad - 5), int(W * 0.61):int(W * 0.70)]
        val = recognize_crop(dc_crop)
        if val and re.search(r'\d+', val):
            data["specialResources"][2]["val"] = re.search(r'\d+', val).group(0)

    # 3. Academy Materials (3x3 grid)
    acad_boxes = [b for b in boxes if b["centerY"] > y_acad_b and b["centerY"] < y_hunt and re.search(r'[0-9]', b["text"])]
    acad_sorted = sort_grid_boxes(acad_boxes, 25)
    for i, b in enumerate(acad_sorted[:9]):
        data["academyMaterials"][i]["val"] = clean_cifi_number(b["text"])

    # 4. Hunter Loot (3x3 grid)
    hunt_boxes = [b for b in boxes if b["centerY"] > y_hunt_b and b["centerY"] < y_end and re.search(r'[0-9]', b["text"])]
    hunt_sorted = sort_grid_boxes(hunt_boxes, 25)
    for i, b in enumerate(hunt_sorted[:9]):
        data["hunterLoot"][i]["val"] = clean_cifi_number(b["text"])

    return data


def parse_stats(img, boxes):
    H, W = img.shape[:2]
    data = {
        "player": "---",
        "level": 0,
        "ouroboros": {
            "traversals": "0",
            "allTimeOrbs": "0",
            "timeCurrent": "00:00:00",
            "orbsAccumCurrent": "0",
            "highestMeltdown": "0",
            "inscriptions": "0",
            "mechsOwned": "0",
            "gadgetLevels": "0"
        },
        "currentRunCones": {
            "cells": "0",
            "mps": "0",
            "shards": "0",
            "rps": ""
        },
        "allTimeStats": [
            {"label": "Cells Gained", "val": "0"},
            {"label": "Manual Gens", "val": "0"},
            {"label": "Tech Up Levels", "val": "0"},
            {"label": "MP Gained", "val": "0"},
            {"label": "Loop Mods", "val": "0"},
            {"label": "Loop Resets", "val": "0"},
            {"label": "Shards Gained", "val": "0"},
            {"label": "Milestone Levels", "val": "0"},
            {"label": "RP Gained", "val": "0"},
            {"label": "Research LVs", "val": "0"},
            {"label": "AP Gained", "val": "0"},
            {"label": "Gear Levels", "val": "0"},
            {"label": "Cons. Projects", "val": "0"},
            {"label": "Blueprints Gained", "val": "0"},
            {"label": "Fragments Gained", "val": "0"},
            {"label": "Relic Levels", "val": "0"},
            {"label": "Borge Progress", "val": "LV. 0 (S-0)"},
            {"label": "Ozzy Progress", "val": "LV. 0 (S-0)"},
            {"label": "Knox Progress", "val": "LV. 0 (S-0)"},
            {"label": "Slot 20", "val": "???"}
        ]
    }

    # Player Name
    prof_b = next((b for b in boxes if "PLAYER" in b["text"].upper() and "PROFILE" in b["text"].upper()), None)
    if prof_b:
        name_cands = [b for b in boxes if b["centerY"] < prof_b["top"] and b["centerY"] >= H * 0.02 and "PLAYER" not in b["text"].upper()]
        name_cands.sort(key=lambda b: b["bottom"], reverse=True)
        if name_cands:
            data["player"] = name_cands[0]["text"].strip()
    else:
        top_cands = [b for b in boxes if b["centerY"] <= H * 0.08 and "PLAYER" not in b["text"].upper()]
        if top_cands:
            data["player"] = top_cands[0]["text"].strip()

    # Player Level
    lv_b = next((b for b in boxes if b["centerY"] <= H * 0.15 and ("LV." in b["text"].upper() or "LEVEL" in b["text"].upper())), None)
    if lv_b:
        m = re.search(r'(\d+)', lv_b["text"])
        if m:
            data["level"] = int(m.group(1))

    def find_val_below(label_tokens, max_dy=85, max_dx=140):
        m_lbls = [b for b in boxes if any(tok in b["text"].upper() for tok in label_tokens)]
        if not m_lbls:
            return None
        m_lbls.sort(key=lambda b: b["bottom"], reverse=True)
        lbl = m_lbls[0]
        cands = [b for b in boxes if b["top"] >= lbl["bottom"] - 10 and b["top"] <= lbl["bottom"] + max_dy and abs(b["centerX"] - lbl["centerX"]) <= max_dx and re.search(r'\d', b["text"])]
        cands.sort(key=lambda b: b["top"])
        return cands[0]["text"] if cands else None

    # Ouroboros Stats
    t = find_val_below(["TRAVERSALS PERFORMED", "TRAVERSALS", "VERSALS"])
    if t: data["ouroboros"]["traversals"] = clean_cifi_number(t)

    time_b = next((b for b in boxes if re.search(r'\d{1,4}:\d{2}:\d{2}', b["text"])), None)
    if time_b:
        m = re.search(r'(\d{1,4}:\d{2}:\d{2})', time_b["text"])
        if m: data["ouroboros"]["timeCurrent"] = m.group(1)

    hm = find_val_below(["HIGHEST MELTDOWN", "MELTDOWN"])
    if hm: data["ouroboros"]["highestMeltdown"] = clean_cifi_number(hm)

    mo = find_val_below(["MECHS OWNED", "MECHS"])
    if mo:
        digits = re.sub(r'\D', '', mo)
        data["ouroboros"]["mechsOwned"] = digits[-3:] if len(digits) > 3 else (digits or "0")

    ato = find_val_below(["ALL TIME OURO ORBS", "OURO ORBS", "ALTIME OURO"])
    if ato: data["ouroboros"]["allTimeOrbs"] = clean_cifi_number(ato)

    oac = find_val_below(["ORBS ACCUMULATED", "ACCUMULATED IN", "CURRENT TR"])
    if oac: data["ouroboros"]["orbsAccumCurrent"] = clean_cifi_number(oac)

    ins = find_val_below(["INSCRIPTIONS", "INSCRYPTIONS"])
    if ins: data["ouroboros"]["inscriptions"] = clean_cifi_number(ins)

    gl = find_val_below(["GADGET LEVELS", "GADGET"])
    if gl: data["ouroboros"]["gadgetLevels"] = clean_cifi_number(gl)

    # Current Run Cones
    cur_run = next((b for b in boxes if "CURRENT RUN" in b["text"].upper()), None)
    all_time = next((b for b in boxes if "ALL TIME HIGHEST" in b["text"].upper()), None)
    if cur_run and all_time:
        cone_boxes = [b for b in boxes if b["centerY"] >= cur_run["bottom"] and b["centerY"] <= all_time["top"] and re.search(r'\d', b["text"]) and "LEVEL POINT" not in b["text"].upper()]
        cone_boxes.sort(key=lambda b: b["centerX"])
        c_vals = [re.sub(r'\D', '', b["text"]) for b in cone_boxes if re.sub(r'\D', '', b["text"])]
        if len(c_vals) > 0: data["currentRunCones"]["cells"] = c_vals[0]
        if len(c_vals) > 1: data["currentRunCones"]["mps"] = c_vals[1]
        if len(c_vals) > 2: data["currentRunCones"]["shards"] = c_vals[2]
        if len(c_vals) > 3: data["currentRunCones"]["rps"] = c_vals[3]

    # All Time Highest Stats (20 items)
    min_at_y = all_time["bottom"] if all_time else int(H * 0.44)
    val_max_dy = max(65, int(H * 0.048))
    val_max_dx = max(70, int(W * 0.10))

    for stat in data["allTimeStats"]:
        lbl_toks = [stat["label"].upper()]
        if stat["label"] == "Manual Gens": lbl_toks = ["MANUAL GENS", "MANUALGENS"]
        elif stat["label"] == "Loop Mods": lbl_toks = ["LOOP MODS", "LOOPMODS"]
        elif stat["label"] == "Loop Resets": lbl_toks = ["LOOP RESETS", "LOOPRESETS"]
        elif stat["label"] == "Cons. Projects": lbl_toks = ["CONS. PROJECTS", "CONS.PROJECTS", "CONS"]
        elif stat["label"] == "Blueprints Gained": lbl_toks = ["BLUEPRINTS GAINED", "BLUEPRINTS"]
        elif stat["label"] == "Fragments Gained": lbl_toks = ["FRAGMENTS GAINED", "FRAGMENTS"]
        elif stat["label"] == "Research LVs": lbl_toks = ["RESEARCH LVS", "RESEARCHLVS", "RESEARCH"]
        elif stat["label"] == "Shards Gained": lbl_toks = ["SHARDS GAINED", "SHARDS"]
        elif stat["label"] == "Slot 20": lbl_toks = ["???"]
        elif stat["label"] == "Borge Progress": lbl_toks = ["BORGE PROGRESS", "BORGE"]
        elif stat["label"] == "Ozzy Progress": lbl_toks = ["OZZY PROGRESS", "OZZYPROGRESS", "OZZY"]
        elif stat["label"] == "Knox Progress": lbl_toks = ["KNOX PROGRESS", "KNOXPROGRESS", "KNOX"]

        lbl_b = next((b for b in boxes if b["centerY"] > min_at_y and any(t in b["text"].upper() for t in lbl_toks)), None)
        if lbl_b:
            if "Progress" in stat["label"]:
                prog_b = next((b for b in boxes if b["centerY"] >= lbl_b["top"] and b["centerY"] <= lbl_b["bottom"] + val_max_dy and abs(b["centerX"] - lbl_b["centerX"]) <= val_max_dx * 1.5 and re.search(r'LV\.\s*\d+', b["text"].upper())), None)
                if prog_b:
                    m = re.search(r'(LV\.\s*\d+\s*\([^)]+\))', prog_b["text"].upper())
                    if m:
                        val = m.group(1).replace("(5-", "(S-")
                        val = re.sub(r'LV\.?\s*', 'LV. ', val)
                        val = re.sub(r'\s*\(', ' (', val)
                        stat["val"] = val
            elif stat["label"] == "Slot 20":
                stat["val"] = "???"
            else:
                val_cands = [b for b in boxes if b["centerY"] >= lbl_b["bottom"] and b["centerY"] <= lbl_b["bottom"] + val_max_dy and abs(b["centerX"] - lbl_b["centerX"]) <= val_max_dx and re.search(r'\d', b["text"])]
                val_cands.sort(key=lambda b: b["centerY"])
                if val_cands:
                    stat["val"] = clean_cifi_number(val_cands[0]["text"])

    return data


GEM_TREE_LAYOUTS = {
    "gem_exodus": {
        "maxQuality": 5,
        "upgrades": [
            {"name": "RP BONUS", "pos": (0.51, 0.45), "max": 999, "req": 3},
            {"name": "SHARD BONUS", "pos": (0.31, 0.51), "max": 999, "req": 2},
            {"name": "MP BONUS", "pos": (0.71, 0.51), "max": 999, "req": 4},
            {"name": "CELLS BONUS", "pos": (0.51, 0.56), "max": 999, "req": 1},
            {"name": "ORBS BONUS", "pos": (0.31, 0.61), "max": 999, "req": 5},
            {"name": "AP BONUS", "pos": (0.71, 0.61), "max": 999, "req": 4},
            {"name": "MATS BONUS", "pos": (0.51, 0.66), "max": 999, "req": 5}
        ]
    },
    "gem_temp": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "MP BONUS (ZAG RANK)", "pos": (0.51, 0.45), "max": 10, "req": 3},
            {"name": "MP BONUS (TICKS)", "pos": (0.31, 0.51), "max": 50, "req": 2},
            {"name": "LM MAX LEVELS (LOW TIER)", "pos": (0.71, 0.51), "max": 100, "req": 3},
            {"name": "MP BONUS (LMS)", "pos": (0.51, 0.56), "max": 50, "req": 1},
            {"name": "MP BONUS (ZAG CREW)", "pos": (0.71, 0.61), "max": 30, "req": 3},
            {"name": "MP BONUS (LOOP RESETS)", "pos": (0.51, 0.66), "max": 30, "req": 3}
        ]
    },
    "gem_inno": {
        "maxQuality": 3,
        "upgrades": [
            {"name": "MP BONUS", "pos": (0.51, 0.45), "max": 50, "req": 2},
            {"name": "CELLS BONUS", "pos": (0.31, 0.51), "max": 50, "req": 2},
            {"name": "SHARDS BONUS", "pos": (0.71, 0.51), "max": 50, "req": 2},
            {"name": "BLUEPRINTS", "pos": (0.11, 0.56), "max": 8, "req": 3},
            {"name": "STUDIES PER STUDY", "pos": (0.51, 0.56), "max": 50, "req": 1},
            {"name": "INNOVATION CORES", "pos": (0.91, 0.56), "max": 4, "req": 3},
            {"name": "MATS BONUS", "pos": (0.31, 0.61), "max": 50, "req": 3},
            {"name": "RP BONUS", "pos": (0.71, 0.61), "max": 50, "req": 2},
            {"name": "AP BONUS", "pos": (0.51, 0.66), "max": 50, "req": 2}
        ]
    },
    "gem_creat": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "KNOX STAT BONUS", "pos": (0.10, 0.45), "max": 50, "req": 4},
            {"name": "SOFTWARE BONUS", "pos": (0.51, 0.45), "max": 20, "req": 2},
            {"name": "HARDWARE BONUS", "pos": (0.31, 0.51), "max": 20, "req": 2},
            {"name": "CELLS BONUS", "pos": (0.71, 0.51), "max": 15, "req": 3},
            {"name": "TRINKET TIER BONUS", "pos": (0.10, 0.56), "max": 80, "req": 4},
            {"name": "MECHS BONUS CAP", "pos": (0.51, 0.56), "max": 999, "req": 1},
            {"name": "BORGE STAT BONUS", "pos": (0.91, 0.56), "max": 50, "req": 4},
            {"name": "RP BONUS", "pos": (0.31, 0.61), "max": 15, "req": 3},
            {"name": "MP BONUS", "pos": (0.71, 0.61), "max": 15, "req": 3},
            {"name": "SHARDS BONUS", "pos": (0.51, 0.66), "max": 15, "req": 3},
            {"name": "OZZY STAT BONUS", "pos": (0.91, 0.66), "max": 50, "req": 4}
        ]
    },
    "gem_pwr": {
        "maxQuality": 3,
        "upgrades": [
            {"name": "ZAGREUS BONUS", "pos": (0.51, 0.45), "max": 50, "req": 1},
            {"name": "AUXESIA BONUS", "pos": (0.31, 0.51), "max": 50, "req": 1},
            {"name": "HEPHAESTUS BONUS", "pos": (0.71, 0.51), "max": 50, "req": 1},
            {"name": "BLUEPRINTS", "pos": (0.10, 0.56), "max": 50, "req": 1},
            {"name": "CRADLE BONUS", "pos": (0.51, 0.56), "max": 50, "req": 1},
            {"name": "INNOVATION CORES", "pos": (0.91, 0.56), "max": 50, "req": 1},
            {"name": "ZEUS BONUS", "pos": (0.31, 0.61), "max": 50, "req": 1},
            {"name": "DEMETER BONUS", "pos": (0.71, 0.61), "max": 50, "req": 1},
            {"name": "KOIOS BONUS", "pos": (0.51, 0.66), "max": 50, "req": 1}
        ]
    },
    "gem_evo": {
        "maxQuality": 1,
        "upgrades": [
            {"name": "MK9 STABILITY", "pos": (0.31, 0.51), "max": 36, "req": 1},
            {"name": "ALL GENS OUTPUT", "pos": (0.51, 0.56), "max": 50, "req": 1},
            {"name": "MK9 RESONANCE", "pos": (0.31, 0.61), "max": 8, "req": 1},
            {"name": "BONUS LP", "pos": (0.51, 0.66), "max": 250, "req": 1}
        ]
    },
    "gem_attr": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "CATCH-UP (BORGE/OZZY)", "pos": (0.51, 0.45), "max": 5, "req": 3},
            {"name": "LOOT (OZZY)", "pos": (0.31, 0.51), "max": 50, "req": 2},
            {"name": "LOOT (KNOX)", "pos": (0.71, 0.51), "max": 50, "req": 4},
            {"name": "LOOT (BORGE)", "pos": (0.51, 0.56), "max": 50, "req": 1},
            {"name": "CATCH-UP (KNOX)", "pos": (0.71, 0.61), "max": 5, "req": 4},
            {"name": "SHIP EVO BONUS", "pos": (0.51, 0.66), "max": 100, "req": 4}
        ]
    }
}

def parse_gem_tree(page_id, img, boxes):
    H, W = img.shape[:2]
    cfg = GEM_TREE_LAYOUTS.get(page_id, {"maxQuality": 1, "upgrades": []})
    max_q = cfg["maxQuality"]

    data = {
        "currentLevel": 0,
        "maxLevel": max_q,
        "qualityLevel": f"0 / {max_q}",
        "maxReached": False,
        "upgradeCost": "---",
        "hud": {"orbs": "0", "level": 0, "diamonds": 0, "ticks": "---"},
        "upgrades": [
            {
                "name": u["name"],
                "reqLevel": u.get("req", 1),
                "maxRank": u.get("max", 999),
                "level": 0,
                "unlocked": False
            }
            for u in cfg["upgrades"]
        ]
    }

    # Quality Level e.g. "GEM QUALITY LEVEL 5 / 5"
    ql_b = next((b for b in boxes if "QUALITY" in b["text"].upper() and "LEVEL" in b["text"].upper()), None)
    if ql_b:
        m = re.search(r'(\d+)\s*[/]\s*(\d+)', ql_b["text"])
        if m:
            data["currentLevel"] = int(m.group(1))
            data["qualityLevel"] = f"{m.group(1)} / {m.group(2)}"
        elif any("MAX" in b["text"].upper() and "QUALITY" in b["text"].upper() for b in boxes):
            data["currentLevel"] = max_q
            data["qualityLevel"] = f"{max_q} / {max_q}"
    elif any("CONGRA" in b["text"].upper() or ("MAX" in b["text"].upper() and "QUALITY" in b["text"].upper()) for b in boxes):
        data["currentLevel"] = max_q
        data["qualityLevel"] = f"{max_q} / {max_q}"

    data["maxReached"] = data["currentLevel"] >= max_q and data["currentLevel"] > 0

    # Top HUD
    for b in boxes:
        if b["centerY"] <= H * 0.12:
            if b["centerX"] < W * 0.35 and re.search(r'[0-9.]+[a-zA-Z]+', b["text"]):
                data["hud"]["orbs"] = clean_cifi_number(b["text"])
            elif b["centerX"] > W * 0.70 and re.search(r'\d+', b["text"]):
                data["hud"]["diamonds"] = int(re.sub(r'\D', '', b["text"]) or "0")
            elif W * 0.35 <= b["centerX"] <= W * 0.65 and re.search(r'^\d{3,5}$', b["text"]):
                data["hud"]["level"] = int(b["text"])

    # Gem Upgrade Levels via Geometric Layout
    all_level_boxes = [b for b in boxes if b["centerY"] >= H * 0.38 and b["centerY"] <= H * 0.72 and re.search(r'\d+\s*[-/–—|\\]\s*\d+', b["text"]) and "QUALITY" not in b["text"].upper()]

    for i, u_def in enumerate(cfg["upgrades"]):
        target_x = u_def["pos"][0] * W
        target_y = u_def["pos"][1] * H
        max_rank = u_def["max"]

        # 1. Match detected level box closest to target node
        closest_b = None
        min_dist = float('inf')
        for b in all_level_boxes:
            dist = np.hypot(b["centerX"] - target_x, b["centerY"] - target_y)
            if dist < min_dist:
                min_dist = dist
                closest_b = b

        if closest_b and min_dist <= H * 0.055:
            lvl = clean_rank_level(closest_b["text"], max_rank)
            if lvl > 0:
                data["upgrades"][i]["level"] = lvl
                continue

        # 2. Local node crop OCR if undetected in global image pass
        crop_y1 = max(0, int(target_y - H * 0.035))
        crop_y2 = min(H, int(target_y + H * 0.040))
        crop_x1 = max(0, int(target_x - W * 0.09))
        crop_x2 = min(W, int(target_x + W * 0.09))
        node_crop = img[crop_y1:crop_y2, crop_x1:crop_x2]

        node_res, _ = ocr(node_crop)
        if node_res:
            for b_item in node_res:
                lvl = clean_rank_level(b_item[1], max_rank)
                if lvl > 0:
                    data["upgrades"][i]["level"] = lvl
                    break

    # Calculate unlock state based on current gem quality level
    for u in data["upgrades"]:
        u["unlocked"] = (data["currentLevel"] >= u["reqLevel"] and data["currentLevel"] > 0)

    return data


# -------------------------------------------------------------
# HIGH-PERFORMANCE HTTP VISION SERVER & CONTROLLER
# -------------------------------------------------------------

def analyze_image_bytes(img_bytes):
    nparr = np.frombuffer(img_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if img is None:
        return {"error": "Failed to decode image"}

    H, W = img.shape[:2]
    boxes = get_ocr_boxes(img)
    page_id = classify_page(boxes, W, H)

    if page_id == "profile_inventory":
        data = parse_inventory(img, boxes)
    elif page_id == "profile_stats":
        data = parse_stats(img, boxes)
    elif page_id.startswith("gem_"):
        data = parse_gem_tree(page_id, img, boxes)
    else:
        data = {"error": f"Unknown page type: {page_id}"}

    return {
        "pageId": page_id,
        "width": W,
        "height": H,
        "boxCount": len(boxes),
        "data": data
    }


# -------------------------------------------------------------
# EMBEDDED CIFI WEB UI (100% SELF-CONTAINED STANDALONE SERVICE)
# -------------------------------------------------------------
EMBEDDED_HTML_B64 = b"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"
EMBEDDED_HTML = zlib.decompress(base64.b64decode(EMBEDDED_HTML_B64)).decode("utf-8")


class VisionRequestHandler(BaseHTTPRequestHandler):
    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_GET(self):
        if self.path == '/' or self.path.startswith('/index.html'):
            html_path = os.path.join(os.path.dirname(__file__), "cifi_profile_analyzer.html")
            if os.path.exists(html_path):
                with open(html_path, "rb") as f:
                    content = f.read()
            else:
                content = EMBEDDED_HTML.encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(content)
            return
        elif self.path == '/api/health':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "engine": "PP-OCRv4-ONNX", "version": "2.0.0"}).encode('utf-8'))
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        if self.path == '/api/analyze':
            content_length = int(self.headers.get('Content-Length', 0))
            post_data = self.rfile.read(content_length)

            try:
                try:
                    payload = json.loads(post_data.decode('utf-8'))
                    b64_str = payload.get("image", "")
                    if "," in b64_str:
                        b64_str = b64_str.split(",")[1]
                    img_bytes = base64.b64decode(b64_str)
                except Exception:
                    img_bytes = post_data

                result = analyze_image_bytes(img_bytes)

                self.send_response(200)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps(result).encode('utf-8'))
            except Exception as e:
                self.send_response(500)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        pass


def start_server(port=8765):
    server = HTTPServer(('0.0.0.0', port), VisionRequestHandler)
    print(f"CIFI AI Vision Server running at http://localhost:{port}")
    server.serve_forever()


if __name__ == "__main__":
    start_server()
