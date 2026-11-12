import re, urllib.request, sys
URLS = [
 "https://www.adnocdistribution.ae/en/fuel-prices",
 "https://www.adnocdistribution.ae/en/fuel-prices/",
 "https://www.enoc.com/en/fuel-prices",
 "https://www.enoc.com/en/motorists/fuel-prices",
 "https://www.emarat.ae/en/fuel-prices",
 "https://www.globalpetrolprices.com/United-Arab-Emirates/gasoline_prices/",
 "https://www.globalpetrolprices.com/United-Arab-Emirates/diesel_prices/",
 "https://www.khaleejtimes.com/uae-fuel-prices",
 "https://gulfnews.com/uae/fuel-prices",
 "https://www.dubicars.com/fuel-prices",
 "https://news.google.com/rss/search?q=UAE+petrol+prices+November+2026&hl=en-AE&gl=AE&ceid=AE:en",
]
for u in URLS:
    try:
        req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36", "Accept-Language": "en"})
        html = urllib.request.urlopen(req, timeout=25).read().decode("utf-8", "replace")
        print("==", u, "OK", len(html))
        hits = [m.start() for m in re.finditer(r"98", html)]
        txt = re.sub(r"\s+", " ", html)
        for m in list(re.finditer(r"(?i)super\s*98", txt))[:3]:
            print("   ...", txt[max(0,m.start()-150):m.start()+250].replace("\n"," "))
        for m in list(re.finditer(r"\b[2-5]\.\d\d\b", txt))[:6]:
            print("   num:", txt[max(0,m.start()-80):m.start()+20])
    except Exception as e:
        print("==", u, "FAIL", repr(e)[:200])
