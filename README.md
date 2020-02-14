# Miscelanous loxone stuff

Purpose:

Misc loxone stuff such as mqtt bridge, grafana, zigbee etc ...

## Architecture

Everything is based on mqtt so you need to configure and run bridge first. Bridge then receive value changes from loxone web interface
and sends them to mqtt topic "lox_out". It also sends mqtt messages from topic 'lox_in' to loxone as udp text messages.

## How to start:

Initialize submodules:

    git submodule init
    git submodule update
    
Install docker and docker-compose.    
    
## Bridge    

Bridge is core part of all stuff in this repo. Bridge is responsible for loxone<-->mqtt communication.

You have to create 'config' file in bridge/. Copy config.example and edit what necessary
(mostly loxone address, username, password and udp port number. Let other settings at default (or set DEBUG=1 until everything will work, then set it back to 0)).
 
Try to start bridge:

    ./run.sh
    
Check logs, you should see receiving mqtt messages from loxone. If ok, optionally disable debug and set logging driver
to none in .env file (especially if you are running on sdcard or similar piece of crap). Then run bridge on background:

    ./run.sh -d
    
    
## Zigbee

Project is meant as follow-up to https://www.zigbee2mqtt.io/.

##### What you should already know before you start:
- you know how to create udp inputs in loxone
- you already have custom flashed zigbee usb token (https://www.zigbee2mqtt.io/) 
            
Here is the same situation as in bridge, create 'config' file (use example), probably you don't need to modify anything.
 
Try to start zigbee stuff:

    ./run.sh [-d]

TODO describe basic workflow with zigbee, how to enable joining and rename devices using mobile app etc..

# How it works

Zigbee2mqtt receives zigbee messages from zigbee devices and sends them to mqtt topic 'zigbee'.
Loxone_zigbee_gateway listens for that topics and extracts all data coming from zigbee device,
mapping it to udp messages which are then sent to loxone miniserver.

Example:

Zigbee device 0x00158d00044a1146 (named aquara_1) sends payload:

    {
        "battery":100,
        "voltage":3055,
        "temperature":25.61,
        "humidity":44.2,
        "pressure":975,
        "linkquality":115
    } 

then following udp messages will be send to loxone:

    zigbee/aquara_1/battery 100
    zigbee/aquara_1/voltage 3055
    zigbee/aquara_1/temperature 25.61
    zigbee/aquara_1/humidity 44.2
    zigbee/aquara_1/pressure 975
    zigbee/aquara_1/linkquality 115
    
You should start UDP monitor in loxone, wait until all required messages will be received
and then create virtual udp command for each value you are interested for.

For example create analog input with command recognition:

    zigbee/aquara_1/temperature \v
   
... and you will receive temperature as analog value.

To create oposite way of communication (from loxone to zigbee), you only have to create new "zigbee_out" category
in loxone config. All virtual inputs with that category will be read by the bridge and send to zigbee devices using following naming rules:

Example:

Analog virtual output named "led1_set_brightness" with category "zigbee_out" will be sent to mqtt topic "zigbee/led1/set" with payload { "brightness" : VALUE }.
Zigbee2mqtt will send that brightness command to zigbee device named "led1".


## Pairing using android phone

If you don't want to use you laptop everytime you need to pair new device, you should use any mqtt dashboard application capable
to receive and send messages. Here is quick howto for android 'mqtt dashboard' app:

- click on (+) in bottom right corner
- Client ID: whatever you want
- Server: your mqtt server address
- Port: usually 1883
- click CREATE
- in Subscribe section
    - create listener for logs (mqtt address zigbee/bridge/log)
- in Publish section
    - create new switch, name "Permit join", topic zigbee/bridge/config/permit_join, text on/off, publish value true/false
    - create new text, name "Rename last", topic zigbee/bridge/config/rename_last
    
Now connect to mqtt server, go to publish, switch pairing on. Check for logs in subscribe section that bridge confirmed
that pairing is on (message will ends with permit_join:true). Then you should start pairing the zigbee device
and see result of pairing in subscribe/logs directly on your mobile phone.

After the device is successfully paired, you should rename it to some friendly name using
publish/rename last event. Simply write new name and publish it directly after pairing is done.    
And that's all.

#### TODO debugging



## Grafana

TODO

## Common stuff:

You should consider to disable logging at all if running on system with disks-which-dont-like-too-much-writes. Especially on sdcards.
Set DEBUG=0 in all config files and also set LOGGING_DRIVER=none in .env files.