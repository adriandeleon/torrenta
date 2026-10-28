# How the protocol<N>.pickle and kw.pickle fixtures were written (Python 3.13): one value, every
# protocol. Run from this folder with `python3 -I make.py > out.txt`, then decode each line's base64.
import pickle, base64, pickletools, io
class Plain:
    def __init__(self): self.a = 1; self.b = "x"
class Slotted:
    __slots__ = ("s",)
    def __init__(self): self.s = 5
    def __getstate__(self): return (None, {"s": self.s})
class Kw:
    def __init__(self, n): self.n = n
    def __getnewargs_ex__(self): return ((7,), {"k": 1})
value = {
  "small": 5, "medium": 300, "int": -70000, "big": 1 << 70, "neg": -1, "zero": 0, "float": 2.5,
  "bytes": b"ab", "long_bytes": b"z" * 300, "str": "héllo", "long_str": "y" * 300,
  "t1": (1,), "t2": (1, 2), "t3": (1, 2, 3), "t4": (1, 2, 3, 4), "empty_t": (),
  "set": {9}, "fset": frozenset({8}), "none": None, "yes": True, "no": False,
  "list": [1, [2, 3]], "dict": {"k": {"n": None}}, "plain": Plain(), "slotted": Slotted(),
  "bytearray": bytearray(b"qq"),
}
for proto in range(0, 6):
    data = pickle.dumps(value, protocol=proto)
    ops = sorted({op.name for op, _, _ in pickletools.genops(data)})
    print(proto, base64.b64encode(data).decode())
    print("#", ",".join(ops))
kw = pickle.dumps(Kw(7), protocol=4)
print("kw", base64.b64encode(kw).decode())
print("#", ",".join(sorted({op.name for op, _, _ in pickletools.genops(kw)})))
