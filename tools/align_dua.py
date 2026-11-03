"""Word timings for the bundled Dua al-Safar recording (run in GitHub Actions)."""
import json
from faster_whisper import WhisperModel

AUDIO = "app/src/main/res/raw/dua_al_safar.m4a"
PROMPT = ("الله أكبر الله أكبر الله أكبر سبحان الذي سخر لنا هذا وما كنا له مقرنين وإنا إلى ربنا لمنقلبون "
          "اللهم إنا نسألك في سفرنا هذا البر والتقوى ومن العمل ما ترضى اللهم هون علينا سفرنا هذا واطو عنا بعده "
          "اللهم أنت الصاحب في السفر والخليفة في الأهل اللهم إني أعوذ بك من وعثاء السفر وكآبة المنظر وسوء المنقلب في المال والأهل")
out = {}
for name in ["large-v3", "medium"]:
    model = WhisperModel(name, device="cpu", compute_type="int8")
    segs, info = model.transcribe(AUDIO, language="ar", word_timestamps=True, initial_prompt=PROMPT,
                                  beam_size=5, vad_filter=False, condition_on_previous_text=False)
    words = []
    for s in segs:
        for w in s.words:
            words.append({"w": w.word.strip(), "s": round(w.start, 2), "e": round(w.end, 2), "p": round(w.probability, 2)})
            print(name, words[-1])
    out[name] = words
json.dump(out, open("tools/dua_words.json", "w"), ensure_ascii=False, indent=1)
