"""Quantize an FP32 Pocket TTS model pack into weight-only low-bit variants.

Usage: uv run --python 3.12 --with onnxruntime==1.20.1 --with onnx==1.17.0 \
         tools/bench/quantize.py <fp32 models dir> <out dir> <mode>
Modes:
  int8dyn   upstream method: dynamic INT8 on MatMul (activations quantized at runtime)
  q8w       weight-only 8-bit MatMulNBits (block 32), FP32 activations
  q4w       weight-only 4-bit MatMulNBits (block 32), FP32 activations
  q4w-a8    4-bit weights, int8 compute (accuracy_level=4, uses ARM dotprod/i8mm)
Writes *_int8.onnx names for the three runtime-quantizable graphs so the C++
runtime picks them up with --precision int8; other graphs are copied as-is.
"""
import shutil, sys
from pathlib import Path
import onnx

src, dst, mode = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
dst.mkdir(parents=True, exist_ok=True)
for f in src.iterdir():
    shutil.copy2(f, dst / f.name)

def nbits(inp: Path, out: Path, bits: int, acc: int):
    from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer, DefaultWeightOnlyQuantConfig
    model = onnx.load(str(inp))
    try:
        cfg = DefaultWeightOnlyQuantConfig(block_size=32, is_symmetric=True, accuracy_level=acc)
        q = MatMul4BitsQuantizer(model, algo_config=cfg)
    except TypeError:
        q = MatMul4BitsQuantizer(model, block_size=32, is_symmetric=True, accuracy_level=acc)
    if bits != 4:
        raise SystemExit("8-bit MatMulNBits needs onnxruntime>=1.21 quantizer")
    q.process()
    q.model.save_model_to_file(str(out), use_external_data_format=False)

for name in ("flow_lm_main", "flow_lm_flow", "mimi_decoder"):
    inp, out = src / f"{name}.onnx", dst / f"{name}_int8.onnx"
    if mode == "int8dyn":
        from onnxruntime.quantization import quantize_dynamic, QuantType
        quantize_dynamic(str(inp), str(out), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
    elif mode == "q4w":
        nbits(inp, out, 4, 0)
    elif mode == "q4w-a8":
        nbits(inp, out, 4, 4)
    else:
        raise SystemExit(f"unknown mode {mode}")
    print(f"{name}: {inp.stat().st_size/1e6:.1f} MB -> {out.stat().st_size/1e6:.1f} MB")
