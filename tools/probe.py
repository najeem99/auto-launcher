import re, urllib.request
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36", "Accept-Language": "en"}
def get(u): return urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=25).read().decode("utf-8","replace")
def tx(h): return re.sub(r"\s+"," ",re.sub(r"<[^>]+>"," ",re.sub(r"(?s)<script.*?</script>|<style.*?</style>","",h)))
for u in ["https://www.adnocdistribution.ae/en/",
 "https://www.khaleejtimes.com/business/energy/uae-petrol-diesel-prices-november-2026-announced",
 "https://www.khaleejtimes.com/energy/uae-petrol-diesel-prices-november-2026-announced",
 "https://www.khaleejtimes.com/business/energy/uae-petrol-diesel-prices-october-2026-announced",
 "https://www.wam.ae/a/17i2n96",
 "https://www.emirates247.com/uae/uae-fuel-prices-for-november-2026-announced/7306",
 "https://www.globalpetrolprices.com/United-Arab-Emirates/diesel_prices/"]:
    try:
        h=get(u); t=tx(h); print("==",u,len(h))
        for m in list(re.finditer(r"(?i)super\s*98|current diesel price",t))[:3]: print("   ", t[max(0,m.start()-100):m.start()+260])
    except Exception as e: print("==",u,"FAIL",e)
