import matplotlib.pyplot as plt
import math
import random
plt.style.use('seaborn-whitegrid')

plt.title('SQUARE')
radius = 4096
centerRadius = 1024
totalSpace = (radius-centerRadius)*(radius+centerRadius)
for i in range(60000) :
    rSpace = random.randrange(0,int(totalSpace))
    
    rDistance = math.sqrt(rSpace + centerRadius*centerRadius)
    remainder = (rDistance - int(rDistance))
    perimeterStep = 8*(rDistance*remainder)
    
    if(perimeterStep < rDistance):
        x = rDistance
        z = (perimeterStep%rDistance)
    elif(perimeterStep < rDistance*2):
        x = rDistance - (perimeterStep%rDistance)
        z = rDistance
    elif(perimeterStep < rDistance*3):
        x = -(perimeterStep%rDistance)
        z = rDistance
    elif(perimeterStep < rDistance*4):
        x = -rDistance
        z = rDistance - (perimeterStep%rDistance)
    elif(perimeterStep < rDistance*5):
        x = -rDistance
        z = -(perimeterStep%rDistance)
    elif(perimeterStep < rDistance*6):
        x = -(rDistance - (perimeterStep%rDistance))
        z = -rDistance 
    elif(perimeterStep < rDistance*7):
        x = (perimeterStep%rDistance)
        z = -rDistance
    else:
        x = rDistance
        z = -(rDistance-(perimeterStep%rDistance))
    
    

    plt.plot(x,z,'o',color='black')
    
plt.show();
