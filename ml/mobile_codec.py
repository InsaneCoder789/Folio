"""Portable single-line preprocessing and CTC decoding for the pinned English model.

Contract follows PaddleX 3.7.2 OCRReisizeNormImg / CTCLabelDecode (Apache-2.0).
"""
import math
import operator


def decode_ctc(indices,alphabet):
    text=[];previous=None
    for value in indices:
        index=operator.index(value)
        if not 0<=index<len(alphabet):raise ValueError('CTC index outside the pinned alphabet')
        if index and index!=previous:text.append(alphabet[index])
        previous=index
    return ''.join(text)


def prepare_tensor(bgr):
    import cv2
    import numpy as np
    if bgr.ndim!=3 or bgr.shape[2]!=3 or min(bgr.shape[:2])<1 or bgr.dtype!=np.uint8:
        raise ValueError('Use a nonempty uint8 BGR line image')
    height,width=bgr.shape[:2]
    target_width=min(3200,int(48*max(320/48,width/height)))
    resized_width=min(target_width,math.ceil(48*width/height))
    image=cv2.resize(bgr,(resized_width,48)).astype('float32').transpose((2,0,1))/255
    image-=.5;image/=.5
    tensor=np.zeros((1,3,48,target_width),dtype=np.float32)
    tensor[0,:,:,:resized_width]=image
    return np.ascontiguousarray(tensor)
