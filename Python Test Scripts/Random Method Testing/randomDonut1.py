import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use('seaborn-whitegrid')

plt.title('Before')

it = 100000
radius = 4096
centerRadius = 0
x = np.zeros(it)
z = np.zeros(it)
filterIter = 100
radii = np.zeros(radius-centerRadius)
lpf = np.zeros(radius-centerRadius-filterIter*2+1)
def randomSelect(i) :
    r = centerRadius + (radius-centerRadius)*math.sqrt(random.uniform(0,1))
    rotation = random.uniform(0,1)*math.pi*2
    
    xLoc = int(r * math.cos(rotation))
    zLoc = int(r * math.sin(rotation))

    x[i] = xLoc
    z[i] = zLoc

    radii[int(r)-centerRadius] = radii[int(r)-centerRadius] + 1
    
#start = time.time()
for i in range(it) :
    randomSelect(i)
#stop = time.time()
#print("time: ",stop-start)

#plt.plot(x,z,'o',color='black')

for i in range(filterIter,radius-centerRadius-filterIter) :
    for j in range(-filterIter,filterIter) :
        lpf[i-filterIter] = lpf[i-filterIter] + radii[i+j]/(filterIter*2+1)
plt.plot(range(radius-centerRadius-filterIter*2+1),lpf,'o',color='black')


plt.show();
