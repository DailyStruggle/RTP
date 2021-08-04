import matplotlib.pyplot as plt
import numpy as np
import math
import random
import time
plt.style.use('seaborn-whitegrid')

plt.title('After')

it = 5000
radius = 4096
centerRadius = 0
x = np.zeros(it)
z = np.zeros(it)
def randomSelect(i) :
    totalSpace = math.pi*(radius+centerRadius)*(radius-centerRadius)
    randSpace = random.randrange(0,int(totalSpace))
    
    distance = math.sqrt(randSpace/math.pi + centerRadius*centerRadius)
    rotation = (distance - int(distance))*math.pi*2
    
    xLoc = distance * math.cos(rotation)
    zLoc = distance * math.sin(rotation)

    x[i] = xLoc
    z[i] = zLoc
    
start = time.time()
for i in range(it) :
    randomSelect(i)
stop = time.time()
print("time: ",stop-start)

plt.plot(x,z,'o',color='black')

plt.show();
