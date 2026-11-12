#!/usr/bin/env python3
"""Fetches the UAE monthly fuel prices and keeps fuel-prices.json up to date.

The UAE Fuel Price Committee announces next month's prices on the last day of each
month. The app reads fuel-prices.json from this repo, so it never has to scrape a
news site itself.

Sources, in order:
  1. Khaleej Times' monthly article (all four grades, exact).
  2. GlobalPetrolPrices.com (Special 95 and diesel). Super 98 and E-Plus 91 are then
     worked out from 95 with the committee's fixed gaps (+0.12 and -0.07 AED), which
     have held for years.
Usage: fuel_prices.py [path/to/fuel-prices.json] [--dry-run]
"""
import datetime as dt
import json
import re
import sys
import urllib.request

UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
      "Accept-Language": "en"}
GAP_98 = 0.12
GAP_91 = -0.07


def get(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")


def text(html):
    html = re.sub(r"(?is)<script.*?</script>|<style.*?</style>", " ", html)
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html))


def sane(v):
    return v is not None and 1.0 < v < 9.0


def price_after(t, label):
    # "Super 98 petrol will cost Dh4.16 per litre": the first Dh/AED figure after the label.
    m = re.search(label + r"[^.]{0,80}?(?:Dh|AED)\s?(\d+\.\d{2})", t, re.I)
    return float(m.group(1)) if m else None


def from_khaleej_times(year, month):
    name = dt.date(year, month, 1).strftime("%B")
    low = name.lower()
    slugs = [f"uae-petrol-diesel-prices-{low}-{year}-announced",
             f"uae-petrol-and-diesel-prices-{low}-{year}-announced",
             f"uae-fuel-prices-{low}-{year}-announced",
             f"petrol-prices-{low}-{year}"]
    for slug in slugs:
        for section in ("business/energy", "energy", "uae"):
            url = f"https://www.khaleejtimes.com/{section}/{slug}"
            try:
                t = text(get(url))
            except Exception:
                continue
            # The article must announce this month's rates, not forecast them.
            if not re.search(rf"(?i)apply from {name} 1|from {name} 1, {year}|{name} 1, {year}", t):
                print("  skip (no 'from", name, "1'):", url)
                continue
            p = {
                "super98": price_after(t, r"Super 98"),
                "special95": price_after(t, r"Special 95"),
                "eplus91": price_after(t, r"E-Plus 91"),
                "diesel": price_after(t, r"Diesel"),
            }
            if all(sane(v) for v in p.values()):
                p["source"] = "Khaleej Times"
                p["url"] = url
                return p
            print("  could not read all four prices:", url, p)
    return None


def from_global_petrol_prices(year, month):
    def current(kind):
        t = text(get(f"https://www.globalpetrolprices.com/United-Arab-Emirates/{kind}_prices/"))
        m = re.search(r"The current [\w ]{0,30}? in the United Arab Emirates is AED (\d+\.\d{2}) per liter.{0,80}?(\d\d-\w{3}-\d{4})", t)
        if not m:
            return None, None
        return float(m.group(1)), dt.datetime.strptime(m.group(2), "%d-%b-%Y").date()

    try:
        p95, at95 = current("gasoline")
        diesel, _ = current("diesel")
    except Exception as e:
        print("  GlobalPetrolPrices failed:", e)
        return None
    if not sane(p95) or not sane(diesel):
        return None
    # Only trust it once the site has caught up with the month we want.
    if at95 < dt.date(year, month, 1):
        return None
    return {
        "super98": round(p95 + GAP_98, 2),
        "special95": p95,
        "eplus91": round(p95 + GAP_91, 2),
        "diesel": diesel,
        "source": "GlobalPetrolPrices (98 and 91 worked out from 95)",
        "url": "https://www.globalpetrolprices.com/United-Arab-Emirates/gasoline_prices/",
    }


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    path = args[0] if args else "fuel-prices.json"
    try:
        with open(path) as f:
            data = json.load(f)
    except FileNotFoundError:
        data = {"months": {}}

    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=4))).date()  # UAE time
    wanted = [(today.year, today.month)]
    if today.day >= 28:  # next month's prices come out on the last day
        nxt = today.replace(day=1) + dt.timedelta(days=32)
        wanted.append((nxt.year, nxt.month))

    changed = False
    for y, m in wanted:
        key = f"{y:04d}-{m:02d}"
        have = data["months"].get(key)
        if have and have.get("source") == "Khaleej Times":
            print(key, "already exact:", have)
            continue
        p = from_khaleej_times(y, m)
        if not p and not have:
            p = from_global_petrol_prices(y, m)
        print(key, "->", p)
        if p and p != have:
            data["months"][key] = p
            changed = True

    if changed:
        data["months"] = dict(sorted(data["months"].items()))
        data["updated"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        if not dry:
            with open(path, "w") as f:
                json.dump(data, f, indent=2)
                f.write("\n")
    print("changed" if changed else "no change")


if __name__ == "__main__":
    main()
