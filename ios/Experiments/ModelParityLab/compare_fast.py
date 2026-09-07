"""Compare the pinned Fast graph through ORT CPU and Core ML on this Mac.
Generated tensors only; no camera, images, network, downloads or app changes.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import platform
import tempfile
import time

import numpy as np
import onnxruntime as ort

EXPECTED = '9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('model', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if hashlib.sha256(args.model.read_bytes()).hexdigest() != EXPECTED:
        raise SystemExit('Model checksum mismatch; no inference performed.')
    if 'CoreMLExecutionProvider' not in ort.get_available_providers():
        raise SystemExit('This local ORT build has no Core ML provider.')
    cache = Path(tempfile.mkdtemp(prefix='nobonk-coreml-parity-'))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    cpu = ort.InferenceSession(str(args.model), sess_options=options, providers=['CPUExecutionProvider'])
    assert cpu.get_inputs()[0].shape == [1, 3, 416, 416]
    assert cpu.get_outputs()[0].shape == [1, 84, 3549]
    yy, xx = np.mgrid[0:416, 0:416]
    cases = {
        'gray': np.full((1, 3, 416, 416), 114/255, np.float32),
        'channel_gradient': np.stack([xx/415, yy/415, (xx+yy)/830]).astype(np.float32)[None],
        'seeded_noise': np.random.default_rng(230907).random((1, 3, 416, 416), dtype=np.float32),
    }
    reference = {name: cpu.run(None, {'images': data})[0] for name, data in cases.items()}
    report = {'scope': 'Mac synthetic-tensor numerical comparison; not iPhone throughput or obstacle accuracy',
              'model_sha256': EXPECTED, 'onnxruntime': ort.__version__,
              'platform': platform.platform(), 'available_providers': ort.get_available_providers(),
              'cache_directory': str(cache), 'cases': []}
    options.enable_profiling = True
    options.profile_file_prefix = str(cache / 'execution')
    started = time.monotonic()
    session = ort.InferenceSession(str(args.model), sess_options=options, providers=[
        ('CoreMLExecutionProvider', {'ModelFormat': 'MLProgram', 'MLComputeUnits': 'ALL',
           'RequireStaticInputShapes': '1', 'ModelCacheDirectory': str(cache / 'compiled')}),
        'CPUExecutionProvider'])
    session.disable_fallback()
    report['session_creation_seconds'] = time.monotonic() - started
    report['session_providers'] = session.get_providers()
    for name, data in cases.items():
        started = time.monotonic()
        actual = session.run(None, {'images': data})[0]
        elapsed = time.monotonic() - started
        if actual.shape != reference[name].shape or not np.isfinite(actual).all():
            raise RuntimeError('Output contract or finite-values check failed')
        delta = np.abs(actual - reference[name])
        report['cases'].append({'name': name, 'seconds_under_current_desktop_load': elapsed,
            'max_box_delta_model_pixels': float(delta[:, :4].max()),
            'max_class_score_delta': float(delta[:, 4:].max()),
            'mean_class_score_delta': float(delta[:, 4:].mean()),
            'within_exploratory_limits': bool(delta[:, :4].max() <= 1.0 and delta[:, 4:].max() <= .005)})
    profile = Path(session.end_profiling())
    counts = Counter()
    for event in json.loads(profile.read_text()):
        provider = event.get('args', {}).get('provider')
        if event.get('cat') == 'Node' and provider:
            counts[provider] += 1
    report['profile_node_events_by_provider'] = dict(counts)
    report['coreml_execution_observed'] = counts['CoreMLExecutionProvider'] > 0
    report['caveat'] = 'CoreML EP events do not identify physical CPU/GPU/ANE placement. Synthetic inputs cannot validate object accuracy, NMS parity or camera preprocessing.'
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
