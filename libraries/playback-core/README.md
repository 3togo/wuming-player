# TVBox playback core

Artifact: `com.fongmi.tv:tvbox-playback-core:0.1.0-SNAPSHOT`

This library owns protocol inference, internal playback capability metadata, episodic cast queues,
HLS playlist purification and cache decisions.
It does not contain a player engine; Android Media3 and future desktop playback engines consume its
decisions through their own adapters.

HLS rule selection remains a host adapter concern. The core purifier accepts already-selected
rules and a URI resolver, which keeps Android configuration and CatVod URL behavior outside the
library.
