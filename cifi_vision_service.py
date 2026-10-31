import os
import re
import cv2
import json
import base64
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
            {"name": "RP BONUS", "pos": (0.51, 0.45), "max": 999},
            {"name": "SHARD BONUS", "pos": (0.31, 0.51), "max": 999},
            {"name": "MP BONUS", "pos": (0.71, 0.51), "max": 999},
            {"name": "CELLS BONUS", "pos": (0.51, 0.56), "max": 999},
            {"name": "ORBS BONUS", "pos": (0.31, 0.61), "max": 999},
            {"name": "AP BONUS", "pos": (0.71, 0.61), "max": 999},
            {"name": "MATS BONUS", "pos": (0.51, 0.66), "max": 999}
        ]
    },
    "gem_temp": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "MP BONUS (ZAG RANK)", "pos": (0.51, 0.45), "max": 10},
            {"name": "MP BONUS (TICKS)", "pos": (0.31, 0.51), "max": 50},
            {"name": "LM MAX LEVELS (LOW TIER)", "pos": (0.71, 0.51), "max": 100},
            {"name": "MP BONUS (LMS)", "pos": (0.51, 0.56), "max": 50},
            {"name": "MP BONUS (ZAG CREW)", "pos": (0.71, 0.61), "max": 30},
            {"name": "MP BONUS (LOOP RESETS)", "pos": (0.51, 0.66), "max": 30}
        ]
    },
    "gem_inno": {
        "maxQuality": 3,
        "upgrades": [
            {"name": "MP BONUS", "pos": (0.51, 0.45), "max": 50},
            {"name": "CELLS BONUS", "pos": (0.31, 0.51), "max": 50},
            {"name": "SHARDS BONUS", "pos": (0.71, 0.51), "max": 50},
            {"name": "BLUEPRINTS", "pos": (0.11, 0.56), "max": 8},
            {"name": "STUDIES PER STUDY", "pos": (0.51, 0.56), "max": 50},
            {"name": "INNOVATION CORES", "pos": (0.91, 0.56), "max": 4},
            {"name": "MATS BONUS", "pos": (0.31, 0.61), "max": 50},
            {"name": "RP BONUS", "pos": (0.71, 0.61), "max": 50},
            {"name": "AP BONUS", "pos": (0.51, 0.66), "max": 50}
        ]
    },
    "gem_creat": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "KNOX STAT BONUS", "pos": (0.10, 0.45), "max": 50},
            {"name": "SOFTWARE BONUS", "pos": (0.51, 0.45), "max": 20},
            {"name": "HARDWARE BONUS", "pos": (0.31, 0.51), "max": 20},
            {"name": "CELLS BONUS", "pos": (0.71, 0.51), "max": 15},
            {"name": "TRINKET TIER BONUS", "pos": (0.10, 0.56), "max": 80},
            {"name": "MECHS BONUS CAP", "pos": (0.51, 0.56), "max": 999},
            {"name": "BORGE STAT BONUS", "pos": (0.91, 0.56), "max": 50},
            {"name": "RP BONUS", "pos": (0.31, 0.61), "max": 15},
            {"name": "MP BONUS", "pos": (0.71, 0.61), "max": 15},
            {"name": "SHARDS BONUS", "pos": (0.51, 0.66), "max": 15},
            {"name": "OZZY STAT BONUS", "pos": (0.91, 0.66), "max": 50}
        ]
    },
    "gem_pwr": {
        "maxQuality": 3,
        "upgrades": [
            {"name": "ZAGREUS BONUS", "pos": (0.51, 0.45), "max": 50},
            {"name": "AUXESIA BONUS", "pos": (0.31, 0.51), "max": 50},
            {"name": "HEPHAESTUS BONUS", "pos": (0.71, 0.51), "max": 50},
            {"name": "BLUEPRINTS", "pos": (0.10, 0.56), "max": 50},
            {"name": "CRADLE BONUS", "pos": (0.51, 0.56), "max": 50},
            {"name": "INNOVATION CORES", "pos": (0.91, 0.56), "max": 50},
            {"name": "ZEUS BONUS", "pos": (0.31, 0.61), "max": 50},
            {"name": "DEMETER BONUS", "pos": (0.71, 0.61), "max": 50},
            {"name": "KOIOS BONUS", "pos": (0.51, 0.66), "max": 50}
        ]
    },
    "gem_evo": {
        "maxQuality": 1,
        "upgrades": [
            {"name": "MK9 STABILITY", "pos": (0.31, 0.51), "max": 36},
            {"name": "ALL GENS OUTPUT", "pos": (0.51, 0.56), "max": 50},
            {"name": "MK9 RESONANCE", "pos": (0.31, 0.61), "max": 8},
            {"name": "BONUS LP", "pos": (0.51, 0.66), "max": 250}
        ]
    },
    "gem_attr": {
        "maxQuality": 4,
        "upgrades": [
            {"name": "CATCH-UP (BORGE/OZZY)", "pos": (0.51, 0.45), "max": 5},
            {"name": "LOOT (OZZY)", "pos": (0.31, 0.51), "max": 50},
            {"name": "LOOT (KNOX)", "pos": (0.71, 0.51), "max": 50},
            {"name": "LOOT (BORGE)", "pos": (0.51, 0.56), "max": 50},
            {"name": "CATCH-UP (KNOX)", "pos": (0.71, 0.61), "max": 5},
            {"name": "SHIP EVO BONUS", "pos": (0.51, 0.66), "max": 100}
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
        "upgrades": [{"name": u["name"], "level": 0} for u in cfg["upgrades"]]
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
