# Bake a frosted-glass patch (blur + saturate) under the dock band.
import sys,glob
from PIL import Image,ImageDraw,ImageFilter,ImageEnhance
src,dst=sys.argv[1],sys.argv[2]
W,H=1920,1080
mask=Image.new("L",(W,H),0); d=ImageDraw.Draw(mask)
d.rounded_rectangle((114,753,1805,962),radius=46,fill=255)
mask=mask.filter(ImageFilter.GaussianBlur(1.5))
for f in sorted(glob.glob(src+"/*.jpg")):
    im=Image.open(f).convert("RGB"); size=im.size; im=im.resize((W,H),Image.LANCZOS)
    fr=ImageEnhance.Color(im.filter(ImageFilter.GaussianBlur(26))).enhance(1.4)
    im.paste(fr,(0,0),mask); im.resize(size,Image.LANCZOS).save(dst+"/"+f.split("/")[-1],quality=90)
print("frosted")
