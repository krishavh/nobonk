#!/usr/bin/env python3
"""Generate small numerical fixtures from the exact existing graph; no downloads."""
import argparse, hashlib, json
from pathlib import Path
import numpy as np
import onnxruntime as ort
p = argparse.ArgumentParser(); p.add_argument('model', type=Path); p.add_argument('output', type=Path); args=p.parse_args()
sha=hashlib.sha256(args.model.read_bytes()).hexdigest()
assert sha=='9931a595afd6c780bdbe5ef12e99d34526b6f30dbc7641cf920572798e4ed96d'
session=ort.InferenceSession(str(args.model),providers=['CPUExecutionProvider'])
patterns=[]
y,x=np.mgrid[:416,:416]
for name,tensor in [('gray114',np.full((1,3,416,416),114/255,dtype=np.float32)),('rgbGradient',np.stack((x%256,y%256,(x+y)%256))[None].astype(np.float32)/255)]:
    output=session.run(['output0'],{'images':tensor})[0]
    assert output.shape==(1,84,3549) and np.isfinite(output).all()
    anchors={0,17,355,1774,3548}
    for cls in [0,1,2,3,5,7,16,15]: anchors.add(int(np.argmax(output[0,4+cls])))
    indices=sorted(channel*3549+anchor for channel in range(84) for anchor in anchors)
    patterns.append({'name':name,'samples':[{'index':i,'value':float(output.reshape(-1)[i])} for i in indices]})
args.output.write_text(json.dumps({'sha256':sha,'runtime':ort.__version__,'patterns':patterns},indent=2)+'\n')
