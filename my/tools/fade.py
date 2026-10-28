import sys,glob
from PIL import Image
src,dst,top,alpha=sys.argv[1],sys.argv[2],float(sys.argv[3]),float(sys.argv[4])
for f in sorted(glob.glob(src+"/*.jpg")):
    im=Image.open(f).convert("RGB"); w,h=im.size
    g=Image.new("L",(1,h))
    for y in range(h):
        t=max(0,(y/h-top)/(1-top), ((1-y/h)-top)/(1-top)); g.putpixel((0,y),int(255*alpha*t**1.6))  # footer and mirrored header
    im.paste(Image.new("RGB",(w,h)),(0,0),g.resize((w,h)))
    im.save(dst+"/"+f.split("/")[-1],quality=88)
print("done")
