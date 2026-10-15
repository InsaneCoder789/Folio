"""Render candidate crops beside their exact source labels for visual admission."""
import argparse
from collections import defaultdict
from pathlib import Path
import re
import textwrap
from ml.manifest import read_manifest


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--manifest',type=Path)
    parser.add_argument('--split',choices=('train','validation'),default='validation')
    args=parser.parse_args()
    if args.manifest is None:args.manifest=Path('ml/data')/('modern-validation' if args.split=='validation' else 'modern-training')/'review-candidates.jsonl'
    rows=read_manifest(args.manifest,required_splits=(args.split,),allowed_licenses=('CC-BY-SA-4.0',))
    from PIL import Image,ImageDraw,ImageFont,ImageOps
    font_path=Path(__file__).resolve().parents[1]/'android/app/src/main/assets/fonts/NotoSans-Regular.ttf'
    font=ImageFont.truetype(str(font_path),18)
    grouped=defaultdict(list)
    for row in rows:grouped[row['document_id']].append(row)
    output=args.manifest.parent/'review-boards';output.mkdir(exist_ok=True)
    for document,lines in sorted(grouped.items()):
        if not re.fullmatch('[0-9a-f]{16}',document):raise ValueError('Unexpected modern source image identifier')
        cards=[]
        for row in lines:
            with Image.open(row['image_path']) as image:crop=ImageOps.contain(image.convert('RGB'),(1340,180))
            label=textwrap.wrap(row['text'],width=125) or ['']
            cards.append((row['id'],label,crop))
        height=70+sum(crop.height+90+24*len(label) for identifier,label,crop in cards)
        board=Image.new('RGB',(1400,height),'white');draw=ImageDraw.Draw(board)
        draw.text((24,15),document+' | candidate review | source: HierText CC BY-SA 4.0',font=font,fill='black')
        y=70
        for identifier,label,crop in cards:
            draw.text((24,y),identifier,font=font,fill='black');y+=30
            board.paste(crop,(24,y));y+=crop.height+16
            for line in label:draw.text((24,y),'GT: '+line,font=font,fill='black');y+=24
            draw.line((24,y+12,1370,y+12),fill='#cccccc');y+=44;crop.close()
        path=output/(document+'.png');board.save(path);board.close();print(path,flush=True)


if __name__=='__main__':main()
