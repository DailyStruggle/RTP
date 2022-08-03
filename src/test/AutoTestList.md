This file should describe the unit tests that need to run before deployment.

For more specific test info like error codes, check the folder containing the unit test.

Bukkit and Sponge code will not exist at compile time, so unit tests cannot test all the server-dependent code. To get around this for main features, custom types were developed to provide necessary functions to the API. 

todo: develop a way to test server-specific functions in local unit tests to get around manual testing.
todo: ^ maybe fill in all those types in the test directory?
todo: set up a snapshot pipeline