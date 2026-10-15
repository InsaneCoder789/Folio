"""Bounded DB-map postprocessing, perspective crops and English reading order."""
import math
from statistics import median


def canonical_quad(points):
    if len(points)!=4:raise ValueError('A line needs exactly four vertices')
    pts=[(float(x),float(y)) for x,y in points]
    if not all(math.isfinite(v) for p in pts for v in p):raise ValueError('Non-finite line geometry')
    cx=sum(p[0] for p in pts)/4;cy=sum(p[1] for p in pts)/4
    pts.sort(key=lambda p:math.atan2(p[1]-cy,p[0]-cx))
    first=min(range(4),key=lambda i:(sum(pts[i]),pts[i][1],pts[i][0]));pts=pts[first:]+pts[:first]
    cross=[(pts[(i+1)%4][0]-pts[i][0])*(pts[(i+2)%4][1]-pts[(i+1)%4][1])-
           (pts[(i+1)%4][1]-pts[i][1])*(pts[(i+2)%4][0]-pts[(i+1)%4][0]) for i in range(4)]
    if min(cross)<=1e-6:raise ValueError('Line must be a nondegenerate convex quad')
    return pts


def bounds(box):
    q=canonical_quad(box['polygon'])
    return min(x for x,y in q),min(y for x,y in q),max(x for x,y in q),max(y for x,y in q)


def reading_order(boxes):
    """Recursive whitespace XY cut for horizontal English; not arbitrary layouts."""
    if len(boxes)>512:raise ValueError('Page line count exceeds 512')
    if not boxes:return []
    decorated=[(box,bounds(box)) for box in boxes]
    def gap(items,axis,minimum):
        intervals=sorted((b[axis],b[axis+2]) for _,b in items)
        end=intervals[0][1];gaps=[]
        for start,stop in intervals[1:]:
            if start-end>=minimum:gaps.append((start-end,(start+end)/2))
            end=max(end,stop)
        return max(gaps,default=(0,None))[1]
    def walk(items,depth=0):
        if len(items)<2:return [b for b,_ in items]
        if depth>64:raise ValueError('Layout recursion exceeds bound')
        height=median(b[3]-b[1] for _,b in items)
        # Whole-column whitespace takes priority. A spanning heading prevents
        # that cut until a horizontal whitespace separator isolates its body.
        for axis,minimum in ((0,max(12,height*1.5)),(1,max(3,height*.55))):
            cut=gap(items,axis,minimum)
            if cut is not None:
                first=[p for p in items if (p[1][axis]+p[1][axis+2])/2<cut]
                second=[p for p in items if (p[1][axis]+p[1][axis+2])/2>=cut]
                if first and second:return walk(first,depth+1)+walk(second,depth+1)
        # No clear whitespace: group overlapping baselines, then left-to-right.
        rows=[]
        for item in sorted(items,key=lambda p:((p[1][1]+p[1][3])/2,p[1][0])):
            center=(item[1][1]+item[1][3])/2
            if rows and abs(center-median((b[1]+b[3])/2 for _,b in rows[-1])) <=height*.45:
                rows[-1].append(item)
            else:rows.append([item])
        return [box for row in rows for box,b in sorted(row,key=lambda p:(p[1][0],p[1][1],str(p[0].get('id',''))))]
    return walk(decorated)


def perspective_crop(bgr,polygon):
    import cv2,numpy as np
    if bgr.ndim!=3 or bgr.shape[2]!=3 or bgr.dtype!=np.uint8:raise ValueError('Use uint8 BGR pixels')
    quad=canonical_quad(polygon);height,width=bgr.shape[:2]
    if any(not 0<=x<=width-1 or not 0<=y<=height-1 for x,y in quad):raise ValueError('Crop lies outside page')
    length=lambda a,b:math.hypot(a[0]-b[0],a[1]-b[1])
    w=max(2,round(max(length(quad[0],quad[1]),length(quad[3],quad[2]))))
    h=max(2,round(max(length(quad[0],quad[3]),length(quad[1],quad[2]))))
    if w*h>4_000_000:raise ValueError('Line crop exceeds 4MP')
    matrix=cv2.getPerspectiveTransform(np.asarray(quad,dtype=np.float32),np.asarray([(0,0),(w-1,0),(w-1,h-1),(0,h-1)],dtype=np.float32))
    return cv2.warpPerspective(bgr,matrix,(w,h),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)


def map_to_boxes(probability,original_width,original_height,*,threshold=.3,box_threshold=.6,unclip_ratio=1.5):
    import cv2,numpy as np,pyclipper
    if probability.ndim!=2 or not np.isfinite(probability).all():raise ValueError('Need a finite 2D probability map')
    if min(probability.shape)<2 or probability.size>4_000_000:raise ValueError('Detector map exceeds bounds')
    if min(original_width,original_height)<2 or original_width*original_height>9_000_000:raise ValueError('Page exceeds 9MP')
    if not (0<threshold<1 and 0<box_threshold<=1 and 0<unclip_ratio<=3):raise ValueError('Invalid DB thresholds')
    if probability.min() < -1e-5 or probability.max() > 1.00001:raise ValueError('Map must contain probabilities')
    h,w=probability.shape
    contours,_=cv2.findContours((probability>threshold).astype(np.uint8)*255,cv2.RETR_LIST,cv2.CHAIN_APPROX_SIMPLE)
    candidates=[]
    # Bound pathological masks without silently selecting the first contours.
    if len(contours)>2000:raise ValueError('Too many detector components')
    for contour in contours:
        if len(contour)<3:continue
        area=abs(cv2.contourArea(contour));perimeter=cv2.arcLength(contour,True)
        if area<9 or perimeter<=0:continue
        x,y,rw,rh=cv2.boundingRect(contour)
        mask=np.zeros((rh,rw),dtype=np.uint8)
        cv2.fillPoly(mask,[contour-np.asarray([[[x,y]]])],1)
        score=float(cv2.mean(probability[y:y+rh,x:x+rw],mask)[0])
        if score<box_threshold:continue
        offset=pyclipper.PyclipperOffset()
        offset.AddPath([(int(p[0][0]*1024),int(p[0][1]*1024)) for p in contour],pyclipper.JT_ROUND,pyclipper.ET_CLOSEDPOLYGON)
        expanded=offset.Execute(area*unclip_ratio/perimeter*1024)
        if len(expanded)!=1:continue
        rect=cv2.minAreaRect(np.asarray(expanded[0],dtype=np.float32)/1024)
        if min(rect[1])<3:continue
        polygon=cv2.boxPoints(rect)
        polygon[:,0]=np.clip(polygon[:,0]*original_width/w,0,original_width-1)
        polygon[:,1]=np.clip(polygon[:,1]*original_height/h,0,original_height-1)
        try:quad=canonical_quad(polygon.tolist())
        except ValueError:continue
        candidates.append(dict(polygon=quad,detectorScore=score))
    ordered=[]
    for box in sorted(candidates,key=lambda b:(-b['detectorScore'],bounds(b))):
        a=np.asarray(box['polygon'],dtype=np.float32);duplicate=False
        for prior in ordered:
            b=np.asarray(prior['polygon'],dtype=np.float32)
            intersection=float(cv2.intersectConvexConvex(a,b)[0]);union=abs(cv2.contourArea(a))+abs(cv2.contourArea(b))-intersection
            if union>0 and intersection/union>.7:duplicate=True;break
        if not duplicate:ordered.append(box)
        if len(ordered)>512:raise ValueError('Detected line count exceeds 512')
    for i,box in enumerate(reading_order(ordered)):box['id']=f'line-{i:04d}'
    return reading_order(ordered)
