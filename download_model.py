import hashlib, urllib.request
from pathlib import Path

url = (
    "https://huggingface.co/Xenova/segformer-b0-finetuned-ade-512-512/resolve/main/onnx/model.onnx"
)
with urllib.request.urlopen(url, timeout=120) as r:
    data = r.read()
assert (
    hashlib.sha256(data).hexdigest()
    == "3e5c18a4be395f16646438d54c42377ddc202edfa33d5eced0c9506de75c44c2"
)
p = Path(__file__).parent / "assets" / "floor.onnx"
p.parent.mkdir(exist_ok=True)
p.write_bytes(data)
print(p)
