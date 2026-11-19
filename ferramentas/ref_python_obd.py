# Gera valores de referência decodificados pela python-OBD para comparar com o núcleo Kotlin.
import json, random, sys
import obd
from obd import commands
random.seed(42)
class Msg:
    def __init__(self, data): self.data = bytearray(data)
out = []
for cmd in commands[1]:
    if cmd is None: continue
    pid = cmd.pid
    nbytes = cmd.bytes - 2
    if nbytes <= 0 or pid % 0x20 == 0: continue
    for _ in range(25):
        data = [random.randrange(256) for _ in range(nbytes)]
        try:
            v = cmd.decode([Msg([0x41, pid] + data)])
        except Exception as e:
            continue
        mag = getattr(v, "magnitude", None)
        if isinstance(mag, (int, float)):
            out.append({"pid": pid, "bytes": data, "valor": float(mag), "unidade": str(getattr(v, "units", "")), "nome": cmd.name})
json.dump(out, open(sys.argv[1], "w"), indent=0)
print(len(out), "vetores;", len({o["pid"] for o in out}), "PIDs")
