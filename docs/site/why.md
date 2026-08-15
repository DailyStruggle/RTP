# Why

I made this plugin originally to fix 2 major holes in RTP plugin design:

1. placement distribution

    i.e. uneven utilization of pre-generated land

2. Excessive rerolls and chunk loading

    i.e. unpredictable time complexity, particularly when each selection requires chunk loading prior to placement checking, fixable with a better model that's compatible with placement memory.

### Method 1
A simplistic approach to picking a spot in a circle is to pick a random radius and a random angle. However, the result ends up looking a little like this: 

![](https://i.imgur.com/xd8eNog.png)
![](https://i.imgur.com/n6Ie5tH.png)

It might look random enough at first glance, but selection seems to tend towards the middle

![](https://i.imgur.com/wSnd0yO.png)

### Method 2
A slightly more nuanced approach ([previously used in BetterRTP](https://github.com/SuperRonanCraft/BetterRTP/blob/db14e688b9ec1de129db6eef86308e9f915e51a8/src/main/java/me/SuperRonanCraft/BetterRTP/references/worlds/WorldPlayer.java#L166-L177)) is to take a square root of the random result to account for the inverse square law and shift all values out some, getting a result like this:

![](https://i.imgur.com/AyzjXSy.png)
![](https://i.imgur.com/7ORYnXN.png)

Looks great, but I seem to consistently get large untouched areas closer to the center. Even if I triple the number of attempts, it's a rough edge. 

![](https://i.imgur.com/RJ0rzPj.png)

This happens because the circle in the calculation is simply stretched into a donut, as if separating pizza slices.

As you can see from the distance distributions (between the donut hole and donut edge),

![number of placements at each distance, given 60k random placements](https://i.imgur.com/VYTpgot.png)

In the stretched circle algorithm, there are close to 0 placements near the donut starting point and placement tends towards the outer edge. This creates some clustering around the edge of the donut, such that players get placed closer together as the distance from the center point increases and points closer to the center are less utilized.

It's preferable to utilize available space equally.

### Method 3
However, it's entirely possible and frustratingly simple to create a uniform pattern, using one random location on a space-filling curve.

Given an integer area of a 2D donut shape,

(outer circle area - inner circle area)

_r2 = outer radius_

_r1 = inner radius_

_area = 2(pi)(r2-r1)(avg(r2,r1))_

simplified,

_area = pi(r2-r1)(r2+r1)_

get a new random spatial area, call it distance along a curve

_d = random(0,area)_

solve for the new radius

_r3 = sqrt(d/pi + centerRad^2)_

convert the decimal component to an angle

_angle = 2(pi)(r3 - int(r3))_

and convert radius and angle to x,z coordinates as usual

Checking each integer d from 0 to area, the result is a spiral pattern, with each layer at a distance of 1 from the last.

![](https://i.imgur.com/76ri32z.png)
![](https://i.imgur.com/I2dPFCI.png)

applying randomization from 0 to area,

![](https://i.imgur.com/nOGuGL8.png)
![](https://i.imgur.com/mFr5tyJ.png)

When I take it from 5000 points to 15000 again, the graph is as smooth as a baby's bottom

![](https://i.imgur.com/TJCr1mH.png)
![](https://i.imgur.com/Lr0nsOm.png)

To ensure that each integer location has an equal chance of selection and to reduce the number of random calls needed for it, a space-filling curve is currently my best option

### Sanity Check
Just to make sure I was actually solving the problem, I took methods 2 and 3 to 60k iterations.

here are the differences:
![](https://i.imgur.com/A9h54Th.png)
![](https://i.imgur.com/cmeC3IE.png)

### Square Equivalent
A square spiral was harder to conceptualize, but it came out pretty clean and just as evenly distributed
![](https://i.imgur.com/hfu5i4H.png)
![](https://i.imgur.com/c8CkwPd.png)

## Compared to other RTP plugins

I took up this project as a challenge to solve a single problem with random teleportation that often gets brushed over - nondeterministic search time due to duplicate selections and the time each selection takes when loading chunks.

The model I used to solve this problem seemed difficult to work into a pre-existing plugin, so I put together my own plugin with some programming know-how. I wanted to represent that plugin as a foundation for random teleportation, so I chose a simple name, `RTP`.

The other plugins I consider rivals are mainly BetterRTP and JakesRTP - BetterRTP for its popularity and documentation, JakesRTP for its flexible configuration, location caching, and interesting selection models.

### Selection

The selection mechanisms for each of these encapsulate easy mathematical models for selection followed almost immediately by chunk loading and placement testing.

- BetterRTP uses a basic width/height geometric calculation, sometimes splitting up a shape to avoid rejection sampling before chunk loading.
- JakesRTP offers more flexible configuration and adds gaussian distributions to the x-z coordinate selection via `Random.nextGaussian()` calls, using rejection sampling to exclude selections outside the selected range.
- RTP uses more complex mathematical concepts to map and recall bad selections in a 2D plane, which helps prevent duplicate selections and reduces chunk loading as region usage increases. RTP also approximates both exponential and gaussian distributions.

### Background processing and memory

BetterRTP has historically had some difficulty with an attempted queue system, and carries a few extra time costs between command and teleportation. It leans toward file interactions over memory allocation; RTP prefers the opposite, which is reflected in the timing and memory-usage differences.

Like JakesRTP, RTP adds a location cache system, operating at a configurable rate (defined in `performance.yml`) and capped per-region. Unlike JakesRTP, selected chunks are force-loaded until used in order to achieve millisecond response times on commands - the cost of 10-100 extra chunks is less than one online player, which I consider acceptable. To resolve the possibility of changes to the chunk between selection and usage, a second safety check runs when the location is picked up from the cache.

RTP also adds player queues, operating at cache rate, to prevent overlapping selection tasks by default. That means `/rtp` cannot easily be used to DDoS the server via chunk loading.
