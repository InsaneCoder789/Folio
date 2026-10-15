"""Pinned BGR detector tensor contract; rounding follows Python's ties-to-even rule."""


def detector_dimensions(width,height,limit=1280):
    if min(width,height)<32 or limit<32:raise ValueError('Use a full-page image with both dimensions at least 32')
    ratio=min(1.,limit/max(width,height))
    return max(32,round(int(width*ratio)/32)*32),max(32,round(int(height*ratio)/32)*32)


def prepare_detector_tensor(bgr):
    import cv2,numpy as np
    if bgr.ndim!=3 or bgr.shape[2]!=3 or bgr.dtype!=np.uint8:raise ValueError('Use uint8 BGR page pixels')
    height,width=bgr.shape[:2];w,h=detector_dimensions(width,height)
    image=cv2.resize(bgr,(w,h)) if (w,h)!=(width,height) else bgr
    mean=(.485,.456,.406);std=(.229,.224,.225);channels=[]
    for c in range(3):
        plane=image[:,:,c].astype(np.float32);plane*=(1./255)/std[c];plane+=-mean[c]/std[c];channels.append(plane)
    return np.ascontiguousarray(np.stack(channels)[None])
