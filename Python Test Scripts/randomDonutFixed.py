import matplotlib.pyplot as plt
import math
import random
plt.style.use('seaborn-whitegrid')

plt.title('Method 3')

radius = 8192
centerRadius = 8191
totalSpace = math.pi*(radius-centerRadius)*(radius+centerRadius)
for i in range(int(totalSpace)) :
    rSpace = float(i)
    #rSpace = random.randrange(0,int(totalSpace))
    
    rDistance = math.sqrt(rSpace/math.pi + centerRadius*centerRadius)
    rotation = (rDistance - int(rDistance))*2*math.pi
    
    x = rDistance * math.cos(rotation)
    z = rDistance * math.sin(rotation)
    
    plt.plot(x,z,'o',color='black')
    
plt.show();
