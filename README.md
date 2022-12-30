# RTP

A spigot plugin for random teleportation.
https://www.spigotmc.org/resources/rtp.94812/

The goal of this plugin is function over form, 
 * unique geometry to produce flat random distributions and support a learning algorithm
 * unique methods to learn from placements and avoid redundant selections, e.g. trying the same ocean thousands of times
 * region-based design, supporting any number of rtp regions per world
 * per-world and per-region configuration and teleport permissions
 * adjustment of region and world settings by command or by config file 
 * adjustment of all plugin messages in lang.yml

The plugin has some pretty useful shapes by default - 

circle, using an exponential distribution of 0.1, 1.0, 10.0
![zu5gW62](https://user-images.githubusercontent.com/28832622/210043913-fd624a9f-8bdd-45de-b877-6a5f5e3bf40a.png)

square, using an exponential distribution of 0.1, 1.0, 10.0
![3mrkKh1](https://user-images.githubusercontent.com/28832622/210043922-4d94e3d6-e829-4adc-a21a-74cce484f8e6.png)

circle, using a normal distribution
![SUGBQk3](https://user-images.githubusercontent.com/28832622/210043926-5c5013cf-032e-444c-9397-e381c17a4752.png)

square, using a normal distribution
![pzu9j63](https://user-images.githubusercontent.com/28832622/210043956-df964dde-4c70-460b-a377-ffd49a365e69.png)

rectangle, using a flat distribution and a rotation
![3Yw2tBj](https://user-images.githubusercontent.com/28832622/210043964-ca9725b8-be25-4e3c-a460-90f8b81326cb.png)

This plugin also allows arbitrary shape addition via API calls.
