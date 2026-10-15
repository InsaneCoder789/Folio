import itertools
import unittest
from ml.fullpage_geometry import canonical_quad,reading_order,perspective_crop,map_to_boxes


def box(identity,x,y,w=100,h=20):
    return dict(id=identity,polygon=[(x,y),(x+w,y),(x+w,y+h),(x,y+h)])


class FullPageGeometryTests(unittest.TestCase):
    def test_quad_is_permutation_invariant_and_rejects_degeneracy(self):
        expected=[(0.,0.),(100.,0.),(100.,20.),(0.,20.)]
        for points in itertools.permutations(expected):self.assertEqual(expected,canonical_quad(points))
        with self.assertRaises(ValueError):canonical_quad([(0,0)]*4)
        with self.assertRaises(ValueError):canonical_quad([(0,0),(float('nan'),0),(1,1),(0,1)])

    def test_two_columns_read_down_left_then_down_right(self):
        rows=[box('r2',200,50),box('l1',0,10),box('r1',200,10),box('l2',0,50)]
        for inputs in itertools.permutations(rows):
            self.assertEqual(['l1','l2','r1','r2'],[b['id'] for b in reading_order(inputs)])

    def test_heading_is_read_before_two_column_body(self):
        rows=[box('heading',0,0,300),box('r2',200,100),box('l1',0,60),box('r1',200,60),box('l2',0,100)]
        self.assertEqual(['heading','l1','l2','r1','r2'],[b['id'] for b in reading_order(rows)])

    def test_same_baseline_reads_left_to_right(self):
        self.assertEqual(['a','b'],[b['id'] for b in reading_order([box('b',50,1,40),box('a',0,0,40)])])
        self.assertEqual([],reading_order([]))
        with self.assertRaises(ValueError):reading_order([box(str(i),0,i) for i in range(513)])

    def test_rectified_crop_preserves_known_pixel_endpoints(self):
        import numpy as np
        image=np.zeros((60,100,3),dtype=np.uint8)
        image[:,:,0]=np.arange(100,dtype=np.uint8)
        crop=perspective_crop(image,[(10,10),(50,10),(50,30),(10,30)])
        self.assertEqual((20,40,3),crop.shape)
        self.assertEqual(10,int(crop[0,0,0]));self.assertEqual(50,int(crop[-1,-1,0]))
        with self.assertRaises(ValueError):perspective_crop(image,[(-1,0),(50,0),(50,30),(0,30)])

    def test_probability_postprocessing_finds_confident_regions_in_source_coordinates(self):
        import numpy as np
        probability=np.zeros((100,100),dtype=np.float32)
        probability[10:20,10:70]=.95;probability[60:70,10:70]=.9
        boxes=map_to_boxes(probability,200,200)
        self.assertEqual(2,len(boxes))
        self.assertLess(boxes[0]['polygon'][0][1],boxes[1]['polygon'][0][1])
        self.assertGreater(boxes[0]['detectorScore'],.9)
        self.assertTrue(all(0<=x<200 and 0<=y<200 for b in boxes for x,y in b['polygon']))

    def test_postprocessing_does_not_invent_text_regions(self):
        import numpy as np
        self.assertEqual([],map_to_boxes(np.zeros((32,32),dtype=np.float32),100,100))
        probability=np.ones((32,32),dtype=np.float32)*.4
        self.assertEqual([],map_to_boxes(probability,100,100))
        probability[0,0]=float('nan')
        with self.assertRaises(ValueError):map_to_boxes(probability,100,100)
        with self.assertRaises(ValueError):map_to_boxes(np.zeros((32,32),dtype=np.float32),4000,4000)


if __name__=='__main__':unittest.main()
