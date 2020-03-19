# Miscelanous loxone stuff

Purpose:

Misc loxone stuff such as mqtt bridge, grafana, zigbee etc ...

## Quickstart for impatient

- you know how to create udp inputs in loxone
- you already have custom flashed zigbee usb token (https://www.zigbee2mqtt.io/) 

Install docker, docker-compose, git. Should be as simple as:

    sudo -i
    curl -sSL https://get.docker.com | sh
    apt-get install docker.io docker-compose git wget

but check your distro documentation.

Then clone and initialize this repo:

    git clone https://github.com/dusanmsk/loxone.git
    cd loxone
    git submodule init
    git submodule update

## MQTT bridge
    
Configure mqtt bridge. Bridge is core part of all components in this repo. Bridge is responsible for loxone<-->mqtt communication.

Create dedicated user in loxone for this gateway and new virtual udp input. Then create config file from example and edit what is necessary
(mostly loxone address, username, password and udp port number. Let other settings untouch (or set DEBUG=1 until everything will work, then set it back to 0)).

    cd bridge
    cp config.example config
    <YOUR EDITOR> config


Now start the bridge in foreground mode:

    ./run.sh

You should see something like
    
    loxone2mqtt_1  | {"message":"Loxone to MQTT gateway started","level":"info"}
    mqtt2loxone_1  | 441 [main] INFO MqttToLoxoneUDP - Connected to mqtt tcp://localhost:1883 and ready         


## Zigbee

Let it run in foreground, open another terminal (screen,tmux,byobu, ...) and go to zigbee folder. Create config from example (probably you don't need to modify anything if you didn't touch topic names in gateway config):

    cd ../zigbee
    cp config.example config
    
Build all containers (it will download required stuff):

    ./build.sh
    
If everything is ok, run zigbee stuff in foreground:

    ./run.sh


### zigbee web management
    
Now open web browser and go to port 8881 (you should change it in docker-compose.yml file).

Main windows is used to list, pair and rename zigbee devices. Click on "Enable". Now try to pair new zigbee device.
Now click on 'Refresh' button to see if device was sucessfully paired.

Zigbee logs should give more detailed info what is going on. It listens and show all zigbee mqtt messages.

To translate values coming from zigbee to loxone (for example translating "true" and "false" coming from xiaomi door contact)
to loxone understandable values (for example 1 or 0), click on Edit button. Now open and close door so manager will receive
messages from zigbee device. Click on Refresh. You will see all zigbee device messages received in last period of time.
Click on value you want to remap, now fill new value to "Map to:" textfield and click on "Create mapping".
When done, click on 'Save' button. 

## Backup and restore

#### bridge

Do backup of 'config' file.

#### zigbee

Do backup of 'config' and 'settings.json' file
Do backup (as root) of zigbee2mqtt/data directory.


## Upgrading

TODO


# More detailed info

## Architecture

Everything is based on mqtt so you need to configure and run bridge first. Bridge then receive value changes from loxone web interface
(using node-lox-mqtt-gateway) and sends them to mqtt topic "lox_out". It also sends mqtt messages received from topic 'lox_in' to loxone as udp text messages.

## Zigbee

Project is meant as follow-up to https://www.zigbee2mqtt.io/.

# OLD obsolete stuff - to be removed, do not read below this line


    
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

Project is meant as follow-up to https://www.zigbee2mqtt.io/. Read original documentation to be familiar with zigbee2mqtt.
Zigbee2mqttManager is simple web application that is able to manage zigbee2mqtt bridge - especially to list connected zigbee devices,
enable/disable joining, device renaming. Second part of this module is a bridge between loxone and zigbee. 

##### What you should already know before you start:
- you know how to create udp inputs in loxone
- you already have custom flashed zigbee usb token (https://www.zigbee2mqtt.io/) 
            
Here is the same situation as in bridge, create 'config' file (use example), probably you don't need to modify anything.
 
Try to start zigbee stuff:

    ./run.sh [-d]

TODO describe basic workflow with zigbee, how to enable joining and rename devices using mobile app etc..

# How it works

Zigbee2mqtt module receives messages from zigbee devices and sends them to mqtt topic 'zigbee2mqtt'.
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

#### Debugging

You should use any mqtt client to monitor mqtt traffic and see if everything is going ok. For example MQTT Explorer, which shows all
traffic.

In fully working enviroment, there must be some messages in topics:

- zigbee2mqtt
- lox_in (if using loxone mapping, you will see messages going from )
- lox_out (if using loxone mapping, there must be 1:1 picture in mqtt messages of what you see in loxone web interface)

You also should check zigbee2mqtt/bridge/logs for more info about zigbee bridge.


## Grafana

TODO

## Common stuff:

You should consider to disable logging at all if running on system with disks-which-dont-like-too-much-writes. Especially on sdcards.
Set DEBUG=0 in all config files and also set LOGGING_DRIVER=none in .env files.