# Bake a frosted-glass patch (blur + saturate) under the dock band and the top-bar buttons.
import sys,glob
from PIL import Image,ImageDraw,ImageFilter,ImageEnhance
src,dst=sys.argv[1],sys.argv[2]
W,H=1920,1080
mask=Image.new("L",(W,H),0); d=ImageDraw.Draw(mask)
d.rounded_rectangle((114,753,1805,962),radius=46,fill=255)
for cx in (1557,1661,1766): d.ellipse((cx-41,159-41,cx+41,159+41),fill=255)
mask=mask.filter(ImageFilter.GaussianBlur(1.5))
dark=Image.new("L",(W,H),0); dd=ImageDraw.Draw(dark)
for cx in (1557,1661,1766): dd.ellipse((cx-41,159-41,cx+41,159+41),fill=150)
dark=dark.filter(ImageFilter.GaussianBlur(1.5))
for f in sorted(glob.glob(src+"/*.jpg")):
    im=Image.open(f).convert("RGB"); size=im.size; im=im.resize((W,H),Image.LANCZOS)
    fr=ImageEnhance.Color(im.filter(ImageFilter.GaussianBlur(26))).enhance(1.4)
    im.paste(fr,(0,0),mask); im.paste(Image.new("RGB",(W,H)),(0,0),dark); im.resize(size,Image.LANCZOS).save(dst+"/"+f.split("/")[-1],quality=90)
print("frosted")
