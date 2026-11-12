import re, urllib.request, html as H
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36", "Accept-Language": "en"}
def get(u):
    return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=25).read().decode("utf-8","replace")
def tx(h): return re.sub(r"\s+"," ",re.sub(r"<[^>]+>"," ",h))
try:
    h = get("https://www.globalpetrolprices.com/United-Arab-Emirates/gasoline_prices/")
    t = tx(h)
    for k in ["Octane","octane","current","The price of","per liter"]:
        for m in list(re.finditer(k,t))[:3]: print("GPP:", t[max(0,m.start()-200):m.start()+200])
except Exception as e: print("GPP fail", e)
try:
    print("EMARAT:", get("https://www.emarat.ae/en/fuel-prices")[:3800])
except Exception as e: print("emarat fail", e)
for u in ["https://www.adnocdistribution.ae/en/fuel-price","https://www.adnocdistribution.ae/en/","https://www.adnocdistribution.ae/","https://www.emarat.ae/fuel-prices/","https://www.emarat.ae/en/","https://www.enoc.com/"]:
    try:
        h=get(u); t=tx(h); print("==",u,len(h))
        for m in list(re.finditer(r"(?i)fuel[- ]?price",h))[:5]: print("   ", h[max(0,m.start()-120):m.start()+120].replace("\n"," "))
    except Exception as e: print("==",u,"FAIL",e)
try:
    r = get("https://news.google.com/rss/search?q=UAE+petrol+prices+November+2026&hl=en-AE&gl=AE&ceid=AE:en")
    for t in re.findall(r"<title>(.*?)</title>", r)[:25]: print("GN:", H.unescape(t))
    for d in re.findall(r"<description>(.*?)</description>", r)[:3]: print("GND:", H.unescape(d)[:400])
except Exception as e: print("gn fail", e)
